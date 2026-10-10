package com.pocketpenguin;

import android.content.SharedPreferences;
import java.util.Calendar;
import java.util.Random;

/**
 * The penguin's "mind": plays behaviour sequences (anticipation -> action -> after-glow), moves it through the room,
 * and picks the next sequence from context (time, weather, battery, charging, where it is, what it just did, recent touches).
 * Rendering knows nothing about this class except through {@link #in}.
 */
final class Brain {
    final Rig.In in = new Rig.In();
    final Room room;
    private final SharedPreferences prefs;
    private final Random rnd = new Random();

    // ---- world
    int w, h; float u = 1f, x, groundY, baseY;   // groundY = feet position right now (moves up when it perches on the cushion / bed)
    int perch;                                   // 0 floor, 1 on the cushion, 2 lying in the bed
    boolean front;                               // stepped forward to the bowls (which stand closer to the viewer)
    float petUntil = -1f;
    boolean riding; float rideX, rideY, rideX0, rideY0, rideB;   // riding on the buddy's back (positions come from Buddy)                        // tap-petting: each tap on the head keeps it purring for a moment
    int face = 1;
    float gaitAmp, gaitPh, time;
    float targetX = -1f; boolean hasTarget;
    float touchX = -1f;
    Care care; float careCool = 60f, ballCool = 90f, boredCool = 120f, moodCool = 50f;
    boolean hold;                              // a shared scene with Jinbei is running: stay put between the engine's cues
    float trickCool = 90f, sneezeCool = 120f, sunCool = 60f;
    int begGoal = Seqs.G_FOOD;                 // the bowl BEG_* sequences pace around
    float avoidX = -1f;                        // where the buddy lies: random wandering prefers other spots (it stands in front of the penguin)

    // ---- sequence player
    State state = State.IDLE; Step[] steps; int idx; int seqId = -1;
    float t, dur = 1f;
    private float exitFace;
    private State resume; private float resumeDur;   // TURN plays first, then the real step continues
    private float dodge = 1f;
    boolean stepChanged;                       // set whenever a new step starts (the engine clears it)
    int kickDir;                               // set when the penguin turns around (engine applies an inertial kick)
    private final int[] recent = { -1, -1, -1, -1 };
    private State lastState = State.IDLE;

    // ---- context
    boolean charging, petting, chargeGo; int battery = 100;
    float lastPetAt = -1000f, lastTouchAt = -1000f, wokeAt = -1000f;
    int lastMorningDay = -1;
    private int pendingSeq = -1; private boolean sleepCarry;
    private final float[] cool = new float[Seqs.COUNT];   // earliest time each sequence may start again
    private final float[] weights = new float[Seqs.COUNT];

    // ---- gaze (finger following)
    float gazeOn, gazeX, gazeY, gazeUntil = -1f;

    Brain(Room room, SharedPreferences prefs) { this.room = room; this.prefs = prefs; }

    void layout(int ww, int hh) {
        w = ww; h = hh;
        final float charH = Math.max(210f, Math.min(h * .20f, 370f));   // ~20 % smaller than v0.10 so the room (and the ball) has more space
        u = charH / 735f;
        baseY = room.groundY + h * .035f;           // stand on the floor in front of the furniture row
        groundY = baseY; perch = 0;
        if (x == 0f) x = w * .5f;
        clampX();
    }

    boolean on(String k) { return prefs.getBoolean(k, true); }
    int amount() { return prefs.getInt("speed", 1); }          // 0 calm / 1 normal / 2 lively
    boolean sysSaver;                          // the phone's own battery saver is on
    boolean powerSave() { return sysSaver || prefs.getBoolean("power", false); }

    boolean offscreenState() {
        switch (state) { case EXIT_SCREEN: case OFF_SCREEN: case PEEK_FROM_EDGE: case ENTER_SCREEN: return true; default: return false; }
    }
    boolean sleeping() { return state.isSleeping() || (state == State.SLEEP); }
    boolean asleepish() { return state.isSleeping() || state == State.SLEEPY || state == State.NOD_OFF; }
    boolean visibleOnScreen() { return x > -w * .1f && x < w * 1.1f && state != State.OFF_SCREEN; }

