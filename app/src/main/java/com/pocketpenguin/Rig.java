package com.pocketpenguin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.util.Random;

/**
 * Part-based penguin animator + renderer.
 *
 * Every state only describes TARGET values per part (body / head / wings / feet / tail / eyes / lids / brows / mouth / cheeks).
 * Each part then chases its target through its own Spring with a different stiffness, so the head trails the body,
 * the wings trail the head (secondary motion), landings overshoot (squash & stretch) and direction changes carry inertia.
 * All values are screen-space (positive rotation = clockwise); "face" only changes gait direction, tail side and head turn.
 */
final class Rig {
    // ---- anchors in the 640x800 art space (see tools/make_assets.py)
    static final float GROUND = 778f, SHOULDER_Y = 462f, SHOULDER_LX = 172f, SHOULDER_RX = 468f, NECK_Y = 470f;
    static final float JUMP_PREP = .26f, JUMP_AIR = .56f, BIG_PREP = .32f, BIG_AIR = .80f, FALL_IMPACT = .28f;

    static final int EV_LAND = 1, EV_IMPACT = 2, EV_TAKEOFF = 4, EV_SWEAT = 8, EV_EXCL = 16, EV_SNEEZE = 32;

    /** Per-frame input from the behaviour layer. */
    static final class In {
        State s = State.IDLE;
        float t, dur = 1f, rnd = 1f, gaitPh, gaitAmp, time;
        float gazeOn, gx, gy;            // finger following (-1..1)
        float clockDx = 1f, windowDx = -1f, petDir;
        int face = 1, weather;           // weather: 0 none, 1 rain, 2 snow, 3 stars
        boolean micro, gazeOk;
    }

    /** Pose targets for one frame. */
    static final class Tg {
        float bx, by, rot, sx, sy, hx, hy, hrot, wl, wr, fxl, fyl, fxr, fyr, tail;
        float lookX, lookY, turn, lid, mouth, cheek, browL, browR, eyeS, footS;
        Expr expr;
        void reset() {
            bx = by = rot = hx = hy = hrot = wl = wr = fxl = fyl = fxr = fyr = tail = 0f;
            lookX = lookY = turn = lid = mouth = browL = browR = 0f;
            sx = sy = eyeS = footS = 1f; cheek = -1f; expr = Expr.NORMAL;
        }
    }

    // ---- springs (frequency Hz, damping ratio); lower freq + lower damping = lag + bounce
    final Spring bx = new Spring(6f, .7f), by = new Spring(8f, .55f), rot = new Spring(5f, .5f);
    final Spring sx = new Spring(9f, .4f, 1f), sy = new Spring(9f, .4f, 1f);
    final Spring hx = new Spring(5f, .6f), hy = new Spring(6f, .5f), hrot = new Spring(4.2f, .45f);
    final Spring wl = new Spring(6f, .38f), wr = new Spring(6f, .38f);
    final Spring fxl = new Spring(12f, .8f), fyl = new Spring(12f, .8f), fxr = new Spring(12f, .8f), fyr = new Spring(12f, .8f);
    final Spring tail = new Spring(5f, .35f), lookX = new Spring(14f, .75f), lookY = new Spring(14f, .75f), turn = new Spring(4f, .8f);
    final Spring lid = new Spring(20f, 1f), mouth = new Spring(12f, .8f), cheek = new Spring(3f, 1f, .7f);
    final Spring browL = new Spring(10f, .8f), browR = new Spring(10f, .8f);
    final Spring eyeS = new Spring(10f, .6f, 1f), footS = new Spring(8f, .7f, 1f);
    Expr expr = Expr.NORMAL;
    int events;
    boolean boots;                   // rainy day: yellow rain boots over the feet
    int costume;                     // seasonal: 0 none, 1 muffler (winter), 2 straw hat (summer), 3 pumpkin hat (Halloween), 4 Santa hat (Christmas)
    static final int C_NONE = 0, C_MUFFLER = 1, C_STRAW = 2, C_PUMPKIN = 3, C_SANTA = 4;
    private final Path hatPath = new Path();

    private final Tg T = new Tg();
    private final Random rnd = new Random();
    private State prevS = State.IDLE; private float prevT;
    // blink + micro animation
    private float blinkClock = 2f, blinkT = -1f;
    private float mClock = 1.5f, mT, mDur = 1f; private int mType = -1, mDir = 1;

