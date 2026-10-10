package com.pocketpenguin;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import java.util.Random;

/**
 * "Jinbei" - a chubby whale-shark plush that lives in the room next to the penguin.
 * Own small state machine (sleepy by nature: it dozes most of the day, wakes up for short play sessions),
 * own part rig (body / tail / fin with springs, squash & stretch, runtime face) and a few shared moments with the penguin
 * (visit, bump, nap together, giving the penguin a ride on its back). Nothing is allocated per frame.
 */
final class Buddy {
    // ---- states
    static final int SNOOZE = 0, STIR = 1, YAWN = 2, IDLE = 3, LOOK = 4, CRAWL = 5, SWIM = 6, WIGGLE = 7, ROLL = 8, BUBBLES = 9,
            SURPRISE = 10, PETTED = 11, TICKLE = 12, SHY = 13, CALL = 14, CHASE_TAIL = 15, DROWSY = 16, JOY_HOP = 17, EAT_B = 18, DRINK_B = 19, P_BALL = 20,   // P_BALL is only a pick, not a state
            ANGRY_B = 21, SULK_B = 22, SAD_B = 23, DANCE_B = 24, LAUGH_B = 25, SCUFFLE_B = 26;   // emotions (v0.11), mostly cued by the engine
    static final String[] NAMES = { "SNOOZE", "STIR", "YAWN", "IDLE", "LOOK", "CRAWL", "SWIM", "WIGGLE", "ROLL", "BUBBLES", "SURPRISE",
            "PETTED", "TICKLE", "SHY", "CALL", "CHASE_TAIL", "DROWSY", "JOY_HOP", "EAT_B", "DRINK_B", "P_BALL",
            "ANGRY_B", "SULK_B", "SAD_B", "DANCE_B", "LAUGH_B", "SCUFFLE_B" };
    // ---- things it asks the penguin to do (the engine carries them out)
    static final int PEN_NONE = 0, PEN_GREET = 1, PEN_JUMP = 2, PEN_GOTO = 3, PEN_MOUNT = 4, PEN_DISMOUNT = 5, PEN_FLAP = 6;
    // ---- touch zones
    static final int Z_NONE = 0, Z_HEAD = 1, Z_BELLY = 2, Z_TAIL = 3, Z_BODY = 4;
    // ---- shared-moment phases
    private static final int D_NONE = 0, D_GO = 1, D_LINGER = 2, D_CALL = 3, D_WAIT = 4, D_MOUNT = 5, D_CARRY = 6, D_NAPGO = 7, D_EAT = 8, D_DRINK = 9, D_BEG = 10;
    private static final int K_VISIT = 0, K_BUMP = 1, K_RIDE = 2;

    private static final float GROUND = 500f, CX = 400f, EYE_X = 584f, EYE_Y = 338f;

    private final Fx fx;
    private final Random rnd = new Random();

    // ---- world
    int w, h; float ub = .4f, pu = .64f, x, baseY; int face = 1;
    float time, t, dur = 10f; int state = SNOOZE;
    boolean asleep = true, sleepNext, wantWake;
    private float awakeUntil, sleepLock = -1f, goalX, vel, crawlPh, spinPh, bub, nextBlink = 2f, blinkT = -1f, nextFx, nextLook;
    private boolean hasGoal, carry; private float lookGoal;
    private float petUntil = -1f, gazeUntil = -1f, gazeX, gazeY, sootheUntil = -1f;
    private int lastA = -1, lastB = -1;
    // ---- shared moments
    private int duo = D_NONE, duoKind, duoTries; private float duoTimer, duoWait, rideCool, napCool, rideSpot;
    private State lastPenState = State.IDLE;
    // ---- context supplied by the engine every frame
    Care care; float ballX = -1f; boolean ballMoving; private int ballChain; private float careCool = 80f;
    int hour = 12, amount = 1; boolean raining, penOff, penSleeping, penRiding, penCharging, userBusy; float penX; State penState = State.IDLE;
    // ---- requests to the engine
    int reqPen; float reqX;
    // ---- v0.11: scenes cued by the engine, food
    boolean hold;                 // a shared scene is running: stay where you are between cues
    boolean ateLast;              // set when Jinbei just emptied the food bowl (the engine may let the penguin get angry)
    private int wantEat;          // 1 food / 2 water: woke up because of the bowl, go there right after the yawn
    private int moodFace;         // 0 normal, 1 angry brows + frown, 2 sad brows + frown

    // ---- animation springs (art units unless noted)
    private final Spring sx = new Spring(3.2f, .5f, 1f), sy = new Spring(3.2f, .5f, 1f), rot = new Spring(2.4f, .45f), tail = new Spring(2.8f, .3f),
            fin = new Spring(3.2f, .4f, 10f), lift = new Spring(2.4f, .6f), eye = new Spring(9f, .9f), smile = new Spring(5f, .8f, .3f),
            mouth = new Spring(7f, .7f), blush = new Spring(3f, .9f, .3f), lookX = new Spring(7f, .85f), lookY = new Spring(7f, .85f),
            faceF = new Spring(3.4f, .55f, 1f);

    private final Paint bm = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    Buddy(Fx fx) {
        this.fx = fx;
        line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        shadow.setStyle(Paint.Style.FILL); shadow.setColor(0xFF3A2A1C);
        duoTimer = 45f + rnd.nextFloat() * 40f; rideCool = 150f;
    }

    // ===================================================================== layout
    /** sizeStep 0..4 (default 2): how big the whale shark is relative to the penguin. It stands slightly in front of the penguin. */
    void layout(int ww, int hh, Room room, float penguinU, int sizeStep) {
        w = ww; h = hh; pu = penguinU; ub = penguinU * (.82f + .09f * sizeStep);
        baseY = room.groundY + h * .049f;
        if (x == 0f) x = w * .60f;
        x = clampX(x);
        lift.snap(0f);
    }
    private float clampX(float v) { return Math.max(w * .30f, Math.min(w * .70f, v)); }
    private float rr(float a, float b) { return a + (b - a) * rnd.nextFloat(); }