    // =================================================================================== playing sequences
    void start(int id) {
        if (riding && id != Seqs.RIDE) riding = false;      // anything else (a tap, a reaction) ends the ride
        seqId = id; steps = Seqs.ALL[id]; idx = 0; pushRecent(id);
        cool[id] = time + coolFor(id);
        beginStep();
    }

    void startRide() {
        if (offscreenState()) return;
        start(Seqs.RIDE); riding = true; rideB = 0f; rideX0 = x; rideY0 = groundY; rideX = x; rideY = groundY;
    }

    /** Reaction: interrupts what the penguin is doing (unless it is currently off screen, then it is queued). */
    void react(int id) {
        if (offscreenState()) { pendingSeq = id; return; }
        start(id);
    }

    private void pushRecent(int id) { for (int i = recent.length - 1; i > 0; i--) recent[i] = recent[i - 1]; recent[0] = id; }
    private boolean wasRecent(int id) { for (int r : recent) if (r == id) return true; return false; }

    private float coolFor(int id) {
        switch (id) {
            case Seqs.SLIP: return 150f; case Seqs.EDGE_EXIT: return 240f; case Seqs.SPIN_SEQ: return 200f; case Seqs.LOOK_WING: return 120f;
            case Seqs.LOOK_FEET: return 120f; case Seqs.NAP_NODDING: return 240f; case Seqs.BIG_JUMP_PLAY: return 160f; case Seqs.SHY_MOMENT: return 150f;
            case Seqs.RAIN_WATCH: case Seqs.SNOW_EXCITED: case Seqs.STARGAZE_SEQ: return 200f; case Seqs.WINDOW_LONG: return 300f;
            case Seqs.SNACK: return 120f; case Seqs.COZY_SIT: return 80f; case Seqs.STRETCH_ROUTINE: return 90f; case Seqs.CLOCK_CHECK: return 60f;
            default: return 0f;
        }
    }

    private void beginStep() {
        final Step s = steps[idx];
        final State prev = state;
        state = s.s; t = 0f; stepChanged = true;
        dur = s.ms / 1000f * ((s.flag & Seqs.F_JITTER) != 0 ? .75f + .65f * rnd.nextFloat() : 1f);
        in.rnd = (rnd.nextBoolean() ? 1f : -1f) * (.65f + .35f * rnd.nextFloat());
        resume = null;
        final int faceBefore = face;
        if (s.goal != Seqs.G_NONE && s.goal != Seqs.G_KEEP) setGoal(s.goal);
        if (state == State.START_WALK && face != faceBefore && s.goal != Seqs.G_EDGE) {      // turn around first, then start walking
            resume = State.START_WALK; resumeDur = dur; state = State.TURN; dur = .42f; kickDir = 0;
        }
        if (state == State.FOLLOW_TOUCH && hasTarget && Math.abs(targetX - x) > w * .36f) state = State.RUN;   // far away -> run
        switch (state) {
            case OFF_SCREEN: x = face > 0 ? w * 1.35f : -w * .35f; gaitAmp = 0f; break;
            case PEEK_FROM_EDGE: {
                final float bodyW = 560f * u;
                // come back from the opposite side to where it left: left through the right edge -> reappears at the left edge
                face = exitFace > 0 ? 1 : -1;
                x = face > 0 ? -bodyW * .20f : w + bodyW * .20f;
                gaitAmp = 0f; break; }
            case MULTI_TAP: dodge = x < w * .5f ? 1f : -1f; break;
            case BELLY_TICKLE: dodge = x < w * .5f ? 1f : -1f; break;
            case EXIT_SCREEN: exitFace = face; break;
            case EAT: if (care != null && seqId != Seqs.R_FETCH && Math.abs(x - room.goalX(Seqs.G_FOOD) * w) < w * .12f) care.eatP(); break;
            case DRINK: if (care != null && Math.abs(x - room.goalX(Seqs.G_WATER) * w) < w * .12f) care.drinkP(); break;
            case WAKE_UP: wokeAt = time; break;
            default: break;
        }
        if (prev == State.SLEEP && state != State.SLEEP) wokeAt = time;
    }