    // ---- paints / scratch (allocated once)
    private final Paint bm = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint eyeP = new Paint(Paint.ANTI_ALIAS_FLAG), hlP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG), cheekP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mouthP = new Paint(Paint.ANTI_ALIAS_FLAG), tongueP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowP = new Paint(Paint.ANTI_ALIAS_FLAG), starP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path chev = new Path();

    Rig() {
        eyeP.setColor(0xFF282A30); hlP.setColor(0xFFFFFFFF);
        strokeP.setStyle(Paint.Style.STROKE); strokeP.setStrokeCap(Paint.Cap.ROUND); strokeP.setStrokeJoin(Paint.Join.ROUND);
        strokeP.setColor(0xFF282A30);
        cheekP.setColor(0xFFF59AA0); mouthP.setColor(0xFF6B2B30); tongueP.setColor(0xFFF08A95);
        shadowP.setColor(0xFF000000); starP.setColor(0xFFFFFFFF);
    }

    /** Give the body an inertial kick (direction change / bump). */
    void kick(int dir) { hrot.v -= dir * 90f; rot.v += dir * 30f; sy.v -= .9f; }

    // =====================================================================================================
    static float cl(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
    static float sm(float t) { t = cl(t, 0f, 1f); return t * t * (3f - 2f * t); }
    static float seg(float t, float a, float b) { return sm((t - a) / (b - a)); }
    static float pulse(float t, float a, float b, float c, float d) { return seg(t, a, b) * (1f - seg(t, c, d)); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float sin(float v) { return (float) Math.sin(v); }
    static float cos(float v) { return (float) Math.cos(v); }
    static float abs(float v) { return v < 0 ? -v : v; }
    static float blinkWave(float t) { return t < .08f ? t / .08f : (t < .17f ? 1f - (t - .08f) / .09f : 0f); }

    private boolean cross(State s, float t, float a) { return prevS == s && prevT < a && t >= a; }
    private void wings(float v) { T.wl = v; T.wr = v; }
    private void sit(float k) {
        T.sy = lerp(1f, .84f, k); T.sx = lerp(1f, 1.09f, k); T.footS = lerp(1f, 1.12f, k);
        T.fxl = -16f * k; T.fxr = 16f * k; T.wl = T.wr = -3f * k;
    }
    private void look(float x, float y, float e) { T.lookX = lerp(T.lookX, x, e); T.lookY = lerp(T.lookY, y, e); }

    private void jump(In in, float prep, float air, float h, float wingMax, boolean yaw) {
        final float t = in.t;
        if (t < prep) {
            float k = seg(t, 0f, prep);
            T.sy = 1f - .2f * k; T.sx = 1f + .13f * k; wings(-12f * k);
            T.expr = Expr.CURIOUS; T.eyeS = 1.05f;
        } else if (t < prep + air) {
            float s = (t - prep) / air, hh = 4f * s * (1f - s);
            T.by = -h * hh;
            T.sy = 1f + .13f * (1f - s) + .03f * s; T.sx = 1f - (T.sy - 1f) * .7f;
            wings(wingMax * seg(s, 0f, .45f));
            T.expr = Expr.EXCITED; T.mouth = .45f; T.lookY = -.3f;
            if (yaw) { T.turn = 1.3f * sin(6.2831853f * s); T.rot = 10f * sin(6.2831853f * s); }
            if (cross(in.s, t, prep + air)) events |= EV_LAND;
        } else {
            float u = t - prep - air, k = pulse(u, 0f, .04f, .1f, .3f);
            T.sy = 1f - .25f * k; T.sx = 1f + .18f * k;
            wings(wingMax * .45f * (1f - seg(u, 0f, .25f)));
            T.expr = Expr.HAPPY; T.cheek = 1.1f;
        }
    }

    // =====================================================================================================
    //  pose targets for one frame
    private void eval(In in) {
        final float t = in.t, dur = in.dur, rn = in.rnd, ti = in.time;
        final int d = in.face;
        float e;
        T.reset();
        T.turn = d * .15f; T.lookX = d * .2f;

        // ---- gait layer: waddle (weight shift -> lean -> late head -> alternating feet -> belly bounce)
        final float g = in.gaitAmp;
        if (g > .01f) {
            final float ph = in.gaitPh, sp = sin(ph), cp = cos(ph);
            T.bx += sp * 10f * g;
            T.rot += sp * 7f * g + d * (1.5f + 4f * Math.max(0f, g - 1.2f)) * Math.min(g, 1f);
            T.by += -abs(cp) * 9f * g;
            T.hrot += sin(ph - .65f) * 5.5f * g;
            T.hy += -abs(sin(ph - .5f)) * 3f * g;
            T.hx += sin(ph - .5f) * 3f * g;
            T.sy += .028f * g * cos(2f * ph + .3f); T.sx -= .02f * g * cos(2f * ph + .3f);
            T.wl += g * (8f + 10f * sin(ph - 1f)) + 20f * Math.max(0f, g - 1.3f);
            T.wr += g * (8f - 10f * sin(ph - 1f)) + 20f * Math.max(0f, g - 1.3f);
            T.fxl += -cp * 15f * g * d; T.fyl += -Math.max(0f, sp) * 18f * g;
            T.fxr += cp * 15f * g * d;  T.fyr += -Math.max(0f, -sp) * 18f * g;
            T.tail += sin(2f * ph) * 14f * g;
            T.turn = d * .55f * Math.min(g, 1.2f); T.lookX = d * .6f;
        }

        switch (in.s) {
            case IDLE: case OFF_SCREEN: break;
            case TURN: {          // swing head and body round to the new direction, with a little wing balance
                final float s = seg(t, 0f, dur);
                T.turn = d * lerp(-.9f, .9f, s); T.lookX = d * lerp(-.6f, .8f, s);
                T.hrot += d * lerp(-8f, 6f, s) * (1f - s * .5f); T.rot += d * lerp(-5f, 3f, s);
                wings(18f * sin(3.1415927f * s)); T.sy -= .02f * sin(3.1415927f * s); T.fxl += 6f * sin(3.1415927f * s); T.fxr -= 6f * sin(3.1415927f * s);
                break; }
            case BREATHE:
                T.sy += .03f * sin(t * 4f); wings(8f + 6f * sin(t * 4f - .5f)); break;
            case BLINK:
                T.lid = blinkWave(t); break;
            case LOOK_LEFT:
                e = pulse(t, 0f, .18f, dur - .2f, dur);
                look(-1f, 0f, e); T.turn = lerp(T.turn, -.8f, e); T.hrot -= 3f * e; T.hx -= 5f * e; break;
            case LOOK_RIGHT:
                e = pulse(t, 0f, .18f, dur - .2f, dur);
                look(1f, 0f, e); T.turn = lerp(T.turn, .8f, e); T.hrot += 3f * e; T.hx += 5f * e; break;
            case LOOK_UP:
                e = pulse(t, 0f, .2f, dur - .2f, dur);
                look(0f, -1f, e); T.hy -= 6f * e; T.browL = T.browR = .4f * e; T.turn *= 1f - e; break;
            case LOOK_USER:
                e = pulse(t, 0f, .15f, dur - .1f, dur);
                look(0f, .15f, e); T.turn *= 1f - e; T.eyeS = 1f + .06f * e; T.cheek = .9f;
                T.sy += .015f * e; break;
            case TILT_HEAD:
                e = pulse(t, 0f, .25f, dur - .3f, dur);
                T.hrot += 15f * rn * e; T.hx += 5f * rn * e; T.rot += 2f * rn * e; T.expr = Expr.CURIOUS;
                T.browL = (rn > 0 ? .8f : .2f) * e; T.browR = (rn > 0 ? .2f : .8f) * e; look(-rn * .3f, -.1f, e); break;
            case START_WALK:
                e = seg(t, 0f, .22f);
                T.sy -= .07f * (1f - seg(t, .1f, .28f)) * e; T.sx += .05f * (1f - seg(t, .1f, .28f)) * e;
                T.rot += -d * 3f * (1f - seg(t, .1f, .3f)) * e; break;
            case STOP_WALK:
                T.rot += -d * 4f * (1f - seg(t, 0f, .4f)); T.sy -= .035f * pulse(t, 0f, .08f, .12f, .3f);
                T.sx += .03f * pulse(t, 0f, .08f, .12f, .3f); break;
            case WALK: case WALK_FAST: case WALK_TO_BED: case EXIT_SCREEN: break;
            case ENTER_SCREEN:
                T.expr = Expr.CURIOUS; T.browL = T.browR = .3f; break;
            case FOLLOW_TOUCH:
                T.expr = Expr.HAPPY; T.cheek = 1f; break;
            case RUN:
                T.expr = Expr.EXCITED; T.mouth = .3f; break;
            case RUN_TO_CHARGER:
                T.expr = Expr.EXCITED; T.mouth = .3f; T.eyeS = 1.1f; break;
            case SIT:
                sit(seg(t, 0f, .35f)); T.hy += 4f * seg(t, 0f, .35f);
                T.lid = .12f * seg(t, 2f, 5f); break;
            case YAWN: {
                float m = pulse(t, .3f, .6f, dur - .5f, dur - .15f);
                T.sy += .05f * seg(t, 0f, .4f) * (1f - seg(t, dur - .4f, dur)) + .03f * m;
                T.mouth = m; T.hy -= 8f * m; T.hrot += -rn * 4f * m; wings(26f * m);
                T.expr = m > .15f ? Expr.HAPPY : Expr.SLEEPY; T.cheek = .9f;
                T.hrot += 5f * sin(t * 26f) * seg(t, dur - .45f, dur - .3f) * (1f - seg(t, dur - .3f, dur));
                break; }
            case SLEEP:
                sit(1f); T.expr = Expr.SLEEPING; T.hy += 8f; T.hrot += rn * (4f + 3f * sin(ti * .7f));
                T.sy += .022f * sin(ti * 1.6f); T.cheek = .85f; break;
            case SLEEPY:
                sit(.3f); T.expr = Expr.SLEEPY; T.lid = .5f + .12f * sin(t * 1.4f) + .2f * seg(t, 0f, dur);
                T.hy += 3f + 3f * sin(t * 1.2f); break;
            case WAKE_UP:
                T.lid = .7f * (1f - seg(t, 0f, .7f)); T.expr = t < .6f ? Expr.SLEEPY : Expr.NORMAL;
                sit(1f - seg(t, 0f, .5f));
                e = pulse(t, .3f, .55f, .7f, .95f);
                T.sy += .06f * e; wings(30f * e); T.hrot += 6f * sin(t * 20f) * e; break;
            case STRETCH: {
                float a = seg(t, .15f, .75f), b = seg(t, dur - .8f, dur - .25f), k = a * (1f - b);
                T.sy += .11f * k - .06f * pulse(t, 0f, .1f, .14f, .24f); T.sx -= .05f * k;
                wings(15f + 88f * k); T.hy -= 10f * k; T.mouth = .5f * k;
                T.expr = k > .3f ? Expr.HAPPY : Expr.NORMAL; break; }
            case FLAP: {
                float ph = t * 31f; e = pulse(t, 0f, .2f, dur - .3f, dur);
                wings((45f + 38f * sin(ph)) * e); T.by -= abs(sin(ph * .5f)) * 5f * e; T.rot += 3f * sin(ph * .5f) * e;
                T.expr = Expr.HAPPY; T.cheek = 1f; break; }
            case HAPPY:
                T.expr = Expr.HAPPY; T.by -= abs(sin(t * 6f)) * 5f; wings(22f + 12f * sin(t * 9f));
                T.rot += 4f * sin(t * 4f); T.cheek = 1f; T.mouth = .2f; break;
            case VERY_HAPPY:
                T.expr = Expr.VERY_HAPPY; T.by -= abs(sin(t * 7f)) * 9f; wings(45f + 25f * sin(t * 11f));
                T.rot += 6f * sin(t * 5f); T.mouth = .75f; T.cheek = 1.2f; T.sy += .03f * sin(t * 14f); break;
            case JUMP:
                jump(in, JUMP_PREP, JUMP_AIR, 130f, 70f, false); break;
            case BIG_JUMP:
                jump(in, BIG_PREP, BIG_AIR, 250f, 105f, true); break;
            case SLIDE:
                e = seg(t, 0f, .12f);
                T.rot += -d * 18f * e; T.fxl += d * 20f * e; T.fxr += d * 20f * e; T.fyl -= 6f * e; T.fyr -= 6f * e;
                wings(60f * e); T.expr = Expr.SURPRISED; T.mouth = .4f; T.sy -= .03f; T.turn = 0f; T.lookY = -.4f; break;
            case FALL: {
                float f = seg(t, 0f, FALL_IMPACT);
                T.rot = -d * 80f * f * f; T.bx = d * 340f * f * f;      // keep the toppled body over its feet instead of swinging off to the side
                if (t < FALL_IMPACT) { T.expr = Expr.SURPRISED; T.mouth = .5f; wings(70f * f); }
                else {
                    T.expr = Expr.DAZE; T.sy = .92f - .1f * pulse(t, FALL_IMPACT, FALL_IMPACT + .05f, FALL_IMPACT + .08f, .6f);
                    T.sx = 1.08f + .06f * pulse(t, FALL_IMPACT, FALL_IMPACT + .05f, FALL_IMPACT + .08f, .6f);
                    wings(70f * seg(t, FALL_IMPACT, .5f)); T.mouth = .15f; T.cheek = 1.1f;
                    T.fyl = -10f * sin(t * 14f) * seg(t, .6f, .8f); T.fyr = 10f * sin(t * 14f) * seg(t, .6f, .8f);
                }
                if (cross(State.FALL, t, FALL_IMPACT)) events |= EV_IMPACT;
                break; }
            case GET_UP: {
                float r = t < .3f ? lerp(80f, 64f, seg(t, 0f, .3f)) : t < .55f ? lerp(64f, 28f, seg(t, .3f, .55f))
                        : t < .8f ? lerp(28f, 56f, seg(t, .55f, .8f)) : lerp(56f, 0f, seg(t, .8f, 1.2f));
                T.rot = -d * r; T.bx = d * 340f * (r / 80f);
                if (t < .8f) { wings(60f + 30f * sin(t * 16f)); T.expr = Expr.DAZE; } else { wings(10f); T.expr = Expr.CURIOUS; }
                T.sy = .95f; T.sx = 1.04f; break; }
            case SHAKE_BODY: {
                float env = 1f - seg(t, .5f, dur), w = sin(t * 34f);
                T.rot += 10f * w * env; T.hrot += 8f * sin(t * 34f - 1.1f) * env; wings(28f + 22f * sin(t * 34f - 2f) * env);
                T.bx += 4f * w * env; T.expr = env > .1f ? Expr.DAZE : Expr.HAPPY; T.tail += 20f * w * env; break; }
            case SPIN: {
                float s = cl(t / dur, 0f, 1f);
                T.turn = 1.6f * sin(6.2831853f * s); T.rot += 6f * sin(6.2831853f * s); T.by -= 14f * sin(3.1415927f * s);
                wings(40f * sin(3.1415927f * s)); T.expr = Expr.HAPPY; T.cheek = 1f; break; }
            case CURIOUS:
                e = pulse(t, 0f, .2f, dur - .2f, dur);
                T.expr = Expr.CURIOUS; T.hrot += 10f * rn * e; T.hy -= 3f * e; T.browL = .7f * e; wings(14f * e); T.eyeS = 1.08f; break;
            case SURPRISED: {
                float k = pulse(t, 0f, .08f, .3f, .6f);
                T.sy += .16f * k + .03f; T.sx -= .08f * k; T.by -= 10f * k; wings(55f * k);
                T.expr = Expr.SURPRISED; T.mouth = .5f * k; T.browL = T.browR = 1f; T.eyeS = 1.2f; break; }
            case CONFUSED:
                T.expr = Expr.CONFUSED; T.hrot += 8f * sin(t * 3.2f); T.rot += 2f * sin(t * 3.2f + 1f); wings(30f + 6f * sin(t * 3f));
                T.browL = .9f; T.browR = -.6f; T.lookX = sin(t * 2.4f) * .7f; T.mouth = .12f;
                if (cross(State.CONFUSED, t, .3f)) events |= EV_SWEAT; break;
            case EXCITED:
                T.expr = Expr.EXCITED; T.by -= abs(sin(t * 10f)) * 7f; wings(50f + 30f * sin(t * 20f));
                T.sx += .03f * sin(t * 20f); T.mouth = .5f; T.cheek = 1.1f; T.eyeS = 1.15f; break;
            case SHY:
                T.expr = Expr.SHY; T.turn = -d * 1f; T.hy += 5f; T.hrot = -d * 6f; T.lookX = -d * .8f; T.lookY = .6f; T.cheek = 1.5f;
                T.rot += 3f * sin(t * 2.2f); wings(6f); T.fxl += 4f * sin(t * 3f); T.browL = T.browR = -.5f; break;
            case HEAD_PAT: {
                float k = pulse(t, 0f, .07f, .18f, .32f);
                T.sy += .12f * k; T.by -= 9f * k; wings(40f * k);
                if (t < .3f) { T.expr = Expr.SURPRISED; T.eyeS = 1.15f; }
                else if (t < .85f) { T.expr = Expr.NORMAL; look(0f, .25f, 1f); T.turn = 0f; T.cheek = 1f; }
                else { T.expr = Expr.HAPPY; T.cheek = 1.4f; }
                float env = seg(t, .9f, 1.2f) * (1f - seg(t, dur - .5f, dur));
                wings(Math.max(T.wl, (28f + 34f * sin(t * 17f)) * env)); T.rot += 8f * sin(t * 5.5f) * env;
                T.by -= abs(sin(t * 5.5f)) * 5f * env; T.sx += .06f * env; T.sy -= .03f * env; T.turn *= 1f - env;
                break; }
            case BELLY_TICKLE: {
                T.expr = Expr.SURPRISED; T.eyeS = 1.2f;
                T.sy -= .1f * pulse(t, 0f, .05f, .1f, .2f);
                float wv = seg(t, .08f, .3f) * (1f - seg(t, 1.2f, 1.6f));
                wings(105f * wv); T.mouth = .6f * wv; T.browL = T.browR = .9f; look(0f, .1f, 1f); T.turn = 0f;
                float tj = t - .5f;
                if (tj > 0f && tj < .55f) {
                    float s = tj / .55f;
                    T.by -= 100f * 4f * s * (1f - s); T.sy += .12f * (1f - s); T.sx -= .06f * (1f - s);
                } else if (tj >= .55f) {
                    float k = pulse(tj - .55f, 0f, .04f, .1f, .3f); T.sy -= .22f * k; T.sx += .15f * k;
                }
                if (t > 1.5f) { T.expr = Expr.NORMAL; T.eyeS = 1.05f; }
                if (cross(State.BELLY_TICKLE, t, .5f)) events |= EV_TAKEOFF;
                if (cross(State.BELLY_TICKLE, t, 1.05f)) events |= EV_LAND;
                break; }
            case MULTI_TAP:
                T.expr = Expr.SURPRISED; wings(70f + 40f * sin(t * 30f)); T.rot += 12f * sin(t * 15f);
                look(sin(t * 9f), 0f, 1f); T.sy += .05f; T.by -= abs(sin(t * 9f)) * 8f; T.mouth = .5f; T.browL = T.browR = 1f;
                if (cross(State.MULTI_TAP, t, .2f)) events |= EV_EXCL; break;
            case PET:
                T.expr = Expr.HAPPY; T.cheek = 1.6f; T.rot += in.petDir * 8f; T.hrot += in.petDir * 10f; T.hy += 6f;
                wings(34f + 8f * sin(t * 6f)); T.sy -= .02f; T.turn = 0f; look(0f, .2f, 1f); break;
            case GREETING_USER:
                T.by -= abs(sin(t * 6.3f)) * 14f; T.wr = 70f + 30f * sin(t * 16f); T.wl = 8f; T.expr = Expr.VERY_HAPPY;
                T.mouth = .6f; look(0f, .2f, 1f); T.turn = 0f; T.cheek = 1.2f; T.rot += 3f * sin(t * 6.3f); break;
            case LOOK_AT_CLOCK:
                e = pulse(t, 0f, .3f, dur - .35f, dur);
                look(in.clockDx, -1f, e); T.turn = lerp(T.turn, in.clockDx * .9f, e); T.hrot += in.clockDx * 5f * e;
                T.hy -= 6f * e; T.browL = .3f * e; T.lid = blinkWave(Math.max(0f, t - dur * .6f)); break;
            case LOOK_WINDOW: case STARGAZE: {
                boolean star = in.s == State.STARGAZE;
                e = pulse(t, 0f, .35f, dur - .4f, dur);
                look(in.windowDx + (in.weather == 1 ? .35f * sin(t * 1.3f) : 0f), star ? -1f : -.7f, e);
                T.turn = lerp(T.turn, in.windowDx * (star ? .5f : .9f), e);
                T.hy -= (star ? 12f : 5f) * e; T.hrot += (in.weather == 1 ? 4f * sin(t * .8f) : 0f) * e;
                if (star || in.weather == 3) { T.expr = Expr.EXCITED; T.eyeS = 1.12f; T.mouth = 0f; wings((10f + 6f * sin(t * 1.5f)) * e); }
                else if (in.weather == 2) { T.expr = Expr.EXCITED; wings(20f * e); }
                else T.expr = Expr.CURIOUS;
                T.cheek = 1f; break; }
            case REST_WHILE_CHARGING:
                sit(1f); T.expr = t < 2.5f ? Expr.SLEEPY : Expr.SLEEPING; T.lid = t < 2.5f ? .35f + .4f * seg(t, 0f, 2.5f) : 0f;
                T.sy += .015f * sin(ti * 1.2f); T.cheek = .9f; T.hy += 6f; break;
            case PEEK_FROM_EDGE:
                T.rot += d * 10f; T.hrot += d * 8f; T.hx += d * 10f; T.turn = d * 1f;
                look(d * (.5f + .5f * sin(t * 3f)), .1f, 1f); T.expr = Expr.CURIOUS; T.sy += .02f * sin(t * 5f);
                T.lid = blinkWave(Math.max(0f, t - 1.2f)); break;
            case EAT: {
                float peck = abs(sin(t * 5.5f)), k = pulse(t, 0f, .3f, dur - .4f, dur);
                T.hy += (24f + 18f * peck) * k; T.hrot += d * (10f + 6f * peck) * k; T.rot += d * 7f * k; T.sy -= .03f * k;
                T.turn = d * .6f * k; T.lookY = .9f * k; wings((10f + 8f * peck) * k);
                T.expr = k > .8f ? Expr.HAPPY : Expr.NORMAL; T.mouth = .25f * peck * k; break; }
            case DRINK: {
                float peck = abs(sin(t * 3.2f)), k = pulse(t, 0f, .3f, dur - .4f, dur);
                T.hy += (20f + 10f * peck) * k; T.hrot += d * 8f * k; T.rot += d * 6f * k; T.turn = d * .6f * k; T.lookY = .9f * k;
                T.sy -= .02f * k; break; }
            case NOD_OFF: {
                sit(1f);
                if (t < 1.7f) {
                    float dk = seg(t, 0f, 1.7f);
                    T.hy += 4f + 14f * dk; T.hrot += rn * (4f + 12f * dk); T.lid = .5f + .5f * dk; T.expr = Expr.SLEEPY;
                } else {
                    float j = pulse(t, 1.7f, 1.78f, 1.95f, 2.3f);
                    sit(1f - .8f * j); T.sy += .12f * j; T.hy -= 18f * j; wings(60f * j); T.by -= 10f * j;
                    T.expr = j > .2f ? Expr.SURPRISED : Expr.NORMAL; T.eyeS = 1f + .15f * j;
                }
                if (cross(State.NOD_OFF, t, 1.7f)) events |= EV_EXCL;
                break; }
            case LOOK_AT_WING: {
                e = pulse(t, 0f, .35f, dur - .4f, dur); float side = rn > 0 ? 1f : -1f;
                float wv = 45f * e + 10f * sin(t * 14f) * e;
                if (side > 0) T.wr += wv; else T.wl += wv;
                T.hrot += side * 10f * e; T.turn = lerp(T.turn, side, e); look(side, .7f, e); T.expr = Expr.CURIOUS; break; }
            case LOOK_AT_FEET: {
                e = pulse(t, 0f, .35f, dur - .4f, dur);
                T.hy += 12f * e; look(0f, 1f, e); T.turn *= 1f - e; T.sy -= .02f * e; T.expr = Expr.CURIOUS; T.browL = .5f * e;
                float lift = Math.max(0f, sin(t * 7f)) * e;
                if (rn > 0) { T.fyl -= 16f * lift; T.fxl += 6f * sin(t * 7f) * e; } else { T.fyr -= 16f * lift; T.fxr += 6f * sin(t * 7f) * e; }
                break; }
            // ---------------------------------------------------------------- emotions
            case ANGRY: {                     // puffed up, stomping, wings pulled back, quick angry shakes
                e = pulse(t, 0f, .2f, dur - .3f, dur);
                final float st = sin(t * 11f);
                T.expr = Expr.ANGRY; T.browL = T.browR = -1f * e; T.lid = .28f * e; T.cheek = 1.5f;
                T.sx += .06f * e; T.sy += .03f * e; T.rot += d * 3f * st * e; T.hrot += -d * 4f * e + 3f * sin(t * 23f) * e;
                wings(-28f * e + 10f * abs(st) * e); T.hy -= 4f * e;
                if (st > 0) T.fyl -= 18f * st * e; else T.fyr += 18f * st * e;           // stomp stomp
                T.by += 3f * abs(st) * e; T.mouth = .12f * e; T.turn = d * .4f; break; }
            case SULK: {                      // back half-turned, sitting, head down, eyes shut ("hmph!")
                e = pulse(t, 0f, .4f, dur - .4f, dur);
                sit(.6f * e); T.expr = Expr.ANGRY; T.browL = T.browR = -.8f * e; T.lid = .75f * e; T.cheek = 1.4f;
                T.turn = -d * .9f * e; T.hrot = -d * 8f * e; T.hy += 6f * e; T.lookX = -d * .8f; T.lookY = .3f;
                wings(-10f * e); T.rot += -d * 2f * e + .8f * sin(t * 1.3f) * e; break; }
            case SAD: {                       // droopy: head down, wings hanging, slow sway
                e = pulse(t, 0f, .5f, dur - .4f, dur);
                T.expr = Expr.SAD; T.browL = T.browR = -1f * e; T.lid = .35f * e; T.cheek = .5f;
                T.hy += 14f * e; T.hrot += rn * 6f * e + 2f * sin(t * 1.4f) * e; T.sy -= .04f * e; T.sx += .02f * e;
                wings(-8f * e); T.lookY = .7f * e; T.rot += 1.5f * sin(t * 1.4f) * e; break; }
            case CRY: {                       // sobbing: shoulders jerk, mouth wails
                e = pulse(t, 0f, .3f, dur - .3f, dur);
                final float sob = abs(sin(t * 7f));
                T.expr = Expr.SAD; T.browL = T.browR = -1f * e; T.lid = .55f * e; T.cheek = 1.2f;
                T.hy += (8f - 6f * sob) * e; T.sy += (.04f * sob - .03f) * e; wings((14f + 18f * sob) * e);
                T.mouth = (.45f + .25f * sob) * e; T.lookY = .3f; T.rot += 2f * sin(t * 14f) * e; break; }
            case LAUGH: {                     // ha ha ha: bouncing, holding the belly, beak wide open
                e = pulse(t, 0f, .15f, dur - .3f, dur);
                final float ha = abs(sin(t * 12f));
                T.expr = Expr.VERY_HAPPY; T.cheek = 1.5f; T.mouth = (.4f + .35f * ha) * e;
                T.by -= 7f * ha * e; T.sy += .05f * ha * e; T.hrot += (-d * 10f + 4f * sin(t * 12f)) * e; T.hy -= 6f * e;
                T.wl = -24f * e + 6f * ha; T.wr = -24f * e + 6f * ha; T.rot += -d * 4f * e; break; }
            case DANCE: {                     // side steps, wing waves, a little turn on every fourth beat
                final float beat = t * 4.2f, sb = sin(beat), cb = cos(beat);
                e = pulse(t, 0f, .25f, dur - .3f, dur);
                T.expr = (((int) (beat / 3.1415927f)) % 4 == 3) ? Expr.EXCITED : Expr.VERY_HAPPY; T.cheek = 1.3f;
                T.bx += 16f * sb * e; T.rot += 9f * sb * e; T.by -= 9f * abs(cb) * e; T.hrot += -7f * sb * e;
                T.wl = (35f + 35f * sb) * e; T.wr = (35f - 35f * sb) * e;
                if (sb > 0) T.fyl -= 16f * sb * e; else T.fyr += 16f * sb * e;
                T.turn = (((int) (beat / 3.1415927f)) % 4 == 3 ? sin(beat * .5f) * 1.2f : d * .3f); T.mouth = .2f * e; break; }
            case SCUFFLE: {                   // a fast cartoon tussle (mostly hidden in the dust cloud)
                final float j = sin(t * 31f), k2 = sin(t * 23f + 1f);
                T.expr = ((int) (t * 5f)) % 3 == 0 ? Expr.DAZE : Expr.ANGRY; T.browL = T.browR = -1f; T.cheek = 1.5f;
                T.rot += 14f * j; T.bx += 14f * k2; T.by -= 10f * abs(j); wings(50f + 40f * k2); T.hrot += 10f * k2; T.mouth = .3f; break; }
            case HIDE: {                      // crouched low behind the bed, peeking up now and then
                sit(1f); T.sy -= .12f; T.hy += 34f; wings(-6f);
                final float pk = pulse(t % 3.2f, 1.6f, 1.9f, 2.5f, 2.9f);
                T.hy -= 46f * pk; T.expr = pk > .3f ? Expr.CURIOUS : Expr.SHY; T.lookX = d * .6f * pk; T.cheek = 1.3f; break; }
            case BRUSH: {                     // brushing teeth: one wing up at the beak scrubbing, foam (the brush itself is drawn by the engine)
                final float sc = sin(t * 16f);
                T.wr = 105f + 10f * sc; T.wl = 6f; T.hrot += 3f * sc; T.mouth = .28f; T.expr = Expr.HAPPY; T.cheek = 1.1f; T.by -= abs(sc) * 2f; break; }
            case WINK: {
                e = pulse(t, 0f, .15f, dur - .25f, dur);
                T.expr = Expr.WINK; T.cheek = 1.3f; T.hrot += -d * 8f * e; T.wr = 40f * e; T.mouth = .1f * e; T.turn = d * .2f; break; }
            case SMUG: {                      // ドヤ顔: chest out, chin up, wings on hips
                e = pulse(t, 0f, .25f, dur - .3f, dur);
                T.expr = Expr.SMUG; T.browL = T.browR = .6f * e; T.lid = .42f * e; T.cheek = 1.1f;
                T.sy += .04f * e; T.sx += .03f * e; T.hy -= 8f * e; T.hrot += d * 5f * e; wings(-34f * e); T.lookY = -.3f * e; T.turn = d * .3f; break; }
            case SNEEZE: {                    // "は…は…くしゅんっ!"
                final float build = seg(t, 0f, .9f), hit = pulse(t, .95f, 1.0f, 1.15f, 1.6f);
                T.expr = t < .95f ? Expr.SLEEPY : Expr.DAZE; T.lid = .5f * build;
                T.hy -= 10f * build - 28f * hit; T.hrot += -d * 8f * build + d * 18f * hit; T.mouth = .35f * build * (1f - hit);
                T.sy += .04f * build - .1f * hit; wings(20f * hit); T.by += 6f * hit;
                if (cross(State.SNEEZE, t, .97f)) events |= EV_SNEEZE;
                break; }
            case HANDSTAND: {                 // a wobbly handstand (bond level 3+)
                final float k = seg(t, 0f, .5f) * (1f - seg(t, dur - .5f, dur));
                T.rot = 180f * k * d; T.by = -690f * k; T.expr = k > .6f ? Expr.EXCITED : Expr.CURIOUS; wings(150f * k);
                T.rot += 6f * sin(t * 5f) * k; T.fyl = -20f * sin(t * 6f) * k; T.fyr = 20f * sin(t * 6f) * k; T.mouth = .2f * k; break; }
            case FLIP: {                      // somersault (bond level 4+)
                final float prep = .3f, air = .8f;
                if (t < prep) { final float k = seg(t, 0f, prep); T.sy = 1f - .22f * k; T.sx = 1f + .14f * k; wings(-14f * k); T.expr = Expr.EXCITED; }
                else if (t < prep + air) {
                    final float s = (t - prep) / air; T.by = -330f * 4f * s * (1f - s); T.rot = 360f * sm(s) * d; wings(70f); T.expr = Expr.EXCITED; T.mouth = .4f;
                    if (cross(State.FLIP, t, prep + air)) events |= EV_LAND;
                } else { final float u2 = t - prep - air, k = pulse(u2, 0f, .05f, .12f, .35f); T.sy = 1f - .24f * k; T.sx = 1f + .16f * k; T.expr = Expr.VERY_HAPPY; T.cheek = 1.3f; wings(30f * (1f - seg(u2, 0f, .4f))); }
                break; }
            case PUNCH: {                     // wind up, then a straight wing jab with a lunge
                final float wind = pulse(t, 0f, .1f, .12f, .16f), jab = pulse(t, .12f, .2f, .34f, .5f);
                T.expr = Expr.ANGRY; T.browL = T.browR = -1f; T.cheek = 1.5f; T.mouth = .35f * jab; T.turn = d * .8f;
                T.rot += -d * 6f * wind + d * 12f * jab; T.bx += -d * 14f * wind + d * 46f * jab; T.hrot += d * 6f * jab;
                final float front = -35f * wind + 125f * jab, back = -20f * jab;
                if (d > 0) { T.wr = front; T.wl = back; } else { T.wl = front; T.wr = back; }
                break; }
            case KICK: {                      // lean back and kick with the front foot
                final float crouch = pulse(t, 0f, .12f, .15f, .2f), kick = pulse(t, .15f, .24f, .4f, .6f);
                T.expr = Expr.ANGRY; T.browL = T.browR = -1f; T.cheek = 1.5f; T.mouth = .3f * kick; T.turn = d * .8f;
                T.sy -= .1f * crouch; T.rot += -d * 16f * kick; T.bx += d * 20f * kick; wings(45f * kick - 10f * crouch);
                if (d > 0) { T.fxr += 120f * kick; T.fyr -= 80f * kick; } else { T.fxl -= 120f * kick; T.fyl -= 80f * kick; }
                break; }
            case HIT: {                       // got hit: knocked back, dazed, wings flailing
                final float k = pulse(t, 0f, .05f, .3f, dur);
                T.expr = Expr.DAZE; T.cheek = 1.5f; T.mouth = .35f * k;
                T.rot += -d * 18f * k + 4f * sin(t * 30f) * k; T.bx += -d * 36f * k; T.sy -= .08f * k; T.sx += .05f * k; wings(70f * k + 20f * sin(t * 25f) * k); T.hrot += -d * 14f * k;
                break; }
            default: break;
        }

        // finger following: eyes first (fast spring), head a little later (slow spring)
        if (in.gazeOn > .01f && in.gazeOk) {
            look(in.gx, in.gy, in.gazeOn);
            T.turn = lerp(T.turn, in.gx * .45f, in.gazeOn); T.hrot += in.gx * 4f * in.gazeOn; T.hy += in.gy * 3f * in.gazeOn;
        }
    }

    // =====================================================================================================
    /** Advance one frame. */
    void update(float dt, In in) {
        events = 0;
        eval(in);
        prevS = in.s; prevT = in.t;
        final State s = in.s;
        final boolean sleeping = s.isSleeping() || T.expr == Expr.SLEEPING;
        final float ph = in.time * (sleeping ? 1.4f : 2.1f);

        // breathing (always): belly rises, wings and head follow a beat later
        final float br = sin(ph);
        T.sy += (sleeping ? .02f : .016f) * br; T.sx -= .008f * br;
        T.wl += 2.5f * sin(ph - .6f); T.wr += 2.5f * sin(ph - .6f); T.hy += -1.5f * sin(ph - .4f);

        // blink
        if (T.expr != Expr.HAPPY && T.expr != Expr.VERY_HAPPY && !sleeping && T.expr != Expr.DAZE) {
            if (blinkT >= 0f) { blinkT += dt; T.lid = Math.max(T.lid, blinkWave(blinkT)); if (blinkT > .17f) blinkT = -1f; }
            else { blinkClock -= dt; if (blinkClock <= 0f) { blinkT = 0f; blinkClock = 2.2f + rnd.nextFloat() * 3.4f; if (rnd.nextInt(7) == 0) blinkClock = .35f; } }
        }

        // random micro-animations while calm
        if (in.micro) {
            if (mType < 0) {
                mClock -= dt;
                if (mClock <= 0f) { mType = rnd.nextInt(6); mT = 0f; mDur = .8f + rnd.nextFloat() * .9f; mDir = rnd.nextBoolean() ? 1 : -1; mClock = 1.6f + rnd.nextFloat() * 2.8f; }
            } else {
                mT += dt;
                final float me = sin(3.1415927f * cl(mT / mDur, 0f, 1f));
                switch (mType) {
                    case 0: T.lookX = lerp(T.lookX, mDir * .85f, me); T.lookY = lerp(T.lookY, rnd.nextInt(2) * 0f, 0f); break; // eyes only
                    case 1: T.hrot += mDir * 7f * me; T.hx += mDir * 3f * me; break;                                              // head tilt
                    case 2: if (mDir > 0) T.wr += 18f * me; else T.wl += 18f * me; break;                                         // wing twitch
                    case 3: T.rot += mDir * 3f * me; T.bx += mDir * 5f * me; if (mDir > 0) T.fyl -= 5f * me; else T.fyr -= 5f * me; break; // weight shift
                    case 4: T.tail += mDir * 16f * sin(mT * 18f) * me; break;                                                     // tail wag
                    default: T.sy += .03f * me; T.wl += 8f * me; T.wr += 8f * me; break;                                          // deep breath
                }
                if (mT >= mDur) mType = -1;
            }
        }

        // expression defaults
        expr = T.expr;
        if (T.cheek < 0f) {
            switch (expr) {
                case HAPPY: T.cheek = .95f; break; case VERY_HAPPY: T.cheek = 1.2f; break; case SHY: T.cheek = 1.5f; break;
                case SURPRISED: T.cheek = .5f; break; case SLEEPING: T.cheek = .8f; break; case EXCITED: T.cheek = 1.1f; break;
                default: T.cheek = .7f; break;
            }
        }
        if (T.eyeS == 1f) {
            switch (expr) { case SURPRISED: T.eyeS = 1.22f; break; case EXCITED: T.eyeS = 1.14f; break; case CURIOUS: T.eyeS = 1.07f; break; default: break; }
        }
        if (expr == Expr.SLEEPY && T.lid < .35f) T.lid = .35f;

        final float k = dt > .05f ? .05f : dt;
        bx.update(T.bx, k); by.update(T.by, k); rot.update(T.rot, k); sx.update(T.sx, k); sy.update(T.sy, k);
        hx.update(T.hx, k); hy.update(T.hy, k); hrot.update(T.hrot, k);
        wl.update(T.wl, k); wr.update(T.wr, k);
        fxl.update(T.fxl, k); fyl.update(T.fyl, k); fxr.update(T.fxr, k); fyr.update(T.fyr, k);
        tail.update(T.tail, k); lookX.update(T.lookX, k); lookY.update(T.lookY, k); turn.update(T.turn, k);
        lid.update(T.lid, k); mouth.update(T.mouth, k); cheek.update(T.cheek, k);
        browL.update(T.browL, k); browR.update(T.browR, k); eyeS.update(T.eyeS, k); footS.update(T.footS, k);
    }

    // =====================================================================================================
    private void part(Canvas c, Bitmap b, int l, int t) { if (b != null) c.drawBitmap(b, l, t, bm); }

    /**
     * Draw the penguin standing at (x, y) = feet position on screen, u = screen pixels per art unit.
     * @param alpha 0..255 overall opacity, @param face -1 / 1 (tail side)
     */
    void draw(Canvas c, PenguinArt a, float x, float y, float u, int face, int alpha, float groundLift) {
        if (alpha <= 0) return;
        c.save();
        c.translate(x, y); c.scale(u, u); c.translate(-320f, -GROUND);

        // soft ground shadow (shrinks while airborne)
        final float lift = Math.max(0f, -by.p) + groundLift;
        final float ss = 1f / (1f + lift / 260f);
        shadowP.setAlpha((int) (62 * ss * alpha / 255f));
        rf.set(320f - 190f * ss, GROUND - 22f, 320f + 190f * ss, GROUND + 16f);
        c.drawOval(rf, shadowP);

        bm.setAlpha(alpha);
        c.save();
        c.translate(bx.p, by.p);
        c.rotate(rot.p, 320f, GROUND);
        c.scale(sx.p, sy.p, 320f, GROUND);

        // tail (behind body, opposite to facing direction)
        c.save();
        if (face > 0) c.scale(-1f, 1f, 320f, 0f);
        c.rotate(tail.p, 440f, 700f);
        part(c, a.tail, PartLayout.PG_TAIL_L, PartLayout.PG_TAIL_T);
        c.restore();

        part(c, a.body, PartLayout.PG_BODY_L, PartLayout.PG_BODY_T);

        // feet (in front of the belly)
        c.save(); c.translate(fxl.p, fyl.p); c.scale(footS.p, footS.p, 246f, 750f);
        part(c, a.footL, PartLayout.PG_FOOT_L_L, PartLayout.PG_FOOT_L_T); c.restore();
        c.save(); c.translate(fxr.p, fyr.p); c.scale(footS.p, footS.p, 394f, 750f);
        part(c, a.footR, PartLayout.PG_FOOT_R_L, PartLayout.PG_FOOT_R_T); c.restore();
        if (boots) { boot(c, 246f + fxl.p, 742f + fyl.p, alpha); boot(c, 394f + fxr.p, 742f + fyr.p, alpha); }

        // wings pivot at the shoulders
        c.save(); c.rotate(wl.p, SHOULDER_LX, SHOULDER_Y);
        part(c, a.wingL, PartLayout.PG_WING_L_L, PartLayout.PG_WING_L_T); c.restore();
        c.save(); c.rotate(-wr.p, SHOULDER_RX, SHOULDER_Y);
        part(c, a.wingR, PartLayout.PG_WING_R_L, PartLayout.PG_WING_R_T); c.restore();

        if (costume == C_MUFFLER) muffler(c, alpha);
        // head group: head art + eyes / cheeks / mouth / beak
        c.save();
        c.translate(hx.p, hy.p);
        c.rotate(hrot.p, 320f, NECK_Y);
        part(c, a.head, PartLayout.PG_HEAD_L, PartLayout.PG_HEAD_T);
        drawFace(c, a, alpha);
        if (costume >= C_STRAW) hat(c, alpha);
        c.restore();

        c.restore();   // body group
        bm.setAlpha(255);
        c.restore();
    }

    /** Red knitted muffler around the neck with one end hanging down. */
    private void muffler(Canvas c, int alpha) {
        mouthP.setAlpha(alpha);
        mouthP.setColor(0xFFE0474C); rf.set(150f, 430f, 490f, 512f); c.drawRoundRect(rf, 40f, 40f, mouthP);
        rf.set(370f, 470f, 432f, 640f); c.drawRoundRect(rf, 22f, 22f, mouthP);                          // hanging end
        mouthP.setColor(0xFFFFFFFF); for (int i = 0; i < 4; i++) { rf.set(196f + i * 72f, 462f, 232f + i * 72f, 476f); c.drawRoundRect(rf, 7f, 7f, mouthP); }
        rf.set(380f, 590f, 422f, 602f); c.drawRoundRect(rf, 6f, 6f, mouthP);
        mouthP.setColor(0xFFB8333A); for (int i = 0; i < 5; i++) c.drawRect(374f + i * 12f, 636f, 380f + i * 12f, 664f, mouthP);   // fringe
        mouthP.setColor(0xFF6B2B30);
    }
    /** Seasonal hats (drawn on the head, so they move with it). */
    private void hat(Canvas c, int alpha) {
        mouthP.setAlpha(alpha);
        if (costume == C_STRAW) {
            mouthP.setColor(0xFFE9C46A); rf.set(110f, 74f, 530f, 150f); c.drawOval(rf, mouthP);              // brim
            mouthP.setColor(0xFFF2D488); rf.set(200f, 10f, 440f, 122f); c.drawRoundRect(rf, 70f, 70f, mouthP);   // crown
            mouthP.setColor(0xFFE0565B); c.drawRect(204f, 82f, 436f, 108f, mouthP);                                // ribbon
        } else if (costume == C_PUMPKIN) {
            mouthP.setColor(0xFFF28C28); rf.set(170f, 20f, 470f, 150f); c.drawOval(rf, mouthP);
            mouthP.setColor(0xFFD9741A); rf.set(250f, 20f, 390f, 150f); c.drawOval(rf, mouthP);
            mouthP.setColor(0xFFF5A040); rf.set(290f, 22f, 350f, 148f); c.drawOval(rf, mouthP);
            mouthP.setColor(0xFF4F8A3B); rf.set(306f, -14f, 334f, 34f); c.drawRoundRect(rf, 10f, 10f, mouthP);   // stem
        } else {   // Santa
            hatPath.reset(); hatPath.moveTo(150f, 120f); hatPath.quadTo(300f, -40f, 520f, 10f); hatPath.lineTo(490f, 120f); hatPath.close();
            mouthP.setColor(0xFFE0353B); c.drawPath(hatPath, mouthP);
            mouthP.setColor(0xFFFFFFFF); rf.set(130f, 96f, 510f, 150f); c.drawRoundRect(rf, 27f, 27f, mouthP);
            c.drawCircle(522f, 14f, 30f, mouthP);
        }
        mouthP.setColor(0xFF6B2B30);
    }

    private void boot(Canvas c, float cx, float cy, int alpha) {
        mouthP.setColor(0xFFFFCB2E); mouthP.setAlpha(alpha); rf.set(cx - 62f, cy - 44f, cx + 62f, cy + 34f); c.drawRoundRect(rf, 30f, 30f, mouthP);
        mouthP.setColor(0xFFE0A21A); rf.set(cx - 64f, cy + 18f, cx + 64f, cy + 36f); c.drawRoundRect(rf, 9f, 9f, mouthP);    // sole
        mouthP.setColor(0x88FFFFFF); rf.set(cx - 40f, cy - 36f, cx - 16f, cy - 4f); c.drawOval(rf, mouthP);
        mouthP.setColor(0xFF6B2B30);
    }

    private void drawFace(Canvas c, PenguinArt a, int alpha) {
        final float tn = turn.p;
        final float lx = lookX.p * 9f, ly = lookY.p * 8f;
        final float cheekShift = tn * 14f, eyeShift = tn * 22f, beakShift = tn * 30f;

        // cheeks
        final float ca = cl(cheek.p, 0f, 1.6f) / 1.6f;
        cheekP.setAlpha((int) ((70 + 170 * ca) * alpha / 255f));
        final float cs = 1f + (cheek.p - .7f) * .12f;
        rf.set(176f + cheekShift - 36f * cs, 392f - 25f * cs, 176f + cheekShift + 36f * cs, 392f + 25f * cs); c.drawOval(rf, cheekP);
        rf.set(464f + cheekShift - 36f * cs, 392f - 25f * cs, 464f + cheekShift + 36f * cs, 392f + 25f * cs); c.drawOval(rf, cheekP);

        // eyes
        eyeP.setAlpha(alpha); hlP.setAlpha(alpha); strokeP.setAlpha(alpha);
        drawEye(c, 232f + eyeShift + lx, 330f + ly, -1, alpha);
        drawEye(c, 408f + eyeShift + lx, 330f + ly, 1, alpha);

        // brows
        drawBrow(c, 232f + eyeShift, 330f + ly * .5f, browL.p, -1);
        drawBrow(c, 408f + eyeShift, 330f + ly * .5f, browR.p, 1);

        // mouth (opens under the beak)
        final float m = cl(mouth.p, 0f, 1.2f);
        if (m > .03f) {
            mouthP.setAlpha(alpha); tongueP.setAlpha(alpha);
            final float mx = 320f + beakShift, my = 394f + m * 7f;
            rf.set(mx - (14f + 22f * m), my - 4f, mx + (14f + 22f * m), my + 6f + 28f * m); c.drawOval(rf, mouthP);
            rf.set(mx - 10f - 8f * m, my + 10f + 14f * m, mx + 10f + 8f * m, my + 4f + 28f * m); c.drawOval(rf, tongueP);
        }
        else if (expr == Expr.SMUG) {                                // one-sided smirk
            strokeP.setStrokeWidth(7f); strokeP.setAlpha(alpha);
            final float mx = 320f + beakShift;
            rf.set(mx - 4f, 396f, mx + 34f, 422f); c.drawArc(rf, 20f, 120f, false, strokeP);
        }
        else if (expr == Expr.ANGRY || expr == Expr.SAD) {          // small frown under the beak
            strokeP.setStrokeWidth(7f); strokeP.setAlpha(alpha);
            final float mx = 320f + beakShift;
            rf.set(mx - 20f, 410f, mx + 20f, 432f); c.drawArc(rf, 200f, 140f, false, strokeP);
        }
        // beak
        c.save(); c.translate(beakShift, -m * 9f);
        part(c, a.beak, PartLayout.PG_BEAK_L, PartLayout.PG_BEAK_T);
        c.restore();
    }

    private void drawEye(Canvas c, float cx, float cy, int side, int alpha) {
        final Expr ex = expr;
        float sc = eyeS.p;
        if (ex == Expr.CONFUSED) sc *= side < 0 ? 1.1f : .88f;
        final float rx = 22f * sc, ry = 30f * sc;

        if (ex == Expr.HAPPY || ex == Expr.VERY_HAPPY) {
            strokeP.setStrokeWidth(ex == Expr.VERY_HAPPY ? 12f : 10f);
            rf.set(cx - 25f, cy - 14f, cx + 25f, cy + 16f);
            c.drawArc(rf, 180f, 180f, false, strokeP);
            return;
        }
        if (ex == Expr.WINK && side > 0) {                 // one eye shut in a happy arc
            strokeP.setStrokeWidth(10f);
            rf.set(cx - 25f, cy - 14f, cx + 25f, cy + 16f);
            c.drawArc(rf, 180f, 180f, false, strokeP);
            return;
        }
        if (ex == Expr.SLEEPING) {
            strokeP.setStrokeWidth(9f);
            rf.set(cx - 25f, cy - 16f, cx + 25f, cy + 8f);
            c.drawArc(rf, 0f, 180f, false, strokeP);
            return;
        }
        if (ex == Expr.DAZE) {
            strokeP.setStrokeWidth(10f);
            chev.reset();
            if (side < 0) { chev.moveTo(cx - 16f, cy - 18f); chev.lineTo(cx + 12f, cy); chev.lineTo(cx - 16f, cy + 18f); }
            else          { chev.moveTo(cx + 16f, cy - 18f); chev.lineTo(cx - 12f, cy); chev.lineTo(cx + 16f, cy + 18f); }
            c.drawPath(chev, strokeP);
            return;
        }
        final float l = cl(lid.p, 0f, 1f);
        if (l > .93f) {                                     // fully closed: thin smile-like line
            strokeP.setStrokeWidth(8f);
            rf.set(cx - 22f, cy - 10f, cx + 22f, cy + 8f);
            c.drawArc(rf, 0f, 180f, false, strokeP);
            return;
        }
        c.save();
        if (l > .02f) c.clipRect(cx - rx - 3f, cy - ry + l * 2f * ry, cx + rx + 3f, cy + ry + 3f);   // lid = flat top edge
        rf.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(rf, eyeP);
        if (ex == Expr.EXCITED || (ex == Expr.CURIOUS && eyeS.p > 1.1f)) {
            c.save(); c.translate(cx + rx * .25f, cy - ry * .32f); c.scale(11f * sc, 11f * sc);
            drawStar(c); c.restore();
            c.drawCircle(cx - rx * .3f, cy + ry * .4f, 5f * sc, hlP);
        } else {
            c.drawCircle(cx + rx * .28f, cy - ry * .36f, 8.5f * sc, hlP);
            c.drawCircle(cx - rx * .30f, cy + ry * .42f, 4.2f * sc, hlP);
        }
        c.restore();
    }

    private final Path starPath = new Path();
    private boolean starInit;
    private void drawStar(Canvas c) {
        if (!starInit) {
            starPath.moveTo(0, -1f); starPath.quadTo(.12f, -.12f, 1f, 0); starPath.quadTo(.12f, .12f, 0, 1f);
            starPath.quadTo(-.12f, .12f, -1f, 0); starPath.quadTo(-.12f, -.12f, 0, -1f); starPath.close(); starInit = true;
        }
        c.drawPath(starPath, starP);
    }

    private void drawBrow(Canvas c, float cx, float cy, float b, int side) {
        if (abs(b) < .06f) return;
        strokeP.setStrokeWidth(8f);
        strokeP.setAlpha((int) (255 * cl(abs(b) * 2.2f, 0f, 1f)));
        final float y = cy - 54f - (b > 0 ? b * 12f : 0f);
        // inner end (toward the beak) / outer end. side=-1 is the left eye, whose inner end is on the right.
        final float xi = cx - side * -20f, xo = cx + side * -20f;
        float yi = b > 0 ? y + 4f : y + 4f + abs(b) * 10f;     // angry (b<0): inner end drops
        float yo = b > 0 ? y - b * 4f : y;
        if (expr == Expr.SAD && b < 0) { yi = y - abs(b) * 9f; yo = y + 6f; }   // sad: inner end rises
        c.drawLine(xo, yo, xi, yi, strokeP);
        strokeP.setAlpha(255);
    }
}