    // ===================================================================== the shared things the penguin may need
    /** True while something is animating (keeps the frame rate up); a sleeping buddy lets the loop slow down. */
    boolean busy() { return !(state == SNOOZE || state == IDLE || state == DROWSY) || faceF.p != face; }
    /** Test hook: every animated value finite and in a sane range. */
    boolean sane() {
        final float[] v = { x, sx.p, sy.p, rot.p, tail.p, fin.p, lift.p, eye.p, smile.p, mouth.p, blush.p, lookX.p, lookY.p, faceF.p };
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f) || Math.abs(f) > 3000f) return false;
        return sx.p > .6f && sx.p < 1.5f && sy.p > .6f && sy.p < 1.5f && Math.abs(faceF.p) < 1.6f && lift.p > -40f && lift.p < h * .3f;
    }
    String debug() { return NAMES[state] + " duo=" + duo + " asleep=" + asleep; }
    /** The buddy stands in front of the penguin, except while it carries it or while the penguin sleeps on its bed. */
    boolean inFront(boolean penguinAsleep) { return !riding() && !penguinAsleep; }
    boolean riding() { return duo == D_MOUNT || duo == D_CARRY; }
    float riderX() { return x - face * 28f * ub; }
    float riderY() { return baseY - lift.p - (GROUND - 198f) * ub * sy.p + 12f * ub; }
    float headX() { return x + face * 250f * ub; }
    float headY() { return baseY - lift.p - 150f * ub; }

    // ===================================================================== state entry
    private void go(int s, float d) {
        if (state == ROLL && s != ROLL) rot.snap(0f);
        state = s; t = 0f; dur = d; nextFx = 0f;
        switch (s) {
            case CRAWL: case SWIM: break;
            case LOOK: lookGoal = rnd.nextBoolean() ? 1f : -1f; if (rnd.nextInt(3) == 0) fx.spawn(Fx.QUEST, headX(), headY() - 30f * pu, 0f, -55f * pu, 1.3f, 34f * pu); break;
            case SURPRISE: fx.spawn(Fx.EXCL, headX(), headY() - 40f * pu, 0f, -45f * pu, 1.1f, 42f * pu); break;
            case CALL: fx.spawn(Fx.EXCL, headX(), headY() - 40f * pu, 0f, -45f * pu, 1.2f, 42f * pu); break;
            case CHASE_TAIL: spinPh = 0f; break;
            case EAT_B: if (care != null) { final boolean had = !care.foodEmpty(); care.eatB(); ateLast = had && care.foodEmpty(); } break;
            case DRINK_B: if (care != null) care.drinkB(); break;
            case STIR: case WIGGLE: case JOY_HOP: case TICKLE: break;
            default: break;
        }
        if (s != CHASE_TAIL && Math.abs(faceF.p - face) > 1.01f) faceF.snap(face);
    }
    private void faceToward(float tx) { if (Math.abs(tx - x) > w * .02f) face = tx > x ? 1 : -1; }
    private void goTo(float gx, boolean swim, float timeout) {
        goalX = clampX(gx); hasGoal = true; faceToward(goalX); vel = 0f; crawlPh = 0f;
        go(swim ? SWIM : CRAWL, timeout);
    }

    // ===================================================================== touch API
    int zoneAt(float px, float py) {
        final float ax = CX + (px - x) / (ub * face), ay = GROUND - (baseY - lift.p - py) / ub;
        final float ex = (ax - 395f) / 345f, ey = (ay - 335f) / 195f;
        if (ex * ex + ey * ey > 1f) return Z_NONE;
        if (ax > 540f) return Z_HEAD;
        if (ax < 200f) return Z_TAIL;
        if (ay > 360f) return Z_BELLY;
        return Z_BODY;
    }

    void gazeAt(float fx0, float fy0) {
        gazeX = Math.max(-1f, Math.min(1f, (fx0 - headX()) / (w * .3f))) * face;
        gazeY = Math.max(-1f, Math.min(1f, (fy0 - headY()) / (h * .2f)));
        gazeUntil = time + 1.6f;
    }

    private void wake(float stayS) {
        asleep = false; sleepNext = false; wantWake = false;
        awakeUntil = Math.max(awakeUntil, time + stayS);
    }

    void tap(int zone, int taps) {
        if (riding()) return;
        duoAbort();
        if (asleep || state == DROWSY) {                       // gently wake up: stir, yawn, look around
            asleep = false; wantWake = true; awakeUntil = time + 75f; sleepLock = time + 75f; go(STIR, 1.0f);
            fx.spawn(Fx.SPARK, headX(), headY() - 20f * pu, 0f, -40f * pu, .8f, 22f * pu); return;
        }
        wake(45f); sleepNext = false;
        if (taps >= 4) { go(SURPRISE, 1.2f); return; }
        if (taps == 3) { go(ROLL, 2.6f); return; }
        switch (zone) {
            case Z_BELLY: go(TICKLE, 2.2f); break;
            case Z_TAIL: go(CHASE_TAIL, 2.6f); break;
            case Z_HEAD:
                if (taps >= 2) { petUntil = time + 1.6f; go(PETTED, 1.2f); }
                else { go(WIGGLE, 1.7f); fx.spawn(Fx.HEART, headX(), headY() - 30f * pu, 10f * pu, -60f * pu, 1.6f, 26f * pu); }
                break;
            default: if (rnd.nextBoolean()) go(JOY_HOP, 1.4f); else go(WIGGLE, 1.6f); break;
        }
    }

    /** Called while the finger strokes the body (swipe petting). */
    void stroke(float dir) {
        petUntil = time + .6f;
        if (riding()) return;
        if (asleep) { sootheUntil = time + 1.0f; return; }
        duoAbort();
        wake(30f);
        if (state != PETTED) go(PETTED, .8f);
    }

    /** The engine cues a state (scenes: quarrel, dance, laugh ...). faceDir 0 keeps the current facing. */
    void act(int s, float d, int faceDir) {
        if (riding()) return;
        duoAbort(); asleep = false; sleepNext = false; wantWake = false; awakeUntil = Math.max(awakeUntil, time + 60f);
        if (faceDir != 0) face = faceDir;
        go(s, d);
    }
    /** Swim quickly to gx (running away in a game of tag). */
    void flee(float gx) { if (riding()) return; duoAbort(); asleep = false; awakeUntil = Math.max(awakeUntil, time + 60f); goTo(gx, true, 5f); vel = face * w * .1f; }
    float leftLimit() { return w * .30f; }
    float rightLimit() { return w * .70f; }

    void userTouchedPenguin() { if (!asleep && duo == D_NONE && (state == IDLE || state == LOOK) && rnd.nextInt(3) == 0) { faceToward(penX); go(LOOK, 1.8f); lookGoal = 1f; } }

    private void duoAbort() { if (duo != D_NONE) { if (duo == D_MOUNT || duo == D_CARRY) { carry = false; reqPen = PEN_DISMOUNT; } duo = D_NONE; duoTimer = rr(70f, 140f); hasGoal = false; } }

    // ===================================================================== update
    void update(float dt) {
        time += dt; t += dt;
        context(dt);
        final float breathe = (float) Math.sin(time * (asleep ? 1.5f : 2.3f));
        final float ph = Math.min(1f, t / Math.max(dur, .01f));
        float vx = 0f, liftT = 0f, sxT = 1f, syT = 1f, rotT = 0f, tailT = 0f, finT = 10f, eyeT = 1f, smT = .5f, moT = 0f, blT = .3f, lkT = 0f, lyT = 0f;
        boolean arrived = false;
        moodFace = 0;

        switch (state) {
            case SNOOZE: {
                eyeT = 0f; smT = .35f; syT = 1f + .028f * breathe; sxT = 1f - .012f * breathe; tailT = 4f * (float) Math.sin(time * .9f); finT = 14f;
                if (time < sootheUntil) { smT = 1f; blT = .8f; tailT = 14f * (float) Math.sin(time * 4f); nextFx -= dt; if (nextFx <= 0f) { nextFx = .9f; fx.spawn(Fx.HEART, headX(), headY() - 20f * pu, 8f * pu, -50f * pu, 1.4f, 22f * pu); } }
                bub += dt / 5.4f;
                if (bub > 1.12f) { bub = 0f; for (int i = 0; i < 3; i++) fx.spawn(Fx.PUFF, headX() + face * 12f * pu, headY() + 70f * pu, face * (20f + 18f * i) * pu, -30f * pu, .5f, 16f * pu); }
                nextZ(dt); break; }
            case STIR: rotT = 3f * (float) Math.sin(t * 9f) * (1f - ph); tailT = 18f * (float) Math.sin(t * 8f) * (1f - ph); eyeT = ph > .55f ? .35f : 0f; smT = .3f; break;
            case YAWN: {
                final float open = ph < .18f ? 0f : ph < .4f ? (ph - .18f) / .22f : ph < .72f ? 1f : Math.max(0f, 1f - (ph - .72f) / .2f);
                moT = open; eyeT = ph > .9f ? (sleepNext ? .5f : 1f) : 0f; rotT = -4f * open; finT = 10f + 34f * open; tailT = 14f * open;
                syT = ph < .18f ? .94f : 1f + .05f * open; sxT = ph < .18f ? 1.03f : 1f - .025f * open; smT = .3f; break; }
            case IDLE: smT = .55f; tailT = 10f * (float) Math.sin(time * 2.6f); finT = 12f + 6f * (float) Math.sin(time * 3.1f); syT = 1f + .015f * breathe; lkT = lookGoal * .5f; break;
            case LOOK: lkT = lookGoal; rotT = 2.5f * lookGoal; eyeT = 1.12f; tailT = 6f * (float) Math.sin(time * 3f); finT = 14f; smT = .5f; break;
            case CRAWL: {
                crawlPh += dt * (amount == 2 ? 4.8f : 4.2f);
                final float s = (float) Math.sin(crawlPh), push = Math.max(0f, s);
                vx = face * w * .19f * push * (hasGoal ? Math.min(1f, Math.abs(goalX - x) / (w * .06f) + .25f) : 1f);
                sxT = 1f + .09f * s; syT = 1f - .07f * s; rotT = -2f * s; tailT = 15f * (float) Math.sin(crawlPh - 1.2f); finT = 20f + 18f * (float) Math.sin(crawlPh + 1f);
                liftT = push * h * .005f; smT = .65f;
                if (s > 0f && ((int) ((crawlPh - dt * 4.5f) / 6.2832f * 2f)) != (int) (crawlPh / 6.2832f * 2f)) fx.spawn(Fx.PUFF, x - face * 190f * ub, baseY - 6f * ub, -face * 30f * pu, -8f * pu, .45f, 15f * pu);
                break; }
            case SWIM: {
                final float want = (carry ? .060f : .085f) * w * (hasGoal ? Math.min(1f, Math.abs(goalX - x) / (w * .10f) + .15f) : 1f);
                vel += (face * want - vel) * Math.min(1f, dt * 2.4f); vx = vel;
                liftT = h * (carry ? .035f : .058f) + h * .007f * (float) Math.sin(time * 2.2f); rotT = -3f + 2.5f * (float) Math.sin(time * 2.2f + 1f);
                tailT = 26f * (float) Math.sin(time * 5.4f); finT = 25f + 22f * (float) Math.sin(time * 5.4f + .8f); sxT = 1.03f; syT = .98f; smT = .85f; blT = .5f;
                nextFx -= dt; if (nextFx <= 0f && !carry) { nextFx = rr(.5f, .9f); fx.spawn(Fx.BUBBLE, x - face * 150f * ub, baseY - lift.p - 150f * ub, -face * 12f * pu, -42f * pu, 1.7f, 11f * pu); }
                break; }
            case WIGGLE: {
                final float dec = 1f - ph * .5f;
                rotT = 7f * (float) Math.sin(time * 15f) * dec; tailT = 34f * (float) Math.sin(time * 14f); finT = 30f + 26f * (float) Math.sin(time * 13f + .5f);
                syT = 1f + .03f * (float) Math.sin(time * 30f); eyeT = 0f; smT = 1f; blT = 1f; moT = .15f;
                nextFx -= dt; if (nextFx <= 0f && ph < .8f) { nextFx = .5f; fx.spawn(Fx.HEART, headX() - face * 40f * pu, headY() - 30f * pu, 14f * pu, -60f * pu, 1.5f, 26f * pu); }
                break; }
            case ROLL: {
                if (ph < .2f) { syT = .84f; sxT = 1.1f; smT = 1f; eyeT = 1f; }
                else if (ph < .85f) {
                    final float a = (ph - .2f) / .65f, e = a * a * (3f - 2f * a);
                    liftT = h * .09f * (float) Math.sin(Math.PI * a); rot.snap(360f * e * face); rotT = 360f * e * face; tailT = 30f * (float) Math.sin(time * 12f); finT = 50f; eyeT = 1.2f; smT = 1f; moT = .3f;
                } else { rot.snap(0f); syT = .86f; sxT = 1.1f; smT = 1f; eyeT = 0f; blT = 1f; if (nextFx == 0f) { nextFx = 1f; for (int i = -1; i <= 1; i += 2) fx.spawn(Fx.PUFF, x + i * 160f * ub, baseY - 6f * ub, i * 90f * pu, -12f * pu, .55f, 24f * pu); fx.spawn(Fx.HEART, headX(), headY() - 30f * pu, 0f, -70f * pu, 1.6f, 28f * pu); } }
                break; }
            case BUBBLES: {
                rotT = -9f; tailT = 12f * (float) Math.sin(time * 4f); finT = 22f; smT = .7f; eyeT = 1f;
                final float cyc = (t % .55f) / .55f; moT = cyc < .55f ? .65f : 0f;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .55f; fx.spawn(Fx.BUBBLE, headX() + face * 18f * pu, headY() + 54f * pu, face * 16f * pu, -62f * pu, 2.1f, rr(10f, 22f) * pu); }
                break; }
            case SURPRISE: {
                if (ph < .15f) { syT = .9f; sxT = 1.05f; }
                else { final float k = 1f - (ph - .15f) / .85f; sxT = 1f + .1f * k; syT = 1f + .1f * k; liftT = h * .022f * (float) Math.sin(Math.PI * Math.min(1f, (ph - .15f) * 2.2f)); }
                eyeT = 1.35f; moT = .45f; finT = 60f; tailT = -20f; smT = .1f; break; }
            case PETTED:
                eyeT = 0f; smT = 1f; blT = 1f; rotT = 3f * (float) Math.sin(time * 5f); tailT = 22f * (float) Math.sin(time * 7f); finT = 26f + 10f * (float) Math.sin(time * 6f); sxT = 1.02f;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .7f; fx.spawn(Fx.HEART, headX() - face * 30f * pu, headY() - 30f * pu, 12f * pu, -62f * pu, 1.6f, 26f * pu); }
                break;
            case TICKLE: {
                rotT = 13f * (float) Math.sin(t * 12f) * (1f - ph * .4f); tailT = 38f * (float) Math.sin(t * 13f); finT = 40f + 30f * (float) Math.sin(t * 14f);
                moT = .75f; eyeT = 0f; smT = 1f; blT = 1f; syT = 1f + .04f * (float) Math.sin(t * 24f); liftT = h * .01f * Math.abs((float) Math.sin(t * 6f));
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .35f; fx.spawn(Fx.SPARK, x + (rnd.nextFloat() - .5f) * 300f * ub, baseY - 180f * ub, 0f, -50f * pu, .7f, 22f * pu); }
                break; }
            case SHY:
                blT = 1f; eyeT = 0f; finT = 72f; rotT = -5f; sxT = .95f; syT = 1.03f; smT = .25f; tailT = 8f * (float) Math.sin(time * 5f);
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .9f; fx.spawn(Fx.HEART, headX(), headY() - 30f * pu, 16f * pu, -55f * pu, 1.6f, 24f * pu); }
                break;
            case CALL: {
                liftT = h * .02f * Math.abs((float) Math.sin(t * 7f)); finT = 55f + 30f * (float) Math.sin(t * 10f); tailT = 24f * (float) Math.sin(t * 9f); eyeT = 1.1f; smT = 1f; moT = .25f * Math.abs((float) Math.sin(t * 7f));
                lkT = (penX > x ? 1f : -1f) * face; break; }
            case CHASE_TAIL: {
                final float e = ph * ph * (3f - 2f * ph); spinPh = e * 6f * (float) Math.PI;
                faceF.snap((float) Math.cos(spinPh) * face);
                tailT = 40f * (float) Math.sin(t * 10f); rotT = 3f * (float) Math.sin(t * 9f); liftT = h * .012f * Math.abs((float) Math.sin(spinPh)); smT = 1f; eyeT = 1.1f; finT = 35f; break; }
            case DROWSY:
                eyeT = Math.max(0f, 1f - ph * 1.1f); rotT = 4f * (float) Math.sin(ph * 6f) * (1f - ph) + ph * 3f; smT = .3f; syT = 1f + .02f * breathe; finT = 12f - 4f * ph; tailT = 5f;
                break;
            case EAT_B: case DRINK_B: {
                rotT = 7f; eyeT = 0f; smT = 1f; blT = .8f; tailT = 14f * (float) Math.sin(time * 6f); finT = 18f; syT = 1f - .02f * Math.abs((float) Math.sin(t * 9f));
                moT = .15f + .5f * Math.abs((float) Math.sin(t * 8f));
                nextFx -= dt; if (nextFx <= 0f) { nextFx = state == DRINK_B ? .3f : .7f; fx.spawn(state == DRINK_B ? Fx.BUBBLE : Fx.SPARK, headX() + face * 10f * pu, headY() + 70f * pu, face * 10f * pu, -50f * pu, .9f, 12f * pu); }
                break; }
            case JOY_HOP: {
                final float s = (float) Math.sin(Math.PI * ph * 2f), a = Math.abs(s);
                liftT = h * .035f * a; syT = 1f + .06f * a; sxT = 1f - .04f * a; tailT = 30f * (float) Math.sin(time * 12f); finT = 40f + 20f * (float) Math.sin(time * 12f); smT = 1f; eyeT = 0f; blT = .9f;
                if (nextFx == 0f && ph > .48f) { nextFx = 1f; for (int i = -1; i <= 1; i += 2) fx.spawn(Fx.PUFF, x + i * 150f * ub, baseY - 6f * ub, i * 80f * pu, -10f * pu, .5f, 20f * pu); }
                break; }
            // ---------------------------------------------------------------- emotions
            case ANGRY_B: {                   // puffed up, red cheeks, trembling, fin and tail stiff
                moodFace = 1; final float tr = (float) Math.sin(t * 26f);
                sxT = 1.09f; syT = 1.07f; rotT = -4f + 2f * tr; tailT = 20f + 8f * tr; finT = 55f; eyeT = .75f; smT = 0f; blT = 1.4f; moT = 0f;
                liftT = h * .004f * Math.abs(tr);
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .75f; fx.spawn(Fx.ANGER, headX() - face * 30f * pu, headY() - 50f * pu, 0f, -12f * pu, .9f, 26f * pu); }
                break; }
            case SULK_B: {                    // turned away, eyes shut, little huffs
                moodFace = 1; eyeT = 0f; smT = .1f; blT = 1.1f; rotT = 4f; tailT = 6f * (float) Math.sin(time * 2f); finT = 8f; syT = .97f + .01f * breathe; sxT = 1.02f;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = 1.8f; fx.spawn(Fx.PUFF, headX() + face * 30f * pu, headY() + 40f * pu, face * 40f * pu, -10f * pu, .6f, 16f * pu); }
                break; }
            case SAD_B: {                     // droopy, teary eyes
                moodFace = 2; eyeT = .7f; smT = .05f; blT = .4f; rotT = 6f; syT = .96f + .01f * breathe; sxT = 1.03f; tailT = 3f * (float) Math.sin(time * 1.5f); finT = 6f; lyT = .6f;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .9f; fx.spawn(Fx.TEAR, headX() - face * 60f * pu, headY() + 10f * pu, -face * 20f * pu, 10f * pu, 1f, 12f * pu); }
                break; }
            case DANCE_B: {                   // bobbing to the beat, tail and fin wave, a hop every other beat
                final float beat = t * 4.2f, sb = (float) Math.sin(beat);
                liftT = h * .018f * Math.abs((float) Math.cos(beat)); rotT = 7f * sb; tailT = 34f * sb; finT = 40f + 30f * (float) Math.sin(beat + 1f);
                sxT = 1f + .04f * sb; syT = 1f - .04f * sb; smT = 1f; eyeT = 0f; blT = 1f; moT = .2f;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .6f; fx.spawn(Fx.NOTE, headX(), headY() - 40f * pu, face * 20f * pu, -55f * pu, 1.6f, 22f * pu); }
                break; }
            case LAUGH_B: {                   // rolling with laughter: shaking, mouth open, eyes squeezed
                final float ha = Math.abs((float) Math.sin(t * 12f));
                rotT = -6f + 5f * (float) Math.sin(t * 12f); syT = 1f + .05f * ha; sxT = 1f - .03f * ha; liftT = h * .008f * ha; eyeT = 0f; smT = 1f; blT = 1.1f; moT = .4f + .4f * ha;
                tailT = 30f * (float) Math.sin(t * 13f); finT = 45f + 20f * ha;
                nextFx -= dt; if (nextFx <= 0f) { nextFx = .45f; fx.spawn(Fx.SPARK, headX(), headY() - 30f * pu, face * 20f * pu, -60f * pu, .8f, 20f * pu); }
                break; }
            case SCUFFLE_B: {                 // tussle: violent wobble (mostly hidden in the dust cloud)
                moodFace = 1; final float j = (float) Math.sin(t * 29f), k2 = (float) Math.sin(t * 21f + 2f);
                rotT = 15f * j; vx = face * w * .06f * k2; liftT = h * .015f * Math.abs(j); tailT = 40f * k2; finT = 60f + 30f * j; eyeT = 1.2f; moT = .4f; blT = 1.3f;
                break; }
            default: break;
        }

        // ---- movement
        if ((state == CRAWL || state == SWIM) && hasGoal) {
            final float dist = goalX - x;
            if (Math.abs(dist) < w * (state == SWIM ? .02f : .012f) || t > dur) arrived = true;
            else { final int nf = dist > 0f ? 1 : -1; if (nf != face && Math.abs(dist) > w * .03f) face = nf; }
        } else if (state == SWIM || state == CRAWL) { if (t > dur) arrived = true; }
        x = clampX(x + vx * dt);
        if (x <= w * .30f + 1f || x >= w * .70f - 1f) { if (state == SWIM || state == CRAWL) arrived = true; }

        // ---- gaze / blink
        if (gazeUntil > time && !asleep && state != SNOOZE) { lkT = gazeX; lyT = gazeY; }
        else if (state == IDLE) { nextLook -= dt; if (nextLook <= 0f) { nextLook = rr(1.2f, 3.2f); lookGoal = rnd.nextInt(3) == 0 ? 0f : rnd.nextBoolean() ? 1f : -1f; } }
        float blink = 1f;
        if (blinkT >= 0f) { blinkT += dt; blink = 1f - (float) Math.sin(Math.PI * Math.min(1f, blinkT / .17f)); if (blinkT > .17f) { blinkT = -1f; nextBlink = time + rr(2f, 5.5f); } }
        else if (time > nextBlink && eye.p > .6f) blinkT = 0f;

        // ---- springs
        sx.update(sxT, dt); sy.update(syT, dt); if (state != ROLL) rot.update(rotT, dt); tail.update(tailT, dt); fin.update(finT, dt); lift.update(liftT, dt);
        eye.update(eyeT * (state == SNOOZE ? 1f : blink), dt); smile.update(smT, dt); mouth.update(moT, dt); blush.update(blT, dt);
        lookX.update(lkT, dt); lookY.update(lyT, dt);
        if (state != CHASE_TAIL) faceF.update(face, dt);

        if (arrived) { hasGoal = false; vel = 0f; think(); }
        else if (t >= dur && state != CRAWL && state != SWIM) think();
    }

    private void nextZ(float dt) {
        zTimer -= dt;
        if (zTimer <= 0f) { zTimer = 2.4f; fx.spawn(Fx.ZZZ, headX() + face * 20f * pu, headY() - 20f * pu, face * 16f * pu, -44f * pu, 2.5f, 26f * pu); }
    }
    private float zTimer = 1f;
    private static final int[] PICKS = { IDLE, LOOK, CRAWL, SWIM, WIGGLE, ROLL, BUBBLES, CHASE_TAIL, JOY_HOP, SHY, P_BALL };

    // ===================================================================== context (what the penguin and the room are doing)
    private void context(float dt) {
        final State ps = penState;
        if (ps != lastPenState) {
            if ((ps == State.FALL || ps == State.SLIDE) && !asleep && !riding() && !hold && (state == IDLE || state == LOOK || state == CRAWL || state == SWIM) && duo == D_NONE) { faceToward(penX); if (rnd.nextBoolean()) go(SURPRISE, 1.2f); else go(LAUGH_B, 2.2f); }
            lastPenState = ps;
        }
        if (hold) return;
        if (asleep || penOff || duo != D_NONE) { if (duo == D_CARRY && !penRiding && t > .6f) { duoAbort(); go(SURPRISE, 1.1f); } return; }
        if (state != IDLE && state != LOOK) return;
        // hungry / thirsty: goes to the bowl by itself
        if (care != null && time > careCool && duo == D_NONE && hour < 23 && hour >= 6) {
            if (care.bHungry() && !care.foodEmpty()) { careCool = time + 60f + rnd.nextFloat() * 40f; goEat(1); return; }
            if (care.bThirsty() && !care.waterEmpty()) { careCool = time + 60f + rnd.nextFloat() * 40f; goEat(2); return; }
            if (care.bHungry() && care.foodEmpty()) { careCool = time + 70f + rnd.nextFloat() * 50f; duo = D_BEG; goTo(bowlSpot(w * Room.FOOD_X), true, 12f); return; }
        }
        // wants to nap next to a sleeping penguin
        if (penSleeping && time > napCool) { napCool = time + 240f; duo = D_NAPGO; final float side = penX > w * .5f ? -1f : 1f; goTo(penX + side * w * .3f, false, 16f); return; }
        // shared moments with the penguin
        duoTimer -= dt * (amount == 0 ? .6f : amount == 2 ? 1.5f : 1f);
        if (duoTimer <= 0f) {
            final boolean free = !penOff && !penSleeping && !penRiding && !penCharging && !userBusy && (ps == State.IDLE || ps.isCalm() || ps == State.WALK);
            if (!free || hour >= 23 || hour < 6) { duoTimer = 15f; return; }
            startDuo();
        }
    }

    private void startDuo() {
        duoTries = 0;
        final float r = rnd.nextFloat();
        duoKind = (time > rideCool && r < .35f) ? K_RIDE : r < .65f ? K_VISIT : K_BUMP;
        if (duoKind == K_RIDE) { duo = D_CALL; faceToward(penX); rideSpot = x; go(CALL, 1.5f); reqPen = PEN_GOTO; reqX = x; }
        else { duo = D_GO; goTo(penX - (penX > x ? 1f : -1f) * w * .26f, Math.abs(penX - x) > w * .3f, 12f); }
    }

    /** Called when a movement / timed state ends. Decides what comes next. */
    private void think() {
        if (state == YAWN && sleepNext) { sleepNext = false; go(DROWSY, 2.3f); return; }
        if (state == DROWSY) { asleep = true; go(SNOOZE, rr(9f, 22f)); bub = 0f; return; }
        if (state == STIR) { if (wantWake) { wantWake = false; go(YAWN, 2.2f); return; } go(SNOOZE, rr(10f, 28f)); return; }
        if (state == YAWN) {
            asleep = false;
            if (wantEat != 0) { final int k = wantEat; wantEat = 0; if (k == 1 ? !care.foodEmpty() : !care.waterEmpty()) { goEat(k); return; } }
            go(IDLE, rr(1.5f, 3f)); return;
        }
        if (state == PETTED && time < petUntil) { go(PETTED, .7f); return; }
        if (hold && !asleep) { go(IDLE, .6f); return; }
        if (duo != D_NONE && duoThink()) return;
        if (!asleep && ballChain > 0 && (state == SWIM || state == CRAWL)) { ballChain--; if (ballX >= 0f) { chaseBall(); return; } }
        if (state == EAT_B || state == DRINK_B) { go(WIGGLE, 1.5f); return; }
        final boolean night = hour >= 23 || hour < 6;
        // a hungry sleeper wakes up when there is something in the bowl
        if (asleep && !night && care != null && time > careCool && !penRiding) {
            if (care.bHungry() && !care.foodEmpty()) { careCool = time + 60f; wakeFor(1); return; }
            if (care.bThirsty() && !care.waterEmpty()) { careCool = time + 60f; wakeFor(2); return; }
        }
        if (asleep) {
            float wake = night ? .03f : hour < 9 ? .14f : .55f;
            if (penSleeping) wake *= .25f;
            if (time < sleepLock) wake = 0f;
            if (rnd.nextFloat() < wake) { wake(rr(90f, 180f)); wantWake = true; go(STIR, 1.3f); return; }
            if (rnd.nextFloat() < .2f) { go(STIR, 1.5f); return; }
            go(SNOOZE, rr(9f, 22f)); return;
        }
        if (time > awakeUntil || (night && time > awakeUntil - 40f)) { sleepNext = true; go(YAWN, 2.2f); return; }
        // ---- awake: pick something to do
        final float act = amount == 0 ? .6f : amount == 2 ? 1.5f : 1f;
        final int[] ids = PICKS;
        final float[] wv = { 26f, 13f, 20f * act, (raining ? 28f : 9f) * act, 7f * act, (raining ? 9f : 4f) * act, (raining ? 20f : 6f) * act, 4f * act, 6f * act, 2f, (ballX >= 0f ? 9f : 0f) * act };
        float total = 0f;
        for (int i = 0; i < ids.length; i++) { if (ids[i] == lastA || ids[i] == lastB) wv[i] *= .2f; total += wv[i]; }
        float r = rnd.nextFloat() * total; int pick = IDLE;
        for (int i = 0; i < ids.length; i++) { if (r < wv[i]) { pick = ids[i]; break; } r -= wv[i]; }
        lastB = lastA; lastA = pick;
        switch (pick) {
            case CRAWL: { float g = w * (.28f + .44f * rnd.nextFloat()); if (Math.abs(g - x) < w * .1f) g = x < w * .5f ? x + w * .16f : x - w * .16f; goTo(g, false, 14f); break; }
            case SWIM: { float g = w * (.26f + .48f * rnd.nextFloat()); if (Math.abs(g - x) < w * .12f) g = x < w * .5f ? x + w * .2f : x - w * .2f; goTo(g, true, 16f); break; }
            case P_BALL: ballChain = 2; chaseBall(); break;
            case IDLE: go(IDLE, rr(2f, 4.5f)); break;
            case LOOK: go(LOOK, rr(1.6f, 2.6f)); break;
            case WIGGLE: go(WIGGLE, 1.8f); break;
            case ROLL: go(ROLL, 2.6f); break;
            case BUBBLES: go(BUBBLES, rr(2.6f, 4.2f)); break;
            case CHASE_TAIL: go(CHASE_TAIL, 2.8f); break;
            case JOY_HOP: go(JOY_HOP, 1.5f); break;
            default: go(SHY, 1.9f); break;
        }
    }

    private void chaseBall() { goTo(ballX, true, 9f); }

    /** Where Jinbei's middle must be so that its head (snout) is over the bowl. */
    private float bowlSpot(float bowlX) { final float side = x < bowlX ? -1f : 1f; return bowlX + side * 205f * ub; }
    private void goEat(int kind) {
        final float bx = w * (kind == 1 ? Room.FOOD_X : Room.WATER_X);
        duo = kind == 1 ? D_EAT : D_DRINK; goTo(bowlSpot(bx), true, 12f);
    }

    /** The user filled a bowl: come and eat / drink (hungry enough and not busy). A sleeping Jinbei wakes up for it. */
    void onFilled(int kind) {
        if (riding() || duo != D_NONE || care == null || hold) return;
        final boolean want = kind == 1 ? care.bFull < .85f : care.bHyd < .85f;
        if (!want) return;
        if (asleep || state == DROWSY || state == SNOOZE) { wakeFor(kind); return; }
        goEat(kind);
    }
    /** Wake up because of the bowl: stir, yawn, then go and eat. */
    private void wakeFor(int kind) {
        wantEat = kind; asleep = false; wantWake = true; awakeUntil = Math.max(awakeUntil, time + 70f); sleepLock = time + 40f; go(STIR, .9f);
        fx.spawn(Fx.EXCL, headX(), headY() - 40f * pu, 0f, -45f * pu, 1.1f, 40f * pu);
    }

    private boolean duoThink() {
        switch (duo) {
            case D_GO:
                if (Math.abs(x - penX) < w * .34f || duoTries > 3) {
                    faceToward(penX);
                    if (duoKind == K_BUMP) { reqPen = PEN_JUMP; go(JOY_HOP, 1.4f); } else { reqPen = PEN_GREET; go(WIGGLE, 1.9f); }
                    duo = D_LINGER; return true;
                }
                duoTries++; goTo(penX - (penX > x ? 1f : -1f) * w * .26f, true, 12f); return true;
            case D_LINGER: duo = D_NONE; duoTimer = rr(80f, 170f); go(IDLE, rr(3f, 6f)); return true;
            case D_CALL: duo = D_WAIT; duoWait = 0f; go(IDLE, .5f); return true;
            case D_WAIT: {
                final boolean here = Math.abs(penX - rideSpot) < w * .09f && !penState.isMove();
                duoWait += dur;
                if (here && !penOff && !penSleeping) {
                    duo = D_MOUNT; reqPen = PEN_MOUNT; carry = true; sy.snap(.86f); sx.snap(1.06f);
                    fx.spawn(Fx.SPARK, riderX(), riderY(), 0f, -30f * pu, .8f, 22f * pu);
                    go(IDLE, 1.0f); lookGoal = 1f; return true;
                }
                if (duoWait > 11f) { duo = D_NONE; duoTimer = 60f; go(SHY, 1.6f); return true; }
                go(IDLE, .4f); return true; }
            case D_MOUNT: {
                duo = D_CARRY;
                float g = x < w * .5f ? w * (.62f + .08f * rnd.nextFloat()) : w * (.38f - .06f * rnd.nextFloat());
                goTo(g, true, 18f); return true; }
            case D_CARRY:
                carry = false; reqPen = PEN_DISMOUNT; duo = D_LINGER; rideCool = time + 420f;
                fx.spawn(Fx.HEART, headX(), headY() - 30f * pu, 0f, -60f * pu, 1.6f, 28f * pu);
                go(WIGGLE, 1.9f); return true;
            case D_EAT: duo = D_NONE; faceToward(w * Room.FOOD_X); go(EAT_B, 3.8f); return true;
            case D_DRINK: duo = D_NONE; faceToward(w * Room.WATER_X); go(DRINK_B, 3.2f); return true;
            case D_BEG: duo = D_NONE; faceToward(w * Room.FOOD_X); go(rnd.nextBoolean() ? SAD_B : CALL, 2.6f); return true;
            case D_NAPGO: duo = D_NONE; sleepNext = true; go(YAWN, 2.2f); return true;
            default: duo = D_NONE; return false;
        }
    }

    // ===================================================================== drawing
    void draw(Canvas c, BuddyArt a) {
        if (a == null || a.body == null) return;
        final float liftPx = lift.p;
        // soft floor shadow (shrinks and fades as it hovers)
        final float k = 1f - Math.min(.45f, liftPx / (h * .14f));
        shadow.setAlpha((int) (62f * k));
        rf.set(x - 330f * ub * k * sx.p, baseY - 14f * ub, x + 330f * ub * k * sx.p, baseY + 18f * ub);
        c.drawOval(rf, shadow);
        c.save();
        c.translate(x, baseY - liftPx);
        c.scale(ub * faceF.p, ub);
        c.translate(-CX, -GROUND);
        c.rotate(rot.p, CX, 335f);
        c.scale(sx.p, sy.p, CX, GROUND);
        // tail (behind the body)
        c.save(); c.rotate(tail.p, 150f, 338f); part(c, a.tail, BuddyLayout.BD_TAIL_L, BuddyLayout.BD_TAIL_T); c.restore();
        part(c, a.body, BuddyLayout.BD_BODY_L, BuddyLayout.BD_BODY_T);
        drawFace(c);
        c.save(); c.rotate(-fin.p, 505f, 420f); part(c, a.fin, BuddyLayout.BD_FIN_L, BuddyLayout.BD_FIN_T); c.restore();
        if (state == SNOOZE && bub > 0f) {
            final float r = 8f + 36f * Math.min(1f, bub);
            fill.setColor(0xFFCDEBFF); fill.setAlpha(100); c.drawCircle(752f, 392f + r * .3f, r, fill);
            line.setColor(0xFF8FCBEF); line.setStrokeWidth(5f); line.setAlpha(230); c.drawCircle(752f, 392f + r * .3f, r, line);
            fill.setColor(0xFFFFFFFF); fill.setAlpha(230); c.drawCircle(752f - r * .35f, 392f + r * .3f - r * .35f, r * .2f, fill);
        }
        c.restore();
    }

    private void part(Canvas c, android.graphics.Bitmap b, int l, int t0) { if (b != null) c.drawBitmap(b, l, t0, bm); }

    private void drawFace(Canvas c) {
        final float lx = lookX.p * 9f, ly = lookY.p * 6f;
        final float e = eye.p;
        // cheek
        if (blush.p > .02f) { fill.setColor(0xFFF7BCB8); fill.setAlpha((int) (Math.min(1f, blush.p) * 190f)); rf.set(522f, 361f, 574f, 391f); c.drawOval(rf, fill); }
        if (e < .2f) {   // closed eye: sleepy "︶" or happy "⌒"
            line.setColor(0xFF101114); line.setAlpha(255); line.setStrokeWidth(8f);
            rf.set(554f + lx * .6f, 318f, 614f + lx * .6f, 358f);
            if (smile.p > .62f) c.drawArc(rf, 200f, 140f, false, line); else c.drawArc(rf, 20f, 140f, false, line);
        } else {
            final float ry = 19f * Math.min(1.35f, e), rx = 17f + 2f * Math.max(0f, e - 1f) * 3f;
            fill.setColor(0xFF26282E); fill.setAlpha(255); rf.set(EYE_X + lx - rx, EYE_Y + ly - ry, EYE_X + lx + rx, EYE_Y + ly + ry); c.drawOval(rf, fill);
            fill.setColor(0xFFFFFFFF); c.drawCircle(EYE_X + lx + 5f, EYE_Y + ly - ry * .38f, 5.2f, fill);
        }
        // brows (angry: slanting down towards the snout, sad: rising towards the snout)
        if (moodFace != 0) {
            line.setColor(0xFF101114); line.setAlpha(255); line.setStrokeWidth(9f);
            if (moodFace == 1) c.drawLine(548f + lx * .6f, 286f, 618f + lx * .6f, 306f, line);
            else c.drawLine(550f + lx * .6f, 304f, 616f + lx * .6f, 284f, line);
        }
        // mouth (on the pink snout)
        final float m = mouth.p;
        if (moodFace != 0 && m <= .06f) {                     // frown
            line.setColor(0xFF101114); line.setStrokeWidth(6f); line.setAlpha(230);
            rf.set(670f, 428f, 720f, 452f); c.drawArc(rf, 200f, 140f, false, line);
        } else if (m > .06f) {
            final float ry = 10f + 36f * m;
            rf.set(666f, 436f - ry * .15f, 722f, 436f + ry);
            fill.setColor(0xFF7A2C44); fill.setAlpha(255); c.drawOval(rf, fill);
            fill.setColor(0xFFEE7685); rf.set(678f, 436f + ry * .45f, 710f, 436f + ry * .95f); c.drawOval(rf, fill);
            line.setColor(0xFF101114); line.setStrokeWidth(6f); line.setAlpha(255); rf.set(666f, 436f - ry * .15f, 722f, 436f + ry); c.drawArc(rf, 0f, 360f, false, line);
        } else {
            line.setColor(0xFF101114); line.setStrokeWidth(6f); line.setAlpha(210);
            rf.set(668f, 418f, 722f, 418f + 14f + 26f * Math.min(1f, smile.p));
            c.drawArc(rf, 15f, 150f, false, line);
        }
    }
}