    private void setGoal(int goal) {
        switch (goal) {
            case Seqs.G_RANDOM: {
                float tx = w * (.22f + .56f * rnd.nextFloat());
                if (Math.abs(tx - x) < w * .16f) tx = x < w * .5f ? x + w * .22f : x - w * .22f;
                for (int tries = 0; tries < 8 && avoidX >= 0f && Math.abs(tx - avoidX) < w * .25f; tries++) tx = w * (.18f + .64f * rnd.nextFloat());
                targetX = clampRange(tx); break; }
            case Seqs.G_TOUCH: targetX = clampRange(touchX < 0f ? w * .5f : touchX); break;
            case Seqs.G_EDGE: face = x < w * .5f ? -1 : 1; if (rnd.nextInt(5) == 0) face = -face; targetX = face > 0 ? w * 1.4f : -w * .4f; break;
            case Seqs.G_ENTER: targetX = w * (.30f + .4f * rnd.nextFloat()); break;
            case Seqs.G_PACE: {                                       // a few steps to the other side of the bowl
                final float bx = room.goalX(begGoal) * w, side = x < bx ? 1f : -1f;
                targetX = clampRange(bx + side * w * (.06f + .04f * rnd.nextFloat())); break; }
            default: targetX = clampRange(room.goalX(goal) * w + (rnd.nextFloat() - .5f) * w * .03f); break;
        }
        hasTarget = true;
        final int nf = targetX > x ? 1 : -1;
        if (nf != face && Math.abs(targetX - x) > 30f * u) { face = nf; kickDir = nf; }
    }

    private float clampRange(float v) { return Math.max(w * .17f, Math.min(w * .83f, v)); }
    private void clampX() {
        if (offscreenState()) return;
        x = Math.max(w * .15f, Math.min(w * .85f, x));
    }

    private void nextStep() {
        idx++;
        if (steps != null && idx < steps.length) { beginStep(); return; }
        // sequence finished -> short idle, then think again (a sleeper keeps sleeping without waking up in between)
        sleepCarry = state.isSleeping();
        steps = null; seqId = -1; state = State.IDLE; t = 0f; stepChanged = true; hasTarget = false;
        final float gap = .5f + rnd.nextFloat() * 2.2f;
        dur = sleepCarry ? .02f : gap * (amount() == 0 ? 1.8f : amount() == 2 ? .6f : 1f) * (powerSave() ? 1.4f : 1f);
        if (pendingSeq >= 0) { final int p = pendingSeq; pendingSeq = -1; start(p); }
    }

    // =================================================================================== update
    void update(float dt) {
        time += dt; t += dt;
        in.s = state; in.t = t; in.dur = dur; in.time = time; in.face = face; in.weather = room.weather();
        // --- per-state movement / transitions
        float vx = 0f;
        final boolean mover = state.isMove();
        float amp = state.gait();
        if (mover && hasTarget) {
            final float dist = targetX - x;
            if (state == State.EXIT_SCREEN) {
                if (x < -w * .22f || x > w * 1.22f) { nextStep(); finish(dt); return; }
            } else if (Math.abs(dist) < 12f * u + gaitAmp * 6f * u || (t > dur)) {
                x = Math.abs(dist) < 40f * u ? targetX : x;
                nextStep(); finish(dt); return;
            } else {
                final int nf = dist > 0f ? 1 : -1;
                if (nf != face && Math.abs(dist) > 36f * u) { face = nf; kickDir = nf; }
            }
        }
        // gait amplitude eases in / out (inertia)
        gaitAmp += (amp - gaitAmp) * Math.min(1f, dt * (amp > gaitAmp ? 8f : 5.5f));
        if (gaitAmp < .015f && amp == 0f) gaitAmp = 0f;
        if (gaitAmp > 0f) gaitPh += dt * (6f + 2.6f * gaitAmp);
        else gaitPh = 0f;
        vx += face * 135f * u * gaitAmp;
        // scripted bits of motion
        switch (state) {
            case SLIDE: vx += face * 210f * u * (1f - Math.min(1f, t / dur)); break;
            case FALL: if (t < .35f) vx += face * 70f * u * (1f - t / .35f); break;
            case MULTI_TAP: vx += dodge * w * .075f * (t < dur * .8f ? 1f : .3f); break;
            case BELLY_TICKLE: if (t > 1.05f && t < 1.8f) vx += dodge * 70f * u; break;
            case GREETING_USER: break;
            default: break;
        }
        x += vx * dt;
        clampX();
        updateFeet(dt);
        if (riding) {
            rideB = Math.min(1f, rideB + dt * 2.4f); final float e = rideB * rideB * (3f - 2f * rideB);
            x = rideX0 + (rideX - rideX0) * e; groundY = rideY0 + (rideY - rideY0) * e - (float) Math.sin(Math.PI * rideB) * h * .05f;
        }
        // --- step timing
        if (t >= dur) {
            if (resume != null) { state = resume; resume = null; t = 0f; dur = resumeDur; stepChanged = true; }
            else if (steps == null) { think(); }
            else if (state == State.PET && (petting || time < petUntil)) { t = dur - .05f; }   // keep purring while the user keeps stroking
            else nextStep();
        }
        finish(dt);
    }

