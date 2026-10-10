package com.pocketpenguin;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import java.util.Calendar;
import java.util.Random;

/**
 * Pocket Penguin live wallpaper (v0.6).
 * The Engine only wires things together: Room (scenes), Brain (behaviour sequences), Rig (part animation + drawing),
 * Fx (hearts / "!" / Zzz ...), touch input and phone events (charging, screen on, unlock, clock).
 */
public class PenguinWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() { return new PenguinEngine(); }

    class PenguinEngine extends Engine {
        private static final int Z_NONE = 0, Z_HEAD = 1, Z_BELLY = 2, Z_BODY = 3;

        final Handler handler = new Handler(Looper.getMainLooper());
        final SharedPreferences prefs = getSharedPreferences("penguin", MODE_PRIVATE);
        final Random random = new Random();
        final Room room = new Room();
        final Brain brain = new Brain(room, prefs);
        final Rig rig = new Rig();
        final Fx fx = new Fx();
        PenguinArt art; BuddyArt bart;
        Care care; Decor decor; Talk talk;
        boolean talkOn = true, wasRiding; long ctxMs; long ctxBits; int lastNewItem;
        final Buddy buddy = new Buddy(fx);
        boolean buddyOn = true, buddyPetting; int bzone, lastTapTarget = -1;

        boolean visible, laidOut, pendingGreet, charging;
        long lastFrame, lastTickMs, lastGreetMs, lastTouchMs;
        int hour = 12, minute = 0, month = 0, w, h;
        float density = 2.5f; int floorStep = 5;

        // touch
        float downX, downY, lastX, lastY, pathLen; long downT; int zone = Z_NONE; boolean petting; float petDir; int taps; long lastTapMs;

        // fx emitters
        float emitHeart, emitZ, emitSpark, emitDust, emitMood, lastMultiAt = -100f;

        final Runnable loop = new Runnable() {
            @Override public void run() {
                if (!visible) return;
                final long t0 = SystemClock.uptimeMillis();
                frame(t0);
                final long cost = SystemClock.uptimeMillis() - t0;
                handler.postDelayed(this, Math.max(1L, delay() - cost));
            }
        };

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                final String a = i.getAction(); if (a == null) return;
                if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                    setCharging(true);
                    if (brain.on("charging")) { brain.chargeGo = true; brain.react(Seqs.R_CHARGE_START); }
                } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                    final boolean resting = brain.state == State.REST_WHILE_CHARGING;
                    setCharging(false);
                    brain.chargeGo = false;
                    if (resting || brain.state == State.RUN_TO_CHARGER) brain.react(Seqs.R_CHARGE_END);
                } else if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                    if (!brain.on("screen")) return;
                    final long now = SystemClock.uptimeMillis();
                    if (now - lastGreetMs < 4000) return;
                    lastGreetMs = now;
                    if (visible) brain.wakeForUser(true); else pendingGreet = true;
                }
            }
        };

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            density = getResources().getDisplayMetrics().density;
            art = new PenguinArt(getResources());
            bart = new BuddyArt(getResources());
            care = new Care(prefs, System.currentTimeMillis()); care.unlockAll = prefs.getBoolean("unlockall", false); care.fast = prefs.getBoolean("carefast", true);
            brain.care = care; buddy.care = care;
            decor = new Decor(care, room, prefs.getInt("decor_seen", 0));
            talk = new Talk(); talkOn = prefs.getBoolean("talk", true);
            buddyOn = prefs.getBoolean("buddy", true);
            testScene = prefs.getInt("test_scene", 0);
            final IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_POWER_CONNECTED); f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            f.addAction(Intent.ACTION_SCREEN_ON); f.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(receiver, f);
        }

        void setCharging(boolean c) { if (testScene != 0) c = false; charging = c; brain.charging = c; room.charging = c; }   // CI screenshots: the emulator always "charges"

        void readBattery() {
            try {
                final Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b == null) return;
                final int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) brain.battery = level * 100 / scale;
                final int plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                setCharging(plug != 0);
            } catch (Exception ignored) { }
        }

        void readClock() {
            final Calendar c = Calendar.getInstance();
            hour = c.get(Calendar.HOUR_OF_DAY); minute = c.get(Calendar.MINUTE); month = c.get(Calendar.MONTH);
        }
        int forcedScene() { return enabled("time") ? prefs.getInt("scene", -1) : (prefs.getInt("scene", -1) >= 0 ? prefs.getInt("scene", -1) : Room.NORMAL_ROOM); }
        boolean enabled(String k) { return prefs.getBoolean(k, true); }

        // ================================================================== lifecycle
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int ww, int hh) {
            super.onSurfaceChanged(holder, format, ww, hh);
            if (ww <= 0 || hh <= 0) return;
            w = ww; h = hh;
            readClock();
            floorStep = prefs.getInt("floor", 5);
            room.layout(w, h, floorStep);
            brain.layout(w, h); buddy.layout(w, h, room, brain.u, prefs.getInt("buddysize", 2));
            decor.layout(w, h); talk.layout(w, h);
            if (!laidOut) { room.first(hour, month, forcedScene()); brain.t = 0f; brain.dur = .8f; laidOut = true; }
            render();
            if (visible) { handler.removeCallbacks(loop); lastFrame = SystemClock.uptimeMillis(); handler.post(loop); }   // visibility may have arrived before the surface
        }

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            handler.removeCallbacks(loop);
            if (!v && care != null) { care.save(); if (talk != null) talk.abort(); endScene(); }
            if (v && laidOut) {
                lastFrame = SystemClock.uptimeMillis(); lastTickMs = 0L;
                readClock(); readBattery();
                if (prefs.getInt("floor", 5) != floorStep && w > 0) { floorStep = prefs.getInt("floor", 5); room.layout(w, h, floorStep); brain.layout(w, h); buddy.layout(w, h, room, brain.u, prefs.getInt("buddysize", 2)); }   // slider changed in the app
                if (w > 0) buddy.layout(w, h, room, brain.u, prefs.getInt("buddysize", 2));   // size slider
                buddyOn = prefs.getBoolean("buddy", true); talkOn = prefs.getBoolean("talk", true); care.unlockAll = prefs.getBoolean("unlockall", false);
                care.fast = prefs.getBoolean("carefast", true); care.advance(System.currentTimeMillis());
                room.tick(hour, month, forcedScene());
                if (pendingGreet) { pendingGreet = false; brain.wakeForUser(true); }
                handler.post(loop);
            }
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false; handler.removeCallbacks(loop); if (care != null) care.save(); if (talk != null) talk.abort();
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            visible = false; handler.removeCallbacks(loop);
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
            if (care != null) care.save(); room.release(); if (art != null) art.recycle(); if (bart != null) bart.recycle();
            super.onDestroy();
        }

        long delay() {
            final State s = brain.state;
            final boolean active = (talk != null && talk.speaking()) || (decor != null && decor.ballMoving()) || !(s == State.IDLE || s.isCalm()) || fx.any() || brain.gazeOn > .05f || rig.events != 0 || (buddyOn && buddy.busy());
            final boolean ps = brain.powerSave();
            if (s == State.OFF_SCREEN) return 100L;
            if (active) return ps ? 50L : 33L;
            if (s.isSleeping() || s == State.SLEEP) return ps ? 200L : 100L;
            if (room.fastAnimating()) return ps ? 100L : 50L;
            return ps ? 125L : 66L;
        }

        // ================================================================== one frame
        void frame(long now) {
            float dt = (now - lastFrame) / 1000f; lastFrame = now;
            if (dt > .066f) dt = .066f; if (dt < .001f) dt = .001f;
            if (now - lastTickMs > 2000L) { lastTickMs = now; readClock(); room.tick(hour, month, forcedScene()); }
            room.update(dt);
            if (buddyOn) {
                buddy.hour = hour; buddy.amount = brain.amount(); buddy.raining = room.weather() == 1;
                buddy.penX = brain.x; buddy.penState = brain.state; buddy.penOff = brain.offscreenState(); buddy.penSleeping = brain.sleeping();
                buddy.penRiding = brain.riding; buddy.penCharging = brain.charging; buddy.userBusy = brain.time - brain.lastTouchAt < 8f;
                buddy.update(dt);
                brain.avoidX = buddy.asleep ? -1f : buddy.x;
                handleBuddyRequest();
                if (buddy.riding()) { brain.rideX = buddy.riderX(); brain.rideY = buddy.riderY(); if (brain.riding) brain.face = buddy.face; }
            }
            brain.update(dt);
            care.update(dt, System.currentTimeMillis());
            decor.update(dt, System.currentTimeMillis(), brain.x, brain.face, brain.state.isMove() && !brain.riding, brain.state == State.JUMP || brain.state == State.BIG_JUMP,
                    buddy.x, buddy.face, buddy.state == Buddy.CRAWL || buddy.state == Buddy.SWIM || buddy.state == Buddy.ROLL);
            if (decor.ballMoving()) talk.sticky(TalkData.BALL, 40f);
            updateScenes(dt);
            updateTalk(dt);
            if (brain.kickDir != 0) { rig.kick(brain.kickDir); brain.kickDir = 0; }
            rig.update(dt, brain.in);
            emitFx(dt);
            fx.update(dt);
            render();
        }

        void render() {
            if (w <= 0) return;
            final android.view.SurfaceHolder sh = getSurfaceHolder();
            Canvas c = null;
            try {
                c = sh.lockCanvas();
                if (c == null) return;
                room.drawBack(c, hour, minute);
                decor.draw(c, Math.min(1f, room.cur.lamp));
                // depth order: whoever stands further forward is drawn later (the penguin steps in front of Jinbei at the bowls)
                final boolean bFront = buddyOn && buddy.inFront(brain.sleeping()) && !(brain.groundY > buddy.baseY + h * .004f && !brain.riding);
                if (buddyOn && !bFront) buddy.draw(c, bart);
                if (brain.state != State.OFF_SCREEN && art != null) { rig.draw(c, art, brain.x, brain.groundY, brain.u * brain.depth(), brain.face, 255, 0f); if (brain.inBed()) room.drawBedFront(c); }
                if (bFront) buddy.draw(c, bart);
                drawCloud(c);
                decor.drawBowls(c);
                fx.draw(c);
                room.drawFront(c);
                if (talkOn) talk.draw(c, headX(), headY() - 60f * brain.u, buddy.headX(), buddy.headY());
            } catch (Exception ignored) {
            } finally { if (c != null) { try { sh.unlockCanvasAndPost(c); } catch (Exception ignored) { } } }
        }

        // ================================================================== particles
        float headX() { return brain.x + (rig.bx.p + rig.hx.p) * brain.u * brain.depth(); }
        float headY() { return brain.groundY + ((60f - Rig.GROUND) * rig.sy.p + rig.by.p + rig.hy.p) * brain.u * brain.depth(); }
        float feetY() { return brain.groundY; }

        void burst(int type, int n, float spread, float up, float life, float size) {
            final float u = brain.u;
            for (int i = 0; i < n; i++) {
                final float a = (random.nextFloat() - .5f) * 2f;
                fx.spawn(type, headX() + a * spread * u, headY() + random.nextFloat() * 60f * u, a * 40f * u, -up * u * (.6f + random.nextFloat() * .6f), life, size * u);
            }
        }

        void emitFx(float dt) {
            final float u = brain.u; final State s = brain.state; final float t = brain.t;
            if (brain.stepChanged) {
                brain.stepChanged = false; emitHeart = emitSpark = 0f;
                switch (s) {
                    case CURIOUS: case LOOK_AT_CLOCK: fx.spawn(Fx.QUEST, headX() + 150f * u * brain.face, headY() - 10f * u, 0f, -70f * u, 1.3f, 38f * u); break;
                    case CONFUSED: fx.spawn(Fx.QUEST, headX() + 150f * u * brain.face, headY(), 0f, -60f * u, 1.5f, 40f * u); break;
                    case SURPRISED: case SLIDE: fx.spawn(Fx.EXCL, headX() + 140f * u * brain.face, headY() - 10f * u, 0f, -45f * u, 1.1f, 44f * u); break;
                    case RUN_TO_CHARGER: fx.spawn(Fx.BOLT, headX() - 150f * u * brain.face, headY() + 20f * u, 0f, -60f * u, 1.3f, 40f * u); break;
                    case EXCITED: burst(Fx.SPARK, 3, 170f, 120f, .9f, 22f); break;
                    case GREETING_USER: burst(Fx.HEART, 3, 160f, 120f, 1.6f, 34f); burst(Fx.SPARK, 3, 190f, 90f, 1f, 22f); break;
                    case VERY_HAPPY: burst(Fx.HEART, 2, 150f, 110f, 1.5f, 34f); break;
                    case SHY: fx.spawn(Fx.HEART, headX() + 120f * u, headY() - 20f * u, 20f * u, -60f * u, 1.7f, 26f * u); break;
                    case WAKE_UP: fx.spawn(Fx.PUFF, headX() + 130f * u, headY() + 40f * u, 0f, -20f * u, .7f, 22f * u); break;
                    default: break;
                }
            }
            final int ev = rig.events;
            if ((ev & Rig.EV_LAND) != 0 || (ev & Rig.EV_IMPACT) != 0) {
                final float fy = feetY() - 6f * u;
                for (int i = -1; i <= 1; i += 2) fx.spawn(Fx.PUFF, brain.x + i * 150f * u, fy, i * 110f * u, -14f * u, .55f, 30f * u);
                if ((ev & Rig.EV_IMPACT) != 0) burst(Fx.SPARK, 4, 190f, 70f, .9f, 22f);
            }
            if ((ev & Rig.EV_TAKEOFF) != 0) fx.spawn(Fx.EXCL, headX() + 150f * u, headY() - 20f * u, 0f, -45f * u, 1.2f, 46f * u);
            if ((ev & Rig.EV_SWEAT) != 0) fx.spawn(Fx.SWEAT, headX() + 190f * u, headY() + 70f * u, 30f * u, 20f * u, 1.1f, 22f * u);
            if ((ev & Rig.EV_EXCL) != 0) {
                fx.spawn(Fx.EXCL, headX() - 110f * u, headY() - 30f * u, 0f, -40f * u, 1.3f, 44f * u);
                fx.spawn(Fx.QUEST, headX() + 120f * u, headY() - 30f * u, 0f, -40f * u, 1.3f, 40f * u);
            }
            // continuous emitters
            switch (s) {
                case SLEEP: case REST_WHILE_CHARGING: case SLEEPY: case NOD_OFF:
                    emitZ -= dt; if (emitZ <= 0f && (s != State.SLEEPY || t > .6f)) { emitZ = 1.8f; fx.spawn(Fx.ZZZ, headX() + 160f * u, headY() + 30f * u, 20f * u, -48f * u, 2.6f, 28f * u); } break;
                case HEAD_PAT: if (t > .9f) { emitHeart -= dt; if (emitHeart <= 0f) { emitHeart = .3f; burst(Fx.HEART, 1, 190f, 100f, 1.7f, 32f); } } break;
                case PET: emitHeart -= dt; if (emitHeart <= 0f) { emitHeart = .26f; burst(Fx.HEART, 1, 170f, 110f, 1.7f, 32f); } break;
                case VERY_HAPPY: emitHeart -= dt; if (emitHeart <= 0f) { emitHeart = .45f; burst(Fx.HEART, 1, 170f, 100f, 1.6f, 30f); } break;
                case HAPPY: emitSpark -= dt; if (emitSpark <= 0f) { emitSpark = .9f; burst(Fx.SPARK, 1, 190f, 60f, .9f, 20f); } break;
                case ANGRY: emitMood -= dt; if (emitMood <= 0f) { emitMood = .7f; fx.spawn(Fx.ANGER, headX() + 130f * u * brain.face, headY() - 40f * u, 0f, -15f * u, .9f, 30f * u);
                    fx.spawn(Fx.PUFF, headX(), headY() - 120f * u, (random.nextFloat() - .5f) * 40f * u, -60f * u, .7f, 18f * u); } break;
                case SULK: emitMood -= dt; if (emitMood <= 0f) { emitMood = 2f; fx.spawn(Fx.PUFF, headX() - 120f * u * brain.face, headY() + 40f * u, -brain.face * 50f * u, -12f * u, .6f, 20f * u); } break;
                case SAD: emitMood -= dt; if (emitMood <= 0f) { emitMood = 1.5f; fx.spawn(Fx.TEAR, headX() + (random.nextBoolean() ? -90f : 90f) * u, headY() + 10f * u, 0f, 20f * u, 1.1f, 16f * u); } break;
                case CRY: emitMood -= dt; if (emitMood <= 0f) { emitMood = .22f; final float sd = random.nextBoolean() ? -1f : 1f;
                    fx.spawn(Fx.TEAR, headX() + sd * 95f * u, headY() + 5f * u, sd * 70f * u, -40f * u, 1.1f, 17f * u); } break;
                case DANCE: emitMood -= dt; if (emitMood <= 0f) { emitMood = .45f; fx.spawn(Fx.NOTE, headX() + (random.nextFloat() - .5f) * 220f * u, headY() - 60f * u, (random.nextFloat() - .5f) * 50f * u, -70f * u, 1.6f, 26f * u); } break;
                case LAUGH: emitMood -= dt; if (emitMood <= 0f) { emitMood = .4f; burst(Fx.SPARK, 1, 170f, 80f, .8f, 20f); } break;
                case FLAP: case EXCITED: case BIG_JUMP: emitSpark -= dt; if (emitSpark <= 0f) { emitSpark = .35f; burst(Fx.SPARK, 1, 200f, 70f, .8f, 20f); } break;
                default: break;
            }
            if (brain.gaitAmp > 1.25f) { emitDust -= dt; if (emitDust <= 0f) { emitDust = .22f; fx.spawn(Fx.PUFF, brain.x - brain.face * 140f * u, feetY() - 4f * u, -brain.face * 40f * u, -10f * u, .5f, 20f * u); } }
        }

        // ================================================================== touch
        int hitZone(float fx0, float fy0) {
            final float du = brain.u * brain.depth(), ax = 320f + (fx0 - brain.x) / du, ay = Rig.GROUND + (fy0 - brain.groundY) / du;
            final float dx = Math.abs(ax - 320f);
            if (ay > 20f && ay < 462f && dx < 285f) return Z_HEAD;
            if (ay >= 462f && ay < 800f && dx < 300f) return (dx < 190f && ay > 520f) ? Z_BELLY : Z_BODY;
            return Z_NONE;
        }


        // ================================================================== scenes with Jinbei (v0.11): quarrel, tag, dance
        static final int SC_NONE = 0, SC_QUARREL = 1, SC_TAG = 2, SC_DANCE = 3;
        int testScene;   // CI hook: prefs "test_scene" starts that scene as soon as possible (screenshots)
        int scene, scPhase, scRound, lastBuddyState = -1; float scT, scClock, scCool = 75f, scFxT, cloudOn; boolean scFlag, scMediated;
        final android.graphics.Paint cloudP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), cloudL = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        boolean scenesOk() {
            return buddyOn && !brain.offscreenState() && !brain.sleeping() && !brain.asleepish() && !brain.riding && !buddy.riding()
                    && !brain.charging && ((hour >= 6 && hour < 23) || testScene < 0);
        }
        boolean penFree() { final State s = brain.state; return !petting && !buddyPetting && (s == State.IDLE || s.isCalm() || s == State.WALK) && brain.seqId != Seqs.EAT_MEAL && brain.seqId != Seqs.DRINK_WATER; }
        int towardBuddy() { return buddy.x > brain.x ? 1 : -1; }
        int towardPen() { return brain.x > buddy.x ? 1 : -1; }
        void script(String s) { if (talkOn && buddyOn) talk.play(TalkData.parse(s)); }
        String pickOf(String... a) { return a[random.nextInt(a.length)]; }

        void startScene(int kind, int reason) {
            scene = kind; scPhase = 0; scT = 0f; scRound = 0; scFlag = false; scMediated = false;
            brain.hold = true; buddy.hold = true;
            switch (kind) {
                case SC_QUARREL: {
                    final float side = brain.x < buddy.x ? -1f : 1f;
                    brain.touchX = Math.max(w * .17f, Math.min(w * .83f, buddy.x + side * w * .24f)); brain.react(Seqs.R_APPROACH);
                    buddy.act(Buddy.IDLE, 8f, side > 0 ? 1 : -1);
                    scRound = reason; break; }
                case SC_TAG:
                    script(pickOf("P!:まてまて〜！|J~:つかまらないの〜！", "P:ジンベエ、おにごっこしよ！|J~:こっちなの〜！", "J~:ペンちゃん、おいかけっこ、なの〜！|P!:よーし、まてー！"));
                    tagRound(); break;
                default:
                    brain.face = towardBuddy(); brain.react(Seqs.DANCE_SEQ); buddy.act(Buddy.DANCE_B, 5.4f, towardPen());
                    script(pickOf("P~:♪ るんるん〜|J~:♪ ふりふり、なの〜|P*:ジンベエ、じょうず！", "J~:おどりたい、きぶんなの〜|P~:いっしょにおどろう！♪", "P~:♪ ぺんぺん、ぺんぎん〜|J~:♪ じんじん、じんべえ〜"));
                    break;
            }
        }
        void tagRound() {
            final float target = buddy.x < w * .5f ? buddy.rightLimit() - w * .02f : buddy.leftLimit() + w * .02f;
            buddy.flee(target);
            brain.touchX = Math.max(w * .17f, Math.min(w * .83f, target + (brain.x < target ? -1f : 1f) * w * .16f));
            brain.react(Seqs.R_CHASE); scT = 0f;
        }
        void endScene() {
            if (scene == SC_NONE) return;
            scene = SC_NONE; brain.hold = false; buddy.hold = false; cloudOn = 0f;
            scCool = scClock + 110f + random.nextFloat() * 110f;
        }
        /** The user tapped one of the two during a quarrel: they stop and make up. */
        boolean mediate(float tx, float ty) {
            if (scene != SC_QUARREL || scPhase < 1 || scPhase > 4) return false;
            fx.spawn(Fx.SPARK, tx, ty, 0f, -40f * brain.u, .9f, 30f * brain.u);
            script(pickOf("P:…あっ、ひと…|J:けんか、とめてくれたの…？|P:ごめんね、ジンベエ|J*:ぼくも、ごめんなの…", "J:…ひとが、みてたの…|P:…けんかは、やめよっか|J*:うん…なかなおり、なの"));
            scMediated = true; quarrelPhase(5); return true;
        }

        void quarrelPhase(int p) {
            scPhase = p; scT = 0f; scFlag = false;
            final int tb = towardBuddy(), tp = towardPen();
            switch (p) {
                case 1:
                    brain.face = tb; brain.react(Seqs.R_ANGRY); buddy.act(Buddy.ANGRY_B, 5.8f, tp);
                    if (scRound == 1) script(pickOf("P!:あーっ！ぼくのごはん、たべたでしょ！|J:だって…おなか、すいてたの…|P:ぼくもすいてたもん！|J:むぅ…！",
                            "P!:ごはん、ぜんぶたべちゃったの！？|J:…ちょっとだけ、のつもりだったの…|P:ぜんぶじゃん！"));
                    else script(pickOf("P:ねえ、ぼくのクッション、つかったでしょ！|J:つかってないの…|P:へこんでるもん！|J:…ちょっとだけなの！",
                            "J:ペンちゃん、さっき、ぶつかったの…|P:ぶつかってないよ！|J:ぶつかったの！|P:ぶつかってなーい！",
                            "P:ボールはぼくのばんだよ！|J:ちがうの、ぼくのばんなの！|P:ぼく！|J:ぼくなの！",
                            "J:ペンちゃんの、いびき、うるさかったの…|P!:えっ！ジンベエだって、ぷくぷくうるさいもん！|J:むぅぅ…！"));
                    break;
                case 2:
                    brain.react(Seqs.R_SCUFFLE); buddy.act(Buddy.SCUFFLE_B, 3.4f, tp); break;
                case 3:
                    brain.face = -tb; brain.react(Seqs.R_SULK); buddy.act(Buddy.SULK_B, 7f, -tp);
                    script(pickOf("P:ふんっ！|J:…ふん、なの…", "P:もう、しらない！|J:…ぷいっ、なの", "J:…ぷんぷん、なの|P:ぼくだって、ぷんぷんだもん！"));
                    break;
                case 4:
                    brain.face = tb; brain.react(random.nextInt(3) == 0 ? Seqs.R_CRY : Seqs.R_SAD); buddy.act(Buddy.SAD_B, 3.4f, tp);
                    script(pickOf("P:…ちょっと、いいすぎたかな…|J:…さみしいの…", "J:…ペンちゃん…|P:…ジンベエ…"));
                    break;
                case 5:
                    brain.face = tb; brain.react(Seqs.R_MAKEUP); buddy.act(Buddy.WIGGLE, 3.6f, tp);
                    if (!scMediated) script(pickOf("P:…ごめんね、ジンベエ|J:ぼくも、ごめんなの…|P*:なかなおり！|J*:なかよし、なの〜",
                            "J:ペンちゃん…ごめんなの…|P:ううん、ぼくこそ、ごめん！|J*:えへへ…", "P:ねえ…いっしょに、ボールしよ？|J*:…うん！なかなおりなの！"));
                    final float mx = (brain.x + buddy.x) * .5f, my = headY() - 40f * brain.u;
                    for (int i = 0; i < 5; i++) fx.spawn(Fx.HEART, mx + (random.nextFloat() - .5f) * w * .15f, my, (random.nextFloat() - .5f) * 60f * brain.u, -70f * brain.u, 1.8f, 30f * brain.u);
                    care.cheer(.12f); break;
                default: break;
            }
        }

        void updateScenes(float dt) {
            scClock += dt;
            cloudOn += ((scene == SC_QUARREL && scPhase == 2 ? 1f : 0f) - cloudOn) * Math.min(1f, dt * 7f);
            if (!buddyOn) { endScene(); return; }
            if (testScene > 0 && scene == SC_NONE && !brain.offscreenState() && !brain.riding && !buddy.riding() && w > 0 && scClock > 1.5f) { final int k = testScene; testScene = -1; startScene(k, 0); return; }
            // things Jinbei does that the penguin reacts to
            final int bs = buddy.state;
            if (bs != lastBuddyState) {
                if (scene == SC_NONE && (bs == Buddy.ROLL || bs == Buddy.CHASE_TAIL) && scenesOk() && penFree() && random.nextInt(2) == 0) { brain.face = towardBuddy(); brain.react(Seqs.R_LAUGH); }
                lastBuddyState = bs;
            }
            if (buddy.ateLast) { buddy.ateLast = false; if (scene == SC_NONE && care.pHungry() && scenesOk() && random.nextInt(3) != 0) { startScene(SC_QUARREL, 1); return; } }
            if (scene == SC_NONE) {
                if (scClock > scCool && scenesOk() && penFree() && (!buddy.asleep || random.nextInt(4) == 0)) {
                    final float dance = care.pMood > .7f ? 40f : 18f, tag = 34f, quarrel = care.pMood > .8f ? 18f : 30f;
                    float r = random.nextFloat() * (dance + tag + quarrel);
                    startScene(r < dance ? SC_DANCE : r < dance + tag ? SC_TAG : SC_QUARREL, 0);
                } else if (scClock > scCool) scCool = scClock + 15f;
                return;
            }
            if (!scenesOk()) { endScene(); return; }
            scT += dt;
            switch (scene) {
                case SC_QUARREL:
                    switch (scPhase) {
                        case 0: if ((scT > 2f && !brain.state.isMove()) || scT > 6.5f) quarrelPhase(1); break;
                        case 1: if (!scFlag && scT > 2.8f) { scFlag = true; brain.react(Seqs.R_ANGRY); } if (scT > 5.6f) quarrelPhase(2); break;
                        case 2: {
                            scFxT -= dt;
                            if (scFxT <= 0f) {
                                scFxT = .18f; final float cx = (brain.x + buddy.x) * .5f, cy = brain.groundY - 300f * brain.u, rr = w * .14f;
                                final float a = random.nextFloat() * 6.2832f;
                                final int t = random.nextInt(3);
                                fx.spawn(t == 0 ? Fx.ANGER : t == 1 ? Fx.SPARK : Fx.PUFF, cx + (float) Math.cos(a) * rr, cy + (float) Math.sin(a) * rr * .6f,
                                        (float) Math.cos(a) * 90f * brain.u, (float) Math.sin(a) * 60f * brain.u - 30f * brain.u, .6f, 26f * brain.u);
                            }
                            if (scT > 3.4f) quarrelPhase(3); break; }
                        case 3: if (scT > 7f) quarrelPhase(4); break;
                        case 4: if (scT > 3.4f) quarrelPhase(5); break;
                        default: if (scT > 4.6f) endScene(); break;
                    }
                    break;
                case SC_TAG:
                    if (scRound < 3) { if (scT > 2.8f) { scRound++; if (scRound < 3) tagRound(); else { scT = 0f; brain.face = towardBuddy(); brain.react(Seqs.R_LAUGH); buddy.act(Buddy.LAUGH_B, 2.8f, towardPen());
                        script(pickOf("P:あはは！ジンベエ、はやい〜！|J:たのしかったの〜", "J:つかまっちゃったの〜|P*:タッチ！あはは！")); } } }
                    else if (scT > 3.3f) endScene();
                    break;
                default:
                    if (scT > 6.2f) { care.cheer(.08f); endScene(); }
                    break;
            }
        }

        /** The dust cloud of a cartoon scuffle (drawn over both pets). */
        void drawCloud(Canvas c) {
            if (cloudOn < .02f) return;
            final float cx = (brain.x + buddy.x) * .5f, cy = brain.groundY - 260f * brain.u, R = Math.max(w * .17f, Math.abs(brain.x - buddy.x) * .62f) * (.8f + .2f * cloudOn);
            cloudP.setStyle(android.graphics.Paint.Style.FILL); cloudL.setStyle(android.graphics.Paint.Style.STROKE); cloudL.setStrokeWidth(Math.max(3f, w * .005f));
            cloudL.setColor(0xFFB9B2A8); cloudL.setAlpha((int) (255 * cloudOn));
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 9; i++) {
                    final float ang = i * .698f + scClock * .8f, wob = .8f + .18f * (float) Math.sin(scClock * 11f + i * 1.7f);
                    final float px = cx + (float) Math.cos(ang) * R * .62f, py = cy + (float) Math.sin(ang) * R * .34f, pr = R * .36f * wob;
                    if (pass == 0) c.drawCircle(px, py, pr, cloudL);
                    else { cloudP.setColor(i % 2 == 0 ? 0xFFF7F3EC : 0xFFEDE6DB); cloudP.setAlpha((int) (250 * cloudOn)); c.drawCircle(px, py, pr, cloudP); }
                }
            }
            cloudP.setColor(0xFFF7F3EC); cloudP.setAlpha((int) (250 * cloudOn)); c.drawCircle(cx, cy, R * .5f, cloudP);
            // a wing and a tail fin poke out now and then
            final int peek = ((int) (scClock * 3f)) % 4;
            if (peek == 1) { cloudP.setColor(0xFF3A3D45); c.drawCircle(cx - R * .7f, cy - R * .1f, R * .1f, cloudP); }
            if (peek == 3) { cloudP.setColor(0xFF6FA7C9); c.drawCircle(cx + R * .7f, cy + R * .05f, R * .11f, cloudP); }
        }

        // ================================================================== care, decor, talk
        long buildCtx() {
            final Calendar cal = Calendar.getInstance(); final int hr = cal.get(Calendar.HOUR_OF_DAY), mo = cal.get(Calendar.MONTH), dom = cal.get(Calendar.DAY_OF_MONTH), dow = cal.get(Calendar.DAY_OF_WEEK);
            long b = hr >= 5 && hr < 10 ? TalkData.MORNING : hr >= 10 && hr < 16 ? TalkData.DAY : hr >= 16 && hr < 19 ? TalkData.EVE : hr >= 19 && hr < 23 ? TalkData.NIGHT : TalkData.LATE;
            final int wx = room.weather(), sc = room.sceneId();
            if (wx == 1) b |= TalkData.RAIN; if (wx == 2) b |= TalkData.SNOW; if (wx == 3) b |= TalkData.STARS | TalkData.MOON;
            if (sc == Room.SUNNY || sc == Room.MORNING || sc == Room.NORMAL_ROOM) b |= TalkData.SUNNY;
            if (sc == Room.CLOUDY) b |= TalkData.CLOUDY;
            if (room.night()) b |= TalkData.MOON;
            if (brain.charging) b |= TalkData.CHARGING;
            if (brain.battery <= 20 && !brain.charging) b |= TalkData.LOWBAT;
            if (brain.battery >= 95) b |= TalkData.FULLBAT;
            final boolean ph = care.pHungry(), bh = care.bHungry(), th = care.pThirsty() || care.bThirsty();
            if (care.pBored()) b |= TalkData.BORED;
            if (ph) b |= TalkData.P_HUNGRY; if (bh) b |= TalkData.B_HUNGRY; if (th) b |= TalkData.THIRSTY;
            if (((ph || bh) && care.foodEmpty()) || (th && care.waterEmpty())) b |= TalkData.EMPTY;
            if (care.recentlyFed(System.currentTimeMillis())) b |= TalkData.FED;
            if (dow == Calendar.MONDAY) b |= TalkData.MON; if (dow == Calendar.FRIDAY) b |= TalkData.FRI; if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) b |= TalkData.WEEKEND;
            b |= (mo >= 2 && mo <= 4) ? TalkData.SPRING : (mo >= 5 && mo <= 7) ? TalkData.SUMMER : (mo >= 8 && mo <= 10) ? TalkData.AUTUMN : TalkData.WINTER;
            if (mo == Calendar.OCTOBER && dom >= 20) b |= TalkData.HALLOWEEN;
            if (mo == Calendar.DECEMBER && dom >= 22 && dom <= 26) b |= TalkData.XMAS;
            if (mo == Calendar.JANUARY && dom <= 3) b |= TalkData.NEWYEAR;
            final int d = care.days(System.currentTimeMillis()); if (d >= 7) b |= TalkData.D7; if (d >= 30) b |= TalkData.D30; if (d >= 100) b |= TalkData.D100;
            if (brain.sleeping()) b |= TalkData.SLEEP_P;
            if (buddyOn && buddy.asleep && buddy.state == Buddy.SNOOZE) b |= TalkData.SLEEP_J;
            if (buddyOn && buddy.riding()) b |= TalkData.RIDING;
            return b;
        }

        void updateTalk(float dt) {
            if (!talkOn) { if (talk.active()) talk.abort(); return; }
            if (!buddyOn) { talk.update(dt, 0L, 0, false, 1f); return; }      // no chats without Jinbei, only the penguin's own reactions
            final long now = SystemClock.uptimeMillis();
            if (now - ctxMs > 1000L) { ctxMs = now; ctxBits = buildCtx(); }
            final boolean riding = buddy.riding();
            if (riding != wasRiding) { if (!riding) { talk.sticky(TalkData.RIDE_DONE, 60f); talk.kick(2.5f); } else talk.kick(1.8f); wasRiding = riding; }
            final boolean pOk = !brain.offscreenState() && (brain.sleeping() || brain.state == State.IDLE || brain.state.isCalm() || brain.state == State.WALK || brain.state == State.SIT || brain.riding);
            final int bs = buddy.state;
            final boolean bOk = buddy.asleep || !(bs == Buddy.STIR || bs == Buddy.YAWN || bs == Buddy.DROWSY || bs == Buddy.SURPRISE || bs == Buddy.ROLL || bs == Buddy.TICKLE || bs == Buddy.PETTED || bs == Buddy.CHASE_TAIL);
            final float scale = (brain.amount() == 0 ? 1.7f : brain.amount() == 2 ? .7f : 1f) * (brain.powerSave() ? 1.4f : 1f);
            final int sp = decor.newItem;
            talk.update(dt, ctxBits, sp, pOk && bOk, scale);
            if (sp > 0 && talk.speaking()) decor.markSeen(m -> prefs.edit().putInt("decor_seen", m).apply());
            if (talk.lineStarted) onTalkLine(talk.lineWho, talk.lineEmo);
        }

        void onTalkLine(int who, int emo) {
            final float u = brain.u;
            final float hx = who == 0 ? headX() : buddy.headX(), hy = who == 0 ? headY() : buddy.headY();
            final float up = 50f * u;
            switch (emo) {
                case '*': fx.spawn(Fx.HEART, hx, hy - up, 12f * u, -60f * u, 1.5f, 26f * u); break;
                case '!': fx.spawn(Fx.EXCL, hx + 20f * u, hy - up, 0f, -45f * u, 1.1f, 38f * u); break;
                case '?': fx.spawn(Fx.QUEST, hx + 20f * u, hy - up, 0f, -45f * u, 1.3f, 34f * u); break;
                case '~': fx.spawn(Fx.SPARK, hx, hy - up, 10f * u, -50f * u, .9f, 24f * u); break;
                case 'z': fx.spawn(Fx.ZZZ, hx + 30f * u, hy - up * .5f, 14f * u, -40f * u, 1.8f, 24f * u); break;
                default: break;
            }
            // the listener looks at the speaker
            if (who == 0) buddy.gazeAt(headX(), headY());
            else if (!brain.offscreenState() && !brain.asleepish()) {
                brain.gazeX = Math.max(-1f, Math.min(1f, (buddy.headX() - headX()) / (w * .32f)));
                brain.gazeY = Math.max(-1f, Math.min(1f, (buddy.headY() - headY()) / (h * .22f)));
                brain.gazeUntil = brain.time + 1.8f;
            }
        }

        /** The user tapped a bowl: fill it and let the pets react. */
        void feed(int kind, float tx, float ty) {
            endScene();
            final long now = System.currentTimeMillis();
            final boolean helpful = kind == 1 ? care.food < .5f : care.water < .5f;
            final boolean full = kind == 1 ? care.pStuffed() : care.pQuenched();
            final boolean awake = !brain.offscreenState() && !brain.sleeping() && !brain.asleepish() && !brain.riding;
            final float u = brain.u;
            if (!helpful) {                                    // the bowl is still full: nothing to add
                fx.spawn(Fx.PUFF, tx, ty - 10f * u, 0f, -30f * u, .5f, 20f * u);
                if (full && awake && talkOn) { brain.face = tx > brain.x ? 1 : -1; brain.react(Seqs.R_REFUSE); talk.say(0, '\0', kind == 1 ? "まだ、ごはんのこってるよ〜" : "おみず、まだあるよ〜"); }
                return;
            }
            if (kind == 1) care.fillFood(now); else care.fillWater(now);
            fx.spawn(Fx.SPARK, tx, ty - 30f * u, 0f, -50f * u, .9f, 26f * u);
            if (full) {                                        // filled, but the penguin is not hungry: says no thanks (Jinbei may still come)
                if (awake) { brain.face = tx > brain.x ? 1 : -1; brain.react(Seqs.R_REFUSE); if (talkOn) talk.say(0, '\0', kind == 1 ? "もう、おなかいっぱい…あとでたべるね" : "いまは、のどかわいてないや"); talk.sticky(TalkData.FULL, 90f); }
                if (buddyOn) buddy.onFilled(kind);
                return;
            }
            fx.spawn(Fx.HEART, tx + 20f * u, ty - 30f * u, 14f * u, -60f * u, 1.4f, 22f * u);
            care.cheer(.1f);
            talk.sticky(TalkData.FED, 100f); talk.kick(3f);
            if (awake) brain.react(kind == 1 ? Seqs.R_FED : Seqs.R_WATERED);
            if (buddyOn) buddy.onFilled(kind);
        }

        /** The user tapped the ball: it rolls away and the penguin chases it (the best cure for a bored penguin). */
        void play(float tx, float ty) {
            endScene();
            decor.flickBall();
            talk.sticky(TalkData.BALL, 60f); talk.kick(6f);
            if (brain.offscreenState() || brain.sleeping() || brain.asleepish() || brain.riding) return;
            final boolean wasBored = care.pBored();
            care.played(System.currentTimeMillis());
            fx.spawn(Fx.SPARK, tx, ty - 30f * brain.u, 0f, -50f * brain.u, .9f, 26f * brain.u);
            if (wasBored) fx.spawn(Fx.HEART, headX(), headY() - 60f * brain.u, 12f * brain.u, -60f * brain.u, 1.5f, 26f * brain.u);
            brain.react(Seqs.R_PLAY);
        }

        void handleBuddyRequest() {
            final int r = buddy.reqPen; if (r == 0) return; buddy.reqPen = 0;
            if (brain.offscreenState()) return;
            switch (r) {
                case Buddy.PEN_GREET: if (!brain.sleeping() && !brain.riding) { brain.face = buddy.x > brain.x ? 1 : -1; brain.react(Seqs.R_GREET); } break;
                case Buddy.PEN_JUMP: if (!brain.sleeping() && !brain.riding) { brain.face = buddy.x > brain.x ? 1 : -1; brain.react(Seqs.R_TAP_JUMP); } break;
                case Buddy.PEN_FLAP: if (!brain.sleeping()) brain.react(Seqs.R_TAP_FLAP); break;
                case Buddy.PEN_GOTO: if (!brain.sleeping()) { brain.touchX = buddy.reqX; brain.react(Seqs.R_TOUCH); } break;
                case Buddy.PEN_MOUNT: brain.face = buddy.face; brain.startRide(); break;
                case Buddy.PEN_DISMOUNT: if (brain.riding) { brain.riding = false; brain.react(Seqs.R_TAP_JUMP); } break;
                default: break;
            }
        }

        void gaze(float fx0, float fy0) {
            if (buddyOn) buddy.gazeAt(fx0, fy0);
            if (brain.offscreenState()) return;
            brain.gazeX = Math.max(-1f, Math.min(1f, (fx0 - headX()) / (w * .32f)));
            brain.gazeY = Math.max(-1f, Math.min(1f, (fy0 - headY()) / (h * .22f)));
            brain.gazeUntil = brain.time + 1.6f;
        }

        @Override public void onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            if (!visible || w <= 0) return;
            final float ex = e.getX(), ey = e.getY();
            lastTouchMs = SystemClock.uptimeMillis();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = ex; downY = lastY = ey; downT = lastTouchMs; pathLen = 0f; petting = false; petDir = 0f;
                    zone = brain.offscreenState() ? Z_NONE : hitZone(ex, ey);
                    bzone = buddyOn ? buddy.zoneAt(ex, ey) : Buddy.Z_NONE; buddyPetting = false;
                    if (bzone != Buddy.Z_NONE && zone != Z_NONE && !buddy.inFront(brain.sleeping())) bzone = Buddy.Z_NONE;   // the penguin is in front of it
                    if (bzone != Buddy.Z_NONE) zone = Z_NONE;
                    gaze(ex, ey);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    final float dx = ex - lastX, dy = ey - lastY;
                    pathLen += (float) Math.hypot(dx, dy);
                    if (Math.abs(dx) > 1f) petDir += (Math.signum(dx) - petDir) * .3f;
                    lastX = ex; lastY = ey;
                    gaze(ex, ey);
                    if (enabled("tap") && zone == Z_HEAD && !brain.offscreenState() && pathLen > 26f * density) {
                        if (!petting) { petting = true; brain.petting = true; brain.react(Seqs.R_PET); care.stroked(); care.cheer(.15f); talk.sticky(TalkData.PETTED, 90f); talk.kick(8f); }
                        brain.lastPetAt = brain.time; brain.in.petDir = petDir;
                    }
                    if (enabled("tap") && bzone != Buddy.Z_NONE && pathLen > 26f * density) { buddyPetting = true; buddy.stroke(petDir); care.stroked(); talk.sticky(TalkData.PETTED, 90f); talk.kick(8f); }
                    break; }
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    if (buddyPetting) { buddyPetting = false; }
                    else if (petting) { petting = false; brain.petting = false; brain.lastPetAt = brain.time; }
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP && pathLen < 22f * density && lastTouchMs - downT < 650L) onTap(ex, ey);
                    break;
                default: break;
            }
        }

        /** Fallback for launchers that deliver wallpaper taps as commands instead of touch events. */
        @Override public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action) && visible && SystemClock.uptimeMillis() - lastTouchMs > 600L) {
                lastTouchMs = SystemClock.uptimeMillis(); onTap(x, y);
            }
            return null;
        }

        void onTap(float tx, float ty) {
            if (!enabled("tap")) return;
            // bowls and the ball first: the pets often stand in front of them and would swallow the tap
            final int bowl = decor.ballHit(tx, ty, 1.5f) ? 0 : decor.bowlHit(tx, ty);     // a tap right on the ball beats the bowl behind it
            if (bowl != 0) { taps = 0; lastTapTarget = 2; gaze(tx, ty); feed(bowl, tx, ty); return; }
            if (decor.ballHit(tx, ty)) { taps = 0; lastTapTarget = 2; gaze(tx, ty); play(tx, ty); return; }
            if (brain.offscreenState()) return;
            if (scene == SC_QUARREL && (hitZone(tx, ty) != Z_NONE || (buddyOn && buddy.zoneAt(tx, ty) != Buddy.Z_NONE)) && mediate(tx, ty)) return;
            if (scene != SC_NONE && scene != SC_QUARREL) endScene();
            final long now = SystemClock.uptimeMillis();
            taps = (now - lastTapMs < 1000L) ? taps + 1 : 1; lastTapMs = now;
            final int z0 = hitZone(tx, ty);
            int bz = buddyOn ? buddy.zoneAt(tx, ty) : Buddy.Z_NONE;
            if (bz != Buddy.Z_NONE && z0 != Z_NONE && !buddy.inFront(brain.sleeping())) bz = Buddy.Z_NONE;
            final int z = bz != Buddy.Z_NONE ? Z_NONE : z0;
            gaze(tx, ty);
            if (bz != Buddy.Z_NONE) {                          // the whale shark was tapped
                if (lastTapTarget != 1) taps = 1; lastTapTarget = 1;
                buddy.tap(bz, taps); if (taps >= 4) taps = 0; return;
            }
            if (lastTapTarget != 0) taps = 1; lastTapTarget = 0;
            brain.lastTouchAt = brain.time;
            if (buddyOn) buddy.userTouchedPenguin();
            if (z == Z_NONE) {
                if (brain.sleeping() || brain.asleepish()) return;          // do not wake it by tapping the wall
                brain.touchX = tx; brain.react(Seqs.R_TOUCH); taps = 0; return;
            }
            if (brain.sleeping() || brain.asleepish()) { brain.react(Seqs.R_WAKE); taps = 0; return; }
            if (z == Z_HEAD && taps >= 2) {            // tap, tap, tap on the head = stroking it (launchers often deliver taps only, no swipes)
                brain.petUntil = brain.time + 1.6f; brain.in.petDir = (taps % 2 == 0) ? 1f : -1f;
                if (brain.state != State.PET) { brain.react(Seqs.R_PET); care.cheer(.1f); }
                return;
            }
            if (taps >= 3) {
                if (brain.time - lastMultiAt < 25f) { brain.react(Seqs.R_ANGRY); care.cheer(-.08f); if (talkOn) talk.say(0, '!', "もう〜！つんつんしないで〜！/しつこいよ〜！ぷんぷん！"); }
                else brain.react(Seqs.R_MULTI);
                lastMultiAt = brain.time; taps = 0; return;
            }
            if (z == Z_HEAD) { brain.react(Seqs.R_HEAD_PAT); care.cheer(.05f); }
            else if (z == Z_BELLY) brain.react(Seqs.R_BELLY);
            else { final int r = random.nextInt(4); brain.react(r == 0 ? Seqs.R_TAP_TILT : r == 1 ? Seqs.R_TAP_FLAP : r == 2 ? Seqs.R_TAP_JUMP : Seqs.R_GREET); }
        }
    }
}