    /** Sitting / sleeping near the cushion or the bed puts the penguin ON it; walking away puts it back on the floor. */
    private boolean bowlSeq() {
        switch (seqId) {
            case Seqs.EAT_MEAL: case Seqs.DRINK_WATER: case Seqs.BEG_FOOD: case Seqs.BEG_WATER: case Seqs.R_FED: case Seqs.R_WATERED: case Seqs.SNACK: return true;
            default: return false;
        }
    }

    private void updateFeet(float dt) {
        final boolean settle = state == State.SIT || state == State.SLEEP || state == State.SLEEPY || state == State.NOD_OFF
                || state == State.YAWN || state == State.WAKE_UP || state == State.REST_WHILE_CHARGING || state == State.HIDE;
        final float dc = Math.abs(x - room.cushionX), db = Math.abs(x - room.bedX);
        if (state.isMove() || offscreenState() || (dc > w * .11f && db > w * .13f)) perch = 0;       // left the furniture
        else if (perch == 0 && settle && !front) { if (dc < w * .09f) perch = 1; else if (db < w * .10f) perch = 2; }
        // the bowls stand in front: on the way to them the penguin also walks towards the viewer, and back again when it leaves
        final float nearBowl = Math.min(Math.abs(x - room.goalX(Seqs.G_FOOD) * w), Math.abs(x - room.goalX(Seqs.G_WATER) * w));
        if (perch == 0 && !riding && bowlSeq() && nearBowl < w * .12f) front = true;
        else if (front && (state.isMove() || offscreenState() || perch != 0) && !(bowlSeq() && nearBowl < w * .12f)) front = false;
        final float frontY = room.bowlY - h * .012f;
        final float target = perch == 1 ? room.groundY - h * .004f : perch == 2 ? room.groundY + h * .02f : front ? frontY : baseY;
        groundY += (target - groundY) * Math.min(1f, dt * (front || Math.abs(groundY - baseY) > h * .01f ? 4.5f : 8f));
    }

    /** True while the penguin is lying in the bed (the engine then draws the bed's front wall over it). */
    boolean inBed() { return perch == 2 && groundY > room.groundY + h * .008f; }

    /** Size factor for how near the viewer the penguin stands (1 on the normal floor line, a bit bigger in front, smaller at the back). */
    float depth() { return 1f + (groundY - baseY) / h * 1.6f; }

    private void finish(float dt) {
        in.s = state; in.t = t; in.dur = dur; in.face = face; in.gaitPh = gaitPh; in.gaitAmp = gaitAmp;
        in.micro = state == State.IDLE || state.isCalm();
        in.clockDx = room.clockDir(x); in.windowDx = room.windowDir(x);
        // finger following eases in and out
        if (gazeUntil > time && !offscreenState() && !asleepish()) { gazeOn += (1f - gazeOn) * Math.min(1f, dt * 10f); in.gx = gazeX; in.gy = gazeY; in.gazeOk = true; }
        else { gazeOn += (0f - gazeOn) * Math.min(1f, dt * 4f); in.gazeOk = gazeOn > .02f; }
        in.gazeOn = gazeOn;
        lastState = state;
    }

    // =================================================================================== deciding what to do next
    private void think() {
        stepChanged = true;
        if (pendingSeq >= 0) { final int p = pendingSeq; pendingSeq = -1; start(p); return; }
        if (hold) { start(Seqs.HOLD); return; }
        final Calendar cal = Calendar.getInstance();
        final int hour = cal.get(Calendar.HOUR_OF_DAY), doy = cal.get(Calendar.DAY_OF_YEAR);
        final int wx = room.weather();
        final boolean night = hour >= 23 || hour < 5, evening = hour >= 19 || hour < 5;
        final int amt = amount();
        final float rare = amt == 0 ? .5f : amt == 2 ? 1.6f : 1f;
        final float lively = powerSave() ? .6f : 1f;
        final boolean awakeToday = lastMorningDay == doy;
        final boolean nearBed = Math.abs(x - room.goalX(Seqs.G_BED) * w) < w * .08f;
        final boolean nearCharger = Math.abs(x - room.goalX(Seqs.G_CHARGER) * w) < w * .08f;
        final boolean petted = time - lastPetAt < 45f, touched = time - lastTouchAt < 30f;
        final boolean lowBat = battery <= 15 && !charging;
        // already on the charger when the wallpaper starts / the sequence was missed: go to the charging spot once
        if (charging && on("charging") && !chargeGo && !nearCharger) { chargeGo = true; start(Seqs.R_CHARGE_START); return; }
        // keep sleeping through the night / while charging until something changes
        if (sleepCarry) {
            sleepCarry = false;
            if (charging && nearCharger) { start(Seqs.CHARGE_REST); return; }
            if (night && nearBed) { start(Seqs.SLEEP_MORE); return; }
        }
        // looking after itself: hungry / thirsty -> goes to the bowl (or waits next to an empty one), plays with the ball now and then
        if (care != null && !night && !charging && !lowBat) {
            // an empty bowl is asked for again and again (the begging itself is short, so this does not take over the whole day)
            if (time > careCool && care.pHungry()) {
                if (care.foodEmpty()) { careCool = time + 45f + rnd.nextFloat() * 40f; begGoal = Seqs.G_FOOD; start(Seqs.BEG_FOOD); }
                else { careCool = time + 70f + rnd.nextFloat() * 60f; start(Seqs.EAT_MEAL); }
                return;
            }
            if (time > careCool && care.pThirsty()) {
                if (care.waterEmpty()) { careCool = time + 50f + rnd.nextFloat() * 40f; begGoal = Seqs.G_WATER; start(Seqs.BEG_WATER); }
                else { careCool = time + 70f + rnd.nextFloat() * 60f; start(Seqs.DRINK_WATER); }
                return;
            }
            if (time > boredCool && care.pBored() && !asleepish()) {
                boredCool = time + 80f + rnd.nextFloat() * 70f;
                start(care.pMood < .25f && rnd.nextInt(3) == 0 ? Seqs.R_CRY : rnd.nextInt(3) == 0 ? Seqs.R_SAD : Seqs.WANT_PLAY); return;
            }
            // tricks it has learnt (なつき度 level 3: handstand, level 4: somersault)
            if (time > trickCool && care.bondLevel() >= 3 && rnd.nextInt(5) == 0) { trickCool = time + 150f + rnd.nextFloat() * 120f; start(care.bondLevel() >= 4 && rnd.nextBoolean() ? Seqs.TRICK_FLIP : Seqs.TRICK_HAND); return; }
            // a sneeze on cold or rainy days
            if (time > sneezeCool && (wx == 1 || wx == 2 || cal.get(Calendar.MONTH) <= 1 || cal.get(Calendar.MONTH) == 11) && rnd.nextInt(8) == 0) { sneezeCool = time + 200f + rnd.nextFloat() * 200f; start(Seqs.SNEEZE_SEQ); return; }
            // sunbathing by the window on sunny days
            final int sc = room.sceneId();
            if (time > sunCool && (sc == Room.SUNNY || sc == Room.MORNING || sc == Room.NORMAL_ROOM) && hour >= 8 && hour < 17 && rnd.nextInt(6) == 0) { sunCool = time + 260f + rnd.nextFloat() * 200f; start(Seqs.SUNBATHE); return; }
            // in a really good mood: dances now and then, or laughs to itself
            if (time > moodCool && care.pMood > .8f && rnd.nextInt(4) == 0) { moodCool = time + 70f + rnd.nextFloat() * 80f; start(rnd.nextInt(3) == 0 ? Seqs.R_LAUGH : Seqs.DANCE_SEQ); return; }
            if (time > ballCool && rnd.nextInt(5) == 0 && !asleepish()) { ballCool = time + 100f + rnd.nextFloat() * 140f; care.cheer(.06f); start(Seqs.BALL_PLAY); return; }
        }
        // standing right in front of Jinbei hides it: step aside now and then
        if (avoidX >= 0f && Math.abs(x - avoidX) < w * .14f && rnd.nextInt(3) != 0) { start(Seqs.WANDER); return; }
        final float[] W = weights;
        for (int i = 0; i < W.length; i++) W[i] = 0f;

        // everyday life
        W[Seqs.WANDER] = 22f * lively; W[Seqs.LOOK_AROUND] = 16f; W[Seqs.SIT_REST] = 10f; W[Seqs.FLAP_HAPPY] = 7f * lively;
        W[Seqs.JUMP_PLAY] = 6f * lively * rare; W[Seqs.STRETCH_ROUTINE] = 5f; W[Seqs.COZY_SIT] = evening ? 12f : 7f;
        W[Seqs.SNACK] = (hour == 7 || hour == 8 || hour == 12 || hour == 13 || hour == 18 || hour == 19) ? 14f : 5f;
        W[Seqs.CLOCK_CHECK] = on("clock") ? ((hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19) ? 11f : 6f) : 0f;
        // rare surprises
        W[Seqs.SLIP] = 3f * rare * lively; W[Seqs.LOOK_WING] = 3f * rare; W[Seqs.LOOK_FEET] = 3f * rare; W[Seqs.SPIN_SEQ] = 2.2f * rare * lively;
        W[Seqs.BIG_JUMP_PLAY] = 2.2f * rare * lively; W[Seqs.SHY_MOMENT] = 1.6f * rare; W[Seqs.NAP_NODDING] = (evening || lowBat) ? 5f : 1f;
        W[Seqs.EDGE_EXIT] = on("door") ? 3.2f * rare : 0f;
        W[Seqs.WINDOW_LONG] = 2f * rare;
        // the room talks to the penguin
        if (wx == 1) W[Seqs.RAIN_WATCH] = 15f;
        if (wx == 2) W[Seqs.SNOW_EXCITED] = 16f;
        if (wx == 3) W[Seqs.STARGAZE_SEQ] = 11f;
        if (wx == 3 || wx == 1) W[Seqs.WINDOW_LONG] += 3f;
        // morning ritual (once a day)
        if (!awakeToday && hour >= 5 && hour < 11) W[Seqs.MORNING] = 120f;
        // night: sleepy, bed, fewer energetic things
        if (night) {
            for (int i : new int[] { Seqs.WANDER, Seqs.FLAP_HAPPY, Seqs.JUMP_PLAY, Seqs.SLIP, Seqs.BIG_JUMP_PLAY, Seqs.SPIN_SEQ, Seqs.EDGE_EXIT, Seqs.SNACK }) W[i] *= .25f;
            if (nearBed && asleepish()) W[Seqs.SLEEP_MORE] = 70f;
            W[Seqs.BED_ROUTINE] = nearBed ? 20f : 62f;
            W[Seqs.NAP_NODDING] += 6f; W[Seqs.SIT_REST] += 6f;
        } else if (hour >= 21) { W[Seqs.BED_ROUTINE] = 10f; W[Seqs.NAP_NODDING] += 3f; }
        // charging
        if (charging) { W[Seqs.CHARGE_REST] = nearCharger ? 55f : 0f; W[Seqs.WANDER] *= .4f; W[Seqs.EDGE_EXIT] = 0f; }
        if (lowBat) { W[Seqs.SIT_REST] += 10f; W[Seqs.NAP_NODDING] += 8f; W[Seqs.JUMP_PLAY] = 0f; W[Seqs.BIG_JUMP_PLAY] = 0f; W[Seqs.SPIN_SEQ] = 0f; }
        // the user was just here
        if (petted) { W[Seqs.SEEK_ATTENTION] = 20f; W[Seqs.FLAP_HAPPY] += 12f; W[Seqs.JUMP_PLAY] += 6f; W[Seqs.SHY_MOMENT] += 3f; W[Seqs.EDGE_EXIT] = 0f; W[Seqs.SLIP] *= .3f; }
        else if (touched) { W[Seqs.SEEK_ATTENTION] = 9f; W[Seqs.WANDER] += 5f; }
        // personality (grows from how the user plays with it)
        if (care != null) {
            switch (care.personality(System.currentTimeMillis())) {
                case Care.SPOILED: W[Seqs.SEEK_ATTENTION] += 12f; W[Seqs.SHY_MOMENT] += 3f; W[Seqs.EDGE_EXIT] *= .5f; break;
                case Care.PLAYFUL: W[Seqs.JUMP_PLAY] *= 1.8f; W[Seqs.BIG_JUMP_PLAY] *= 2f; W[Seqs.SPIN_SEQ] *= 2f; W[Seqs.FLAP_HAPPY] *= 1.4f; W[Seqs.EDGE_EXIT] *= 1.6f; break;
                default: W[Seqs.SIT_REST] *= 1.8f; W[Seqs.COZY_SIT] *= 1.8f; W[Seqs.NAP_NODDING] *= 1.5f; W[Seqs.WANDER] *= .7f; W[Seqs.WINDOW_LONG] *= 1.6f; break;
            }
        }
        // hungry, thirsty or bored: glances at the user more often in between
        if (care != null && (care.pHungry() || care.pThirsty() || care.pBored())) { W[Seqs.SEEK_ATTENTION] += 10f; W[Seqs.FLAP_HAPPY] *= .5f; W[Seqs.JUMP_PLAY] *= .5f; }
        // do not repeat the same thing, respect cool-downs
        for (int i = 0; i <= Seqs.SHY_MOMENT; i++) {
            if (time < cool[i]) W[i] = 0f;
            else if (wasRecent(i)) W[i] *= .15f;
        }
        if (seqId == Seqs.SLEEP_MORE) { /* allowed to repeat */ }
        float total = 0f; for (int i = 0; i <= Seqs.SHY_MOMENT; i++) total += W[i];
        int pick = Seqs.WANDER;
        if (total > 0f) { float r = rnd.nextFloat() * total; for (int i = 0; i <= Seqs.SHY_MOMENT; i++) { if (r < W[i]) { pick = i; break; } r -= W[i]; } }
        if (pick == Seqs.MORNING) lastMorningDay = doy;
        start(pick);
    }

    /** First think after the wallpaper appears: if it is night and the penguin is in bed, just keep sleeping. */
    void wakeForUser(boolean greeting) {
        if (offscreenState()) { pendingSeq = greeting ? Seqs.R_GREET : Seqs.R_WAKE; return; }
        if (sleeping() || state == State.SLEEPY || state == State.NOD_OFF) start(Seqs.R_WAKE_GREET);
        else if (greeting) start(greetSeq());
    }
    /** Greetings get fancier as the penguin grows attached (なつき度). */
    int greetSeq() { final int lv = care == null ? 1 : care.bondLevel(); return lv >= 4 ? Seqs.R_GREET_TOP : lv >= 3 ? Seqs.R_GREET_BIG : Seqs.R_GREET; }

    String debugName() { return seqId >= 0 ? Seqs.NAMES[seqId] + "/" + state : "IDLE"; }
}
