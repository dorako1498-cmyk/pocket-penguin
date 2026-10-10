package com.pocketpenguin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Random;

/**
 * The little room. Furniture is pre-rendered once into a single bitmap (with a transparent window pane), everything that
 * moves (sky, weather, curtains, light shaft, clock hands, lamp glow, charger glow) is drawn with a handful of primitives.
 * Scenes are plain parameter sets (Look); a scene change cross-fades every parameter over several seconds,
 * so the room's mood drifts instead of switching.
 */
final class Room {
    // ---- scene ids
    static final int NORMAL_ROOM = 0, MORNING = 1, SUNNY = 2, CLOUDY = 3, RAIN = 4, SUNSET = 5, NIGHT = 6,
            STARRY_NIGHT = 7, SNOW = 8, COZY_NIGHT = 9, SCENE_COUNT = 10;
    static final String[] SCENE_NAMES = { "SCENE_NORMAL_ROOM", "SCENE_MORNING", "SCENE_SUNNY", "SCENE_CLOUDY", "SCENE_RAIN",
            "SCENE_SUNSET", "SCENE_NIGHT", "SCENE_STARRY_NIGHT", "SCENE_SNOW", "SCENE_COZY_NIGHT" };
    static final float CROSSFADE_S = 6f;

    /** One scene = one set of mood parameters. */
    static final class Look {
        int top, bot, tint, shaftC; float tintA, lamp, clouds, cloudDark, rain, snow, stars, sunA, sunX, sunY, moonA, moonY, shaftA;
        Look(int top, int bot, int tint, float tintA, float lamp, float clouds, float cloudDark, float rain, float snow, float stars,
             float sunA, float sunX, float sunY, float moonA, float moonY, float shaftA, int shaftC) {
            this.top = top; this.bot = bot; this.tint = tint; this.tintA = tintA; this.lamp = lamp; this.clouds = clouds;
            this.cloudDark = cloudDark; this.rain = rain; this.snow = snow; this.stars = stars; this.sunA = sunA; this.sunX = sunX;
            this.sunY = sunY; this.moonA = moonA; this.moonY = moonY; this.shaftA = shaftA; this.shaftC = shaftC;
        }
        Look() { this(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, .5f, .5f, 0, .5f, 0, 0); }
    }

    static final Look[] LOOKS = new Look[SCENE_COUNT];
    static {
        //                       top         bottom      tint        tA   lamp cld  dark rain snow star sun  sX   sY   moon mY   shaft shaftC
        LOOKS[NORMAL_ROOM]  = new Look(0xFF8FD0F5, 0xFFD6F0FB, 0xFFFFF4D0, .02f, 0f, .30f, 0f, 0f, 0f, 0f, 1f, .72f, .26f, 0f, .3f, .06f, 0xFFFFF3C4);
        LOOKS[MORNING]      = new Look(0xFFA9D6F2, 0xFFFFD9B0, 0xFFFFD8A0, .07f, 0f, .18f, 0f, 0f, 0f, 0f, 1f, .22f, .80f, 0f, .3f, .17f, 0xFFFFE2A8);
        LOOKS[SUNNY]        = new Look(0xFF5EC0F2, 0xFFC8EEFF, 0xFFFFF4C0, .03f, 0f, .22f, 0f, 0f, 0f, 0f, 1f, .62f, .22f, 0f, .3f, .14f, 0xFFFFF0B0);
        LOOKS[CLOUDY]       = new Look(0xFFA9B6C2, 0xFFD3DBE2, 0xFF7E8C99, .10f, 0f, 1f, .15f, 0f, 0f, 0f, 0f, .5f, .3f, 0f, .3f, 0f, 0xFFFFFFFF);
        LOOKS[RAIN]         = new Look(0xFF7A8794, 0xFFA9B5BF, 0xFF3E4C60, .20f, 0f, 1f, .55f, 1f, 0f, 0f, 0f, .5f, .3f, 0f, .3f, 0f, 0xFFFFFFFF);
        LOOKS[SUNSET]       = new Look(0xFFE8826A, 0xFFFFD08A, 0xFFFF9A4A, .16f, .15f, .35f, 0f, 0f, 0f, 0f, 1f, .78f, .84f, 0f, .3f, .20f, 0xFFFFB45A);
        LOOKS[NIGHT]        = new Look(0xFF14213D, 0xFF2B3F66, 0xFF1A2A70, .40f, .70f, .15f, .75f, 0f, 0f, .5f, 0f, .5f, .3f, 1f, .30f, .08f, 0xFFB8C8FF);
        LOOKS[STARRY_NIGHT] = new Look(0xFF0A1330, 0xFF1E2F5C, 0xFF111F66, .44f, .55f, 0f, 0f, 0f, 0f, 1f, 0f, .5f, .3f, 1f, .22f, .08f, 0xFFB8C8FF);
        LOOKS[SNOW]         = new Look(0xFFB9C7D6, 0xFFE6EEF5, 0xFFB8D0F0, .12f, 0f, .80f, .05f, 0f, 1f, 0f, 0f, .5f, .3f, 0f, .3f, .05f, 0xFFFFFFFF);
        LOOKS[COZY_NIGHT]   = new Look(0xFF1B1730, 0xFF3A2A4A, 0xFF2B1660, .34f, 1f, .10f, .8f, 0f, 0f, .45f, 0f, .5f, .3f, 1f, .34f, .06f, 0xFFFFD8A0);
    }

    // ---- geometry
    int w, h; float ballX = -1f;      // the toy ball (moved by Decor)
    static final float FOOD_X = .40f, WATER_X = .60f;   // the two bowls stand in the foreground (closer to the viewer than the pets)
    float bowlY;                                        // floor line of the bowls (below / in front of the pets' feet)
    // ---- v0.13: room theme (模様替え), thunder, light
    static final int[][] WALLS = { { 0xFFF6E4D4, 0xFFE9D3BF }, { 0xFFDDF0E6, 0xFFCBE3D6 }, { 0xFFDCEBF7, 0xFFC9DCEC }, { 0xFFE9E1F4, 0xFFD9CFEA }, { 0xFFFBE0D2, 0xFFF0CDBB } };
    static final int[][] FLOORS = { { 0xFFCF9C69, 0xFFE6BB8C }, { 0xFF8F6142, 0xFFA9785A }, { 0xFFD9C3A3, 0xFFEBDCC4 }, { 0xFF9FA3A8, 0xFFBFC3C7 } };
    static final int[][] RUGS = { { 0xFFF3B7B0, 0xFFFAD2C9 }, { 0xFFA9CBEA, 0xFFCFE2F4 }, { 0xFFB9DDB0, 0xFFD7EDCF }, { 0xFFF3D98B, 0xFFFAE8B6 } };
    static final String[] WALL_NAMES = { "クリーム", "ミント", "そらいろ", "ラベンダー", "ピーチ" }, FLOOR_NAMES = { "はちみつ", "ウォルナット", "ホワイトオーク", "グレー" }, RUG_NAMES = { "ピンク", "ブルー", "グリーン", "イエロー" };
    private int wallC = 0xFFF6E4D4, sideC = 0xFFE9D3BF, floorA = 0xFFCF9C69, floorB = 0xFFE6BB8C, rugA = 0xFFF3B7B0, rugB = 0xFFFAD2C9;
    int themeKey = -1;
    /** Choose wall / floor / rug colours (takes effect on the next layout). */
    void setTheme(int wall, int floor, int rug) {
        wall = Math.max(0, Math.min(WALLS.length - 1, wall)); floor = Math.max(0, Math.min(FLOORS.length - 1, floor)); rug = Math.max(0, Math.min(RUGS.length - 1, rug));
        wallC = WALLS[wall][0]; sideC = WALLS[wall][1]; floorA = FLOORS[floor][0]; floorB = FLOORS[floor][1]; rugA = RUGS[rug][0]; rugB = RUGS[rug][1];
        themeKey = wall * 100 + floor * 10 + rug;
    }
    float flash; boolean thunderEvent; private boolean flash2; private float thunderClock = 25f;
    private RadialGradient poolGlow; private final Matrix poolM = new Matrix();
    float winL, winT, winR, winB, groundY, wallBottom, lampX, lampY, clockX, clockY, clockR, chargerX, chargerY, bedX, cushionX;
    private final RectF rfA = new RectF(), rfB = new RectF(), rfC = new RectF();
    private Bitmap furniture;
    private RadialGradient lampGlow;
    private final Matrix lampM = new Matrix();

    // ---- state
    final Look cur = new Look();
    private int fromScene = NORMAL_ROOM, toScene = NORMAL_ROOM;
    private float blend = 1f, sceneClock = 0f, nextChangeS = 1800f, checkClock = 0f, time = 0f;
    private int bucket = -1, forced = -2;
    private final Random rnd = new Random();
    boolean charging;
    private float chargePulse;
    private int clockMin = -1; private float clockHx, clockHy, clockMx, clockMy;

    // ---- weather particles (fixed pools)
    private static final int NR = 36, NS = 44, NSTAR = 22, NCLOUD = 4;
    private final float[] rx = new float[NR], ry = new float[NR], rv = new float[NR];
    private final float[] sx = new float[NS], sy = new float[NS], sv = new float[NS], sp = new float[NS], sz = new float[NS];
    private final float[] stx = new float[NSTAR], sty = new float[NSTAR], stp = new float[NSTAR], stz = new float[NSTAR];
    private final float[] cx = new float[NCLOUD], cy = new float[NCLOUD], cs = new float[NCLOUD], cv = new float[NCLOUD];

    // ---- paints
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    // ================================================================================== layout
    void layout(int ww, int hh) { layout(ww, hh, 5); }

    /** floorStep 0..10 moves the whole floor line (and so the furniture row and the penguin) up or down; 5 = 70 % of the screen height. */
    void layout(int ww, int hh, int floorStep) {
        w = ww; h = hh;
        winL = w * .10f; winR = w * .52f; winT = h * .115f; winB = h * .345f;
        groundY = h * (.60f + .02f * Math.max(0, Math.min(10, floorStep))); wallBottom = groundY - h * .11f;
        bowlY = Math.min(h * .87f, groundY + h * .095f);
        lampX = w * .935f; lampY = groundY - h * .34f; bedX = w * .80f; cushionX = w * .33f;
        clockX = w * .76f; clockY = h * .135f; clockR = w * .052f;
        chargerX = w * .18f; chargerY = groundY;
        for (int i = 0; i < NR; i++) { rx[i] = rnd.nextFloat(); ry[i] = rnd.nextFloat(); rv[i] = .8f + rnd.nextFloat() * .6f; }
        for (int i = 0; i < NS; i++) { sx[i] = rnd.nextFloat(); sy[i] = rnd.nextFloat(); sv[i] = .05f + rnd.nextFloat() * .06f; sp[i] = rnd.nextFloat() * 6.28f; sz[i] = .6f + rnd.nextFloat() * .7f; }
        for (int i = 0; i < NSTAR; i++) { stx[i] = .06f + rnd.nextFloat() * .88f; sty[i] = .05f + rnd.nextFloat() * .7f; stp[i] = rnd.nextFloat() * 6.28f; stz[i] = .5f + rnd.nextFloat(); }
        for (int i = 0; i < NCLOUD; i++) { cx[i] = rnd.nextFloat(); cy[i] = .12f + rnd.nextFloat() * .45f; cs[i] = .7f + rnd.nextFloat() * .6f; cv[i] = .004f + rnd.nextFloat() * .006f; }
        if (furniture != null) furniture.recycle();
        furniture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        buildFurniture(new Canvas(furniture));
        float gr = w * .55f;
        lampGlow = new RadialGradient(0f, 0f, gr, new int[] { 0xCCFFE2A0, 0x55FFC870, 0x00FFB050 }, new float[] { 0f, .45f, 1f }, Shader.TileMode.CLAMP);
        poolGlow = new RadialGradient(0f, 0f, w * .3f, new int[] { 0x70FFDB94, 0x30FFC870, 0x00FFB050 }, new float[] { 0f, .5f, 1f }, Shader.TileMode.CLAMP);
    }

    void release() { if (furniture != null) { furniture.recycle(); furniture = null; } }

    /** Where (as a fraction of screen width) the penguin goes for each goal. */
    float goalX(int goal) {
        switch (goal) {
            case Seqs.G_WINDOW: return .31f;
            case Seqs.G_BED: return .80f;
            case Seqs.G_FOOD: return FOOD_X;
            case Seqs.G_WATER: return WATER_X;
            case Seqs.G_CUSHION: return .33f;
            case Seqs.G_CHARGER: return .19f;
            case Seqs.G_BALL: return w > 0 ? ballX / w : .445f;
            default: return .5f;
        }
    }
    /** -1 / +1: which way the clock / window lies relative to x. */
    float clockDir(float px) { return clockX > px ? 1f : -1f; }
    float windowDir(float px) { float c = (winL + winR) * .5f; return Math.abs(c - px) < w * .06f ? 0f : (c > px ? 1f : -1f); }

    int weather() {
        Look b = blend > .5f ? LOOKS[toScene] : LOOKS[fromScene];
        if (b.rain > .5f) return 1; if (b.snow > .5f) return 2; if (b.moonA > .5f && b.stars > .4f) return 3; return 0;
    }
    boolean night() { Look b = blend > .5f ? LOOKS[toScene] : LOOKS[fromScene]; return b.moonA > .5f; }
    boolean animating() { return cur.rain > .02f || cur.snow > .02f || cur.clouds > .02f || cur.stars > .02f || blend < 1f || cur.lamp > .02f || charging; }
    boolean fastAnimating() { return cur.rain > .1f || cur.snow > .1f; }
    int sceneId() { return blend > .5f ? toScene : fromScene; }

    // ================================================================================== scene logic
    private static int bucketOf(int hour) { return hour >= 23 || hour < 5 ? 0 : hour < 9 ? 1 : hour < 16 ? 2 : hour < 19 ? 3 : 4; }

    int pick(int hour, int month, int avoid) {
        final int b = bucketOf(hour);
        final boolean winter = month == 11 || month <= 1;      // Dec, Jan, Feb (0-based month)
        int[] ids; int[] wt;
        switch (b) {
            case 0:  ids = new int[] { COZY_NIGHT, STARRY_NIGHT, NIGHT, SNOW };        wt = new int[] { 50, 30, 20, winter ? 14 : 0 }; break;
            case 1:  ids = new int[] { MORNING, SUNNY, CLOUDY, NORMAL_ROOM, SNOW };    wt = new int[] { 50, 22, 12, 10, winter ? 14 : 0 }; break;
            case 2:  ids = new int[] { SUNNY, NORMAL_ROOM, CLOUDY, RAIN, SNOW };       wt = new int[] { 34, 22, 22, 16, winter ? 16 : 0 }; break;
            case 3:  ids = new int[] { SUNSET, CLOUDY, RAIN, NORMAL_ROOM };            wt = new int[] { 60, 14, 12, 8 }; break;
            default: ids = new int[] { NIGHT, STARRY_NIGHT, COZY_NIGHT, SNOW, RAIN };  wt = new int[] { 28, 28, 30, winter ? 14 : 0, 10 }; break;
        }
        int total = 0; for (int i = 0; i < ids.length; i++) total += ids[i] == avoid ? 0 : wt[i];
        if (total <= 0) return ids[0];
        int r = rnd.nextInt(total);
        for (int i = 0; i < ids.length; i++) { int v = ids[i] == avoid ? 0 : wt[i]; if (r < v) return ids[i]; r -= v; }
        return ids[0];
    }

    private void startFade(int to) {
        if (to == toScene && blend >= 1f) return;
        // bake the in-between look into "from" so a new fade never jumps
        fromScene = blend > .5f ? toScene : fromScene;
        toScene = to; blend = 0f;
    }

    /** Called when the wallpaper becomes visible: snap to a sensible scene for this hour on first launch only. */
    void first(int hour, int month, int forcedScene) {
        forced = forcedScene;
        int s = forcedScene >= 0 ? forcedScene : pick(hour, month, -1);
        fromScene = toScene = s; blend = 1f; bucket = bucketOf(hour); sceneClock = 0f; nextChangeS = 1500f + rnd.nextInt(1800);
        blendLooks();
    }

    /** Called ~once per second from the engine with a real clock reading. */
    void tick(int hour, int month, int forcedScene) {
        if (forcedScene != forced) { forced = forcedScene; if (forced >= 0) startFade(forced); else startFade(pick(hour, month, toScene)); sceneClock = 0f; return; }
        if (forced >= 0) return;
        int b = bucketOf(hour);
        if (b != bucket) { bucket = b; startFade(pick(hour, month, toScene)); sceneClock = 0f; nextChangeS = 1500f + rnd.nextInt(1800); }
    }

    void update(float dt) {
        time += dt; sceneClock += dt; checkClock += dt;
        if (blend < 1f) { blend = Math.min(1f, blend + dt / CROSSFADE_S); }
        else if (forced < 0 && sceneClock > nextChangeS) {       // slow drift inside the same time-of-day bucket
            sceneClock = 0f; nextChangeS = 1500f + rnd.nextInt(1800);
            startFade(pick(bucketHourGuess(), 5, toScene));
        }
        blendLooks();
        if (cur.rain > .02f) for (int i = 0; i < NR; i++) { ry[i] += rv[i] * dt * 1.6f; rx[i] -= dt * .12f; if (ry[i] > 1f) { ry[i] -= 1f; rx[i] = rnd.nextFloat() * 1.2f; } if (rx[i] < 0f) rx[i] += 1f; }
        if (cur.snow > .02f) for (int i = 0; i < NS; i++) { sy[i] += sv[i] * dt; sp[i] += dt * 1.1f; if (sy[i] > 1f) { sy[i] -= 1f; sx[i] = rnd.nextFloat(); } }
        if (cur.clouds > .02f) for (int i = 0; i < NCLOUD; i++) { cx[i] += cv[i] * dt; if (cx[i] > 1.3f) cx[i] = -.3f; }
        if (charging) chargePulse += dt;
        // thunder: a double flash now and then while it rains
        if (cur.rain > .5f) { thunderClock -= dt; if (thunderClock <= 0f) { thunderClock = 35f + rnd.nextFloat() * 45f; flash = 1f; flash2 = true; thunderEvent = true; } }
        if (flash > 0f) { flash = Math.max(0f, flash - dt * 2.4f); if (flash2 && flash <= .5f) { flash2 = false; flash = .85f; } }   // flash, dim, second flash
    }
    private int bucketHourGuess() { return bucket == 0 ? 0 : bucket == 1 ? 7 : bucket == 2 ? 12 : bucket == 3 ? 17 : 21; }

    private static int lerpC(int a, int b, float t) {
        return Color.argb(255,
            (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t), (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
            (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
    private static float lf(float a, float b, float t) { return a + (b - a) * t; }

    private void blendLooks() {
        final Look a = LOOKS[fromScene], b = LOOKS[toScene]; final float t = Rig.sm(blend);
        cur.top = lerpC(a.top, b.top, t); cur.bot = lerpC(a.bot, b.bot, t); cur.tint = lerpC(a.tint, b.tint, t); cur.shaftC = lerpC(a.shaftC, b.shaftC, t);
        cur.tintA = lf(a.tintA, b.tintA, t); cur.lamp = lf(a.lamp, b.lamp, t); cur.clouds = lf(a.clouds, b.clouds, t);
        cur.cloudDark = lf(a.cloudDark, b.cloudDark, t); cur.rain = lf(a.rain, b.rain, t); cur.snow = lf(a.snow, b.snow, t);
        cur.stars = lf(a.stars, b.stars, t); cur.sunA = lf(a.sunA, b.sunA, t); cur.sunX = lf(a.sunX, b.sunX, t); cur.sunY = lf(a.sunY, b.sunY, t);
        cur.moonA = lf(a.moonA, b.moonA, t); cur.moonY = lf(a.moonY, b.moonY, t); cur.shaftA = lf(a.shaftA, b.shaftA, t);
    }

    // ================================================================================== static furniture bitmap
    private void rr(Canvas c, float l, float t, float r, float b, float rad, int col) { p.setColor(col); rf.set(l, t, r, b); c.drawRoundRect(rf, rad, rad, p); }
    private void ov(Canvas c, float l, float t, float r, float b, int col) { p.setColor(col); rf.set(l, t, r, b); c.drawOval(rf, p); }

    private void buildFurniture(Canvas c) {
        p.setStyle(Paint.Style.FILL); p.setShader(null); p.setXfermode(null);
        // wall
        p.setColor(wallC); c.drawRect(0, 0, w, wallBottom, p);
        p.setColor(0x14C98B6B);
        for (float x = w * .05f; x < w; x += w * .125f) c.drawRect(x, 0, x + w * .05f, wallBottom, p);      // soft stripes
        // wall shadow near ceiling
        p.setColor(0x10000000); c.drawRect(0, 0, w, h * .012f, p);
        // floor in soft perspective: darker at the back, boards run towards a vanishing point above the room,
        // cross seams get wider towards the viewer
        band(c, floorA, floorB, 0, wallBottom, w, h);
        p.setColor(0x26A06B3C); p.setStrokeWidth(Math.max(2f, w * .003f));
        final float vpx = w * .5f, vpy = wallBottom - h * .55f, spread = (h - vpy) / (wallBottom - vpy);
        for (int i = -7; i <= 7; i++) { final float xb = vpx + i * w * .085f; c.drawLine(xb, wallBottom, vpx + (xb - vpx) * spread, h, p); }
        final int rows = 7; float prevY = wallBottom;
        for (int j = 1; j <= rows; j++) {
            final float yj = wallBottom + (h - wallBottom) * (float) Math.pow(j / (float) rows, 1.45f);
            p.setColor(0x1FA06B3C); c.drawLine(0, yj, w, yj, p);
            // staggered board ends between this seam and the previous one
            final float kk = ((prevY + yj) * .5f - vpy) / (wallBottom - vpy);
            for (int i = -7; i <= 7; i += 2) { final float xb = vpx + (i + (j % 2)) * w * .085f * kk + w * .0425f * kk; c.drawLine(xb, prevY, xb, yj, p); }
            // a few soft grain streaks inside each board row
            final float rowH = yj - prevY;
            p.setColor(0x14804A22);
            for (int i = -7; i <= 7; i++) {
                final int hsh = (i * 73 + j * 31) & 7;
                if (hsh > 4) continue;
                final float kk2 = ((prevY + yj) * .5f - vpy) / (wallBottom - vpy);
                final float gx = vpx + (i + .3f + hsh * .08f) * w * .085f * kk2, gy = prevY + rowH * (.3f + hsh * .1f);
                rf.set(gx - w * .03f * kk2, gy - rowH * .06f, gx + w * .03f * kk2, gy + rowH * .06f); c.drawOval(rf, p);
            }
            p.setColor(0x1FA06B3C);
            prevY = yj;
        }
        // contact shadow along the wall and a soft darkening towards the screen edges (gives the room some depth)
        q.setShader(new android.graphics.LinearGradient(0, wallBottom, 0, wallBottom + h * .05f, 0x30000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, wallBottom, w, wallBottom + h * .05f, q);
        q.setShader(new android.graphics.LinearGradient(0, 0, w * .12f, 0, 0x22000000, 0x00000000, Shader.TileMode.CLAMP)); c.drawRect(0, wallBottom, w * .12f, h, q);
        q.setShader(new android.graphics.LinearGradient(w, 0, w * .88f, 0, 0x22000000, 0x00000000, Shader.TileMode.CLAMP)); c.drawRect(w * .88f, wallBottom, w, h, q);
        q.setShader(null);
        // baseboard
        p.setColor(0xFFFFF7EA); c.drawRect(0, wallBottom - h * .014f, w, wallBottom + h * .004f, p);
        p.setColor(0x22000000); c.drawRect(0, wallBottom + h * .004f, w, wallBottom + h * .012f, p);
        // side walls: the back wall ends a little before the screen edges, the strips outside are the room's side walls
        // seen in perspective (darker, with their floor edge running towards the viewer)
        final float cl = w * .045f, cr = w - cl, ex = vpx + (cl - vpx) * spread, exr = vpx + (cr - vpx) * spread;
        path.reset(); path.moveTo(0, 0); path.lineTo(cl, 0); path.lineTo(cl, wallBottom); path.lineTo(ex, h); path.lineTo(0, h); path.close();
        p.setColor(sideC); c.drawPath(path, p);
        path.reset(); path.moveTo(w, 0); path.lineTo(cr, 0); path.lineTo(cr, wallBottom); path.lineTo(exr, h); path.lineTo(w, h); path.close();
        c.drawPath(path, p);
        p.setColor(0x18000000); p.setStrokeWidth(Math.max(2f, w * .003f));
        c.drawLine(cl, 0, cl, wallBottom, p); c.drawLine(cr, 0, cr, wallBottom, p);
        p.setColor(0x30A06B3C); c.drawLine(cl, wallBottom, ex, h, p); c.drawLine(cr, wallBottom, exr, h, p);
        // rug
        ov(c, w * .14f, groundY - h * .03f, w * .86f, groundY + h * .10f, rugA);
        ov(c, w * .19f, groundY - h * .015f, w * .81f, groundY + h * .085f, rugB);
        ov(c, w * .26f, groundY, w * .74f, groundY + h * .07f, rugA);

        // ---- window
        rr(c, winL - w * .018f, winT - w * .018f, winR + w * .018f, winB + w * .018f, w * .02f, 0xFFFFFFFF);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        rr(c, winL, winT, winR, winB, w * .008f, 0x00000000);
        p.setXfermode(null);
        p.setColor(0xFFFFFFFF); final float bar = Math.max(6f, w * .011f);
        c.drawRect((winL + winR) / 2 - bar / 2, winT, (winL + winR) / 2 + bar / 2, winB, p);
        c.drawRect(winL, (winT + winB) / 2 - bar / 2, winR, (winT + winB) / 2 + bar / 2, p);
        rr(c, winL - w * .03f, winB + w * .012f, winR + w * .03f, winB + w * .036f, w * .01f, 0xFFF4E8D8);       // sill
        p.setColor(0x20000000); c.drawRect(winL - w * .03f, winB + w * .036f, winR + w * .03f, winB + w * .046f, p);
        // sill items: tiny pot with sprout
        rr(c, winR - w * .11f, winB - w * .035f, winR - w * .07f, winB + w * .012f, w * .008f, 0xFFD98A5B);
        ov(c, winR - w * .105f, winB - w * .085f, winR - w * .09f, winB - w * .03f, 0xFF6FBF73);
        ov(c, winR - w * .09f, winB - w * .075f, winR - w * .07f, winB - w * .03f, 0xFF86D08A);

        // ---- clock frame (hands are dynamic)
        p.setColor(0x26000000); c.drawCircle(clockX + w * .004f, clockY + w * .006f, clockR + w * .008f, p);
        p.setColor(0xFFB5805A); c.drawCircle(clockX, clockY, clockR + w * .01f, p);
        p.setColor(0xFFFFFDF6); c.drawCircle(clockX, clockY, clockR, p);
        p.setColor(0xFF6B5B55);
        for (int i = 0; i < 12; i++) { double a = i * Math.PI / 6; c.drawCircle(clockX + (float) Math.sin(a) * clockR * .82f, clockY - (float) Math.cos(a) * clockR * .82f, w * .0035f, p); }

        // ---- shelf with plant, books and photo
        final float sl = w * .56f, sr = w * .90f, sy = h * .295f;
        rr(c, sl, sy, sr, sy + h * .009f, h * .004f, 0xFFB5805A);
        p.setColor(0xFF9A6A47); c.drawRect(sl + w * .03f, sy + h * .009f, sl + w * .037f, sy + h * .03f, p); c.drawRect(sr - w * .037f, sy + h * .009f, sr - w * .03f, sy + h * .03f, p);
        // plant
        rr(c, sl + w * .27f, sy - h * .036f, sl + w * .335f, sy, w * .01f, 0xFFD98A5B);
        rr(c, sl + w * .265f, sy - h * .042f, sl + w * .34f, sy - h * .033f, w * .008f, 0xFFE59E70);
        final int[] gc = { 0xFF5FAF69, 0xFF77C47B, 0xFF4F9E5C };
        for (int i = 0; i < 5; i++) { float a = -70f + i * 35f; double ar = Math.toRadians(a); float lx = sl + w * .30f + (float) Math.sin(ar) * w * .035f, ly = sy - h * .075f - (float) Math.cos(ar) * h * .02f;
            c.save(); c.rotate(a, lx, ly); ov(c, lx - w * .018f, ly - h * .03f, lx + w * .018f, ly + h * .02f, gc[i % 3]); c.restore(); }
        // books
        rr(c, sl + w * .045f, sy - h * .026f, sl + w * .125f, sy, w * .004f, 0xFF8EC5E8);
        rr(c, sl + w * .05f, sy - h * .05f, sl + w * .12f, sy - h * .026f, w * .004f, 0xFFF4A6B0);
        rr(c, sl + w * .055f, sy - h * .071f, sl + w * .115f, sy - h * .05f, w * .004f, 0xFFFFD27A);
        // little photo frame
        rr(c, sl + w * .145f, sy - h * .052f, sl + w * .205f, sy, w * .006f, 0xFFFFFFFF);
        rr(c, sl + w * .152f, sy - h * .045f, sl + w * .198f, sy - h * .007f, w * .004f, 0xFFBFE3F2);
        p.setColor(0xFF3C3E45); c.drawCircle(sl + w * .175f, sy - h * .028f, w * .011f, p);
        p.setColor(0xFFFFF8E7); c.drawCircle(sl + w * .175f, sy - h * .022f, w * .007f, p);

        // ---- charging spot: wall outlet + floor pad
        rr(c, chargerX - w * .03f, wallBottom - h * .095f, chargerX + w * .03f, wallBottom - h * .05f, w * .008f, 0xFFFFFFFF);
        p.setColor(0xFF6B6F78); c.drawRect(chargerX - w * .012f, wallBottom - h * .083f, chargerX - w * .007f, wallBottom - h * .067f, p); c.drawRect(chargerX + w * .007f, wallBottom - h * .083f, chargerX + w * .012f, wallBottom - h * .067f, p);
        ov(c, chargerX - w * .1f, groundY - h * .014f, chargerX + w * .1f, groundY + h * .022f, 0xFF8C93A1);
        ov(c, chargerX - w * .085f, groundY - h * .011f, chargerX + w * .085f, groundY + h * .016f, 0xFFB7BFCC);
        path.reset(); float bx = chargerX, by = groundY + h * .003f, bs = w * .02f;
        path.moveTo(bx + bs * .3f, by - bs * .9f); path.lineTo(bx - bs * .55f, by + bs * .1f); path.lineTo(bx - bs * .05f, by + bs * .1f); path.lineTo(bx - bs * .3f, by + bs * .9f);
        path.lineTo(bx + bs * .55f, by - bs * .1f); path.lineTo(bx + bs * .05f, by - bs * .1f); path.close();
        p.setColor(0xFFFFE04A); c.drawPath(path, p);

        // ---- cushion under the window
        final float cu = cushionX;
        ov(c, cu - w * .13f, groundY - h * .004f, cu + w * .13f, groundY + h * .034f, 0x26000000);
        ov(c, cu - w * .12f, groundY - h * .03f, cu + w * .12f, groundY + h * .026f, 0xFFF59AB4);
        ov(c, cu - w * .1f, groundY - h * .034f, cu + w * .1f, groundY + h * .012f, 0xFFFFB6CB);
        p.setColor(0xFFE4809C); c.drawCircle(cu, groundY - h * .01f, w * .008f, p);

        // (the bowls are drawn in front of the pets every frame: Decor.drawBowls)

        // ---- pet bed (right)
        final float bd = bedX;
        ov(c, bd - w * .17f, groundY - h * .016f, bd + w * .17f, groundY + h * .043f, 0x26000000);
        ov(c, bd - w * .16f, groundY - h * .045f, bd + w * .16f, groundY + h * .036f, 0xFFB98660);
        ov(c, bd - w * .14f, groundY - h * .05f, bd + w * .14f, groundY + h * .016f, 0xFFA77450);
        ov(c, bd - w * .125f, groundY - h * .044f, bd + w * .125f, groundY + h * .008f, 0xFFFFE9BF);
        ov(c, bd - w * .08f, groundY - h * .038f, bd + w * .08f, groundY + h * .0f, 0xFFFFF3D6);

        // ---- floor lamp
        rr(c, lampX - w * .0075f, lampY + h * .06f, lampX + w * .0075f, groundY + h * .02f, w * .004f, 0xFF7A5B49);
        ov(c, lampX - w * .045f, groundY + h * .008f, lampX + w * .045f, groundY + h * .034f, 0xFF7A5B49);
        path.reset(); path.moveTo(lampX - w * .04f, lampY + h * .07f); path.lineTo(lampX + w * .04f, lampY + h * .07f); path.lineTo(lampX + w * .026f, lampY - h * .005f); path.lineTo(lampX - w * .026f, lampY - h * .005f); path.close();
        p.setColor(0xFFFFE3A6); c.drawPath(path, p);
        p.setColor(0x26FFFFFF); c.drawRect(lampX - w * .036f, lampY + h * .01f, lampX + w * .036f, lampY + h * .02f, p);
        // wall hook: small frame with penguin footprint
        rr(c, w * .06f, h * .22f, w * .15f, h * .292f, w * .008f, 0xFFFFFFFF);
        rr(c, w * .067f, h * .2275f, w * .143f, h * .2845f, w * .005f, 0xFFD6EEF7);
        p.setColor(0xFF6F7C95); c.drawCircle(w * .105f, h * .2605f, w * .0105f, p);
        for (int i = -1; i <= 1; i++) c.drawCircle(w * .105f + i * w * .0135f, h * .2465f, w * .0042f, p);
    }

    // ================================================================================== per-frame drawing
    private void band(Canvas c, int top, int bot, float l, float t, float r, float b) {
        final int n = 14; final float bh = (b - t) / n;
        for (int i = 0; i < n; i++) { p.setColor(lerpC(top, bot, i / (float) (n - 1))); c.drawRect(l, t + bh * i, r, t + bh * (i + 1) + 1f, p); }
    }

    /** Sky + weather (behind the furniture bitmap, which has a transparent window pane). */
    void drawBack(Canvas c, int hour, int minute) {
        p.setStyle(Paint.Style.FILL); p.setShader(null);
        c.drawColor(wallC);
        final Look k = cur; final float pw = winR - winL, ph = winB - winT;
        c.save(); c.clipRect(winL, winT, winR, winB);
        band(c, k.top, k.bot, winL, winT, winR, winB);
        // stars
        if (k.stars > .02f) for (int i = 0; i < NSTAR; i++) {
            float tw = .55f + .45f * Rig.sin(time * 1.7f + stp[i]);
            p.setColor(0xFFFFFFFF); p.setAlpha((int) (255 * k.stars * tw * Math.min(1f, k.stars * 1.5f)));
            float sx0 = winL + stx[i] * pw, sy0 = winT + sty[i] * ph, r = w * .0045f * stz[i];
            if (i % 3 == 0) { c.drawRect(sx0 - r * 2.2f, sy0 - r * .35f, sx0 + r * 2.2f, sy0 + r * .35f, p); c.drawRect(sx0 - r * .35f, sy0 - r * 2.2f, sx0 + r * .35f, sy0 + r * 2.2f, p); }
            else c.drawCircle(sx0, sy0, r, p);
        }
        p.setAlpha(255);
        // sun
        if (k.sunA > .02f) {
            final float sx0 = winL + k.sunX * pw, sy0 = winT + k.sunY * ph, r = w * .05f;
            p.setColor(0xFFFFE9A0); p.setAlpha((int) (70 * k.sunA)); c.drawCircle(sx0, sy0, r * 1.9f, p);
            p.setAlpha((int) (255 * k.sunA)); p.setColor(0xFFFFD84D); c.drawCircle(sx0, sy0, r, p);
            p.setAlpha(255);
        }
        // moon (crescent)
        if (k.moonA > .02f) {
            final float mx = winL + pw * .72f, my = winT + k.moonY * ph, r = w * .042f;
            p.setColor(0xFFFFF4C8); p.setAlpha((int) (45 * k.moonA)); c.drawCircle(mx, my, r * 1.8f, p);
            p.setAlpha((int) (255 * k.moonA)); p.setColor(0xFFFFF4C8); c.drawCircle(mx, my, r, p);
            p.setColor(lerpC(k.top, k.bot, .35f)); p.setAlpha((int) (255 * k.moonA)); c.drawCircle(mx + r * .5f, my - r * .22f, r * .9f, p);
            p.setAlpha(255);
        }
        // birds crossing by day (a small flock every now and then)
        if (k.sunA > .5f && k.rain < .1f) {
            final float cyc = (time % 38f) / 9f;                     // crosses during the first 9 s of every 38 s
            if (cyc < 1f) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, w * .0035f)); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(0xFF4A5566);
                for (int b = 0; b < 3; b++) {
                    final float bx = winL + (cyc * 1.3f - .15f - b * .07f) * pw, by = winT + ph * (.32f + b * .06f) + (float) Math.sin(time * 3f + b) * ph * .02f;
                    final float fl = (float) Math.sin(time * 14f + b * 1.3f) * w * .006f, s = w * .012f;
                    path.reset(); path.moveTo(bx - s, by - fl); path.quadTo(bx - s * .4f, by - s * .5f, bx, by); path.quadTo(bx + s * .4f, by - s * .5f, bx + s, by - fl); c.drawPath(path, p);
                }
                p.setStyle(Paint.Style.FILL);
            }
        }
        // shooting star at night
        if (k.stars > .3f) {
            final float cyc = (time % 23f) / 1.1f;
            if (cyc < 1f) {
                final float sx0 = winL + pw * (.85f - .6f * cyc), sy0 = winT + ph * (.12f + .35f * cyc);
                p.setColor(0xFFFFFFFF); p.setStrokeWidth(Math.max(2f, w * .004f)); p.setStrokeCap(Paint.Cap.ROUND);
                p.setAlpha((int) (230 * Math.sin(Math.PI * cyc) * k.stars));
                c.drawLine(sx0, sy0, sx0 + pw * .12f, sy0 - ph * .07f, p);
                p.setAlpha(255);
            }
        }
        // clouds
        if (k.clouds > .02f) {
            final int cc = lerpC(lerpC(0xFFFFFFFF, 0xFFFFC9A0, k.sunY > .6f && k.sunA > .5f ? .7f : 0f), 0xFF6C7683, k.cloudDark);   // sunset: peachy clouds
            for (int i = 0; i < NCLOUD; i++) {
                if (i >= 1 + (int) (k.clouds * 3.01f)) break;
                final float x0 = winL + cx[i] * pw, y0 = winT + cy[i] * ph, s = w * .045f * cs[i];
                p.setColor(cc); p.setAlpha((int) (235 * Math.min(1f, k.clouds * 1.6f)));
                c.drawCircle(x0, y0, s, p); c.drawCircle(x0 + s * 1.1f, y0 + s * .15f, s * .8f, p); c.drawCircle(x0 - s * 1.0f, y0 + s * .25f, s * .7f, p);
                rf.set(x0 - s * 1.7f, y0 + s * .1f, x0 + s * 1.9f, y0 + s * .95f); c.drawRoundRect(rf, s * .45f, s * .45f, p);
            }
            p.setAlpha(255);
        }
        // rain
        if (k.rain > .02f) {
            p.setColor(0xFFDCEBFA); p.setStrokeWidth(Math.max(2f, w * .0028f)); p.setStrokeCap(Paint.Cap.ROUND);
            p.setAlpha((int) (200 * k.rain));
            for (int i = 0; i < NR; i++) { float x0 = winL + rx[i] * pw, y0 = winT + ry[i] * ph; c.drawLine(x0, y0, x0 - w * .006f, y0 + w * .028f, p); }
            p.setAlpha(255);
        }
        // snow
        if (k.snow > .02f) {
            p.setColor(0xFFFFFFFF); p.setAlpha((int) (240 * k.snow));
            for (int i = 0; i < NS; i++) { float x0 = winL + (sx[i] + .025f * Rig.sin(sp[i])) * pw, y0 = winT + sy[i] * ph; c.drawCircle(x0, y0, w * .0058f * sz[i], p); }
            p.setAlpha(255);
        }
        c.restore();
        // furniture
        if (furniture != null) c.drawBitmap(furniture, 0f, 0f, null);

        // curtains (sway very slowly)
        final float sw = Rig.sin(time * .6f) * w * .006f, sw2 = Rig.sin(time * .6f + 1.2f) * w * .006f;
        p.setColor(0xFFF4A6B0);
        path.reset(); path.moveTo(winL - w * .035f, winT - w * .02f); path.lineTo(winL + w * .05f, winT - w * .02f);
        path.quadTo(winL + w * .06f + sw, (winT + winB) * .5f, winL + w * .04f + sw2, winB + w * .03f);
        path.lineTo(winL - w * .035f, winB + w * .03f); path.close(); c.drawPath(path, p);
        path.reset(); path.moveTo(winR + w * .035f, winT - w * .02f); path.lineTo(winR - w * .05f, winT - w * .02f);
        path.quadTo(winR - w * .06f + sw2, (winT + winB) * .5f, winR - w * .04f + sw, winB + w * .03f);
        path.lineTo(winR + w * .035f, winB + w * .03f); path.close(); c.drawPath(path, p);
        p.setColor(0x33FFFFFF); c.drawRect(winL - w * .035f, winT - w * .024f, winR + w * .035f, winT - w * .012f, p);
        p.setColor(0xFFB5805A); c.drawRect(winL - w * .05f, winT - w * .03f, winR + w * .05f, winT - w * .02f, p);

        // light shaft from window to floor: two nested trapezoids give it a soft edge
        if (k.shaftA > .01f) {
            p.setColor(k.shaftC); p.setAlpha((int) (255 * k.shaftA * .5f));
            final float off = (k.sunX - .5f) * -w * .5f;
            for (int pass = 0; pass < 2; pass++) {
                final float in = pass * w * .035f, sp = pass * w * .05f;
                path.reset(); path.moveTo(winL + in, winB); path.lineTo(winR - in, winB);
                path.lineTo(winR + w * .2f + off - sp, groundY + h * .05f); path.lineTo(winL + w * .12f + off + sp, groundY + h * .05f); path.close();
                c.drawPath(path, p);
            }
            p.setAlpha(255);
        }

        // dust motes floating in the light shaft (morning / sunny)
        if (k.shaftA > .1f) {
            p.setColor(0xFFFFF6D8);
            for (int i = 0; i < 14; i++) {
                final float mph = time * .05f + i * .137f, fy = (mph - (float) Math.floor(mph));
                final float mx = winL + w * (.08f + .5f * (((i * 37) % 13) / 13f)) + fy * w * .14f + (float) Math.sin(time * .7f + i) * w * .01f;
                final float my = winB + (groundY - winB) * fy;
                p.setAlpha((int) (200 * k.shaftA * Math.sin(Math.PI * fy))); c.drawCircle(mx, my, w * (.0025f + .0015f * (i % 3)), p);
            }
            p.setAlpha(255);
        }
        // clock hands
        if (clockMin != minute) {
            clockMin = minute; final double ma = minute * Math.PI / 30.0, ha = ((hour % 12) + minute / 60.0) * Math.PI / 6.0;
            clockMx = (float) Math.sin(ma) * clockR * .75f; clockMy = -(float) Math.cos(ma) * clockR * .75f;
            clockHx = (float) Math.sin(ha) * clockR * .5f; clockHy = -(float) Math.cos(ha) * clockR * .5f;
        }
        p.setColor(0xFF4A3F3F); p.setStrokeWidth(Math.max(3f, w * .006f)); p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(clockX, clockY, clockX + clockHx, clockY + clockHy, p);
        p.setStrokeWidth(Math.max(2f, w * .0042f)); c.drawLine(clockX, clockY, clockX + clockMx, clockY + clockMy, p);
        p.setColor(0xFFE8604F); c.drawCircle(clockX, clockY, w * .005f, p);
    }

    /** Crescent between the lower arcs of two ellipses (used for the bed's front wall). */
    private void crescent(Canvas c, RectF outer, RectF inner, int col) {
        path.reset();
        path.moveTo(outer.right, (outer.top + outer.bottom) * .5f);
        path.arcTo(outer, 0f, 180f);
        path.lineTo(inner.left, (inner.top + inner.bottom) * .5f);
        path.arcTo(inner, 180f, -180f);
        path.close();
        p.setStyle(Paint.Style.FILL); p.setColor(col); c.drawPath(path, p);
    }

    /** The bed's front wall + rim, drawn AFTER the penguin so it looks like it is lying inside the basket. Same geometry as the static bed. */
    void drawBedFront(Canvas c) {
        rfA.set(bedX - w * .16f, groundY - h * .045f, bedX + w * .16f, groundY + h * .036f);     // outer wall
        rfB.set(bedX - w * .14f, groundY - h * .05f, bedX + w * .14f, groundY + h * .016f);      // rim
        rfC.set(bedX - w * .125f, groundY - h * .044f, bedX + w * .125f, groundY + h * .008f);   // inner cushion
        crescent(c, rfB, rfC, 0xFFA77450);
        crescent(c, rfA, rfB, 0xFFB98660);
        // soft light on the rim edge
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, w * .005f)); p.setColor(0x55FFE9C8);
        path.reset(); path.moveTo(rfB.right, (rfB.top + rfB.bottom) * .5f); path.arcTo(rfB, 0f, 180f); c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Colour grade + lamp glow + charger glow (drawn after the penguin so the whole scene is lit consistently). */
    /**
     * Colour grade, first pass (drawn BEFORE the pets): the room gets most of the evening / night tint here,
     * the pets only get the lighter second pass in drawFront, so Jinbei's blue does not sink into the night colour.
     */
    void drawTint(Canvas c) {
        final float A = cur.tintA; if (A <= .005f) return;
        final float a2 = A * PET_TINT, a1 = 1f - (1f - A) / (1f - a2);
        if (a1 > .003f) c.drawColor((((int) (255 * a1)) << 24) | (cur.tint & 0xFFFFFF));
    }
    private static final float PET_TINT = .45f;

    void drawFront(Canvas c) {
        final Look k = cur;
        if (k.tintA > .005f) { c.drawColor((((int) (255 * k.tintA * PET_TINT)) << 24) | (k.tint & 0xFFFFFF)); }
        if (k.lamp > .02f && lampGlow != null) {
            final float flick = 1f + .06f * Rig.sin(time * 5.3f) * Rig.sin(time * 1.9f) + .025f * Rig.sin(time * 13.7f);
            lampM.setTranslate(lampX - w * .02f, lampY + h * .04f); lampGlow.setLocalMatrix(lampM);
            q.setShader(lampGlow); q.setAlpha((int) (255 * Math.min(1f, k.lamp * flick)));
            c.drawRect(0, 0, w, h, q); q.setShader(null); q.setAlpha(255);
            // lit shade
            p.setColor(0xFFFFF0B8); p.setAlpha((int) (255 * k.lamp)); path.reset();
            path.moveTo(lampX - w * .036f, lampY + h * .062f); path.lineTo(lampX + w * .036f, lampY + h * .062f); path.lineTo(lampX + w * .023f, lampY + h * .0f); path.lineTo(lampX - w * .023f, lampY + h * .0f); path.close();
            c.drawPath(path, p); p.setAlpha(255);
            // warm pool of light on the floor under the lamp (flickers with it)
            if (poolGlow != null) {
                poolM.setScale(1f, .32f); poolM.postTranslate(lampX - w * .08f, groundY + h * .03f); poolGlow.setLocalMatrix(poolM);
                q.setShader(poolGlow); q.setAlpha((int) (255 * Math.min(1f, k.lamp * flick))); c.drawRect(0, groundY - h * .1f, w, h, q); q.setShader(null); q.setAlpha(255);
            }
        }
        if (flash > .01f) c.drawColor(((int) (110 * flash) << 24) | 0xFFFFFF);
        if (charging) {
            final float pu = (chargePulse % 1.6f) / 1.6f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w * .006f); p.setColor(0xFFFFE04A); p.setAlpha((int) (230 * (1f - pu)));
            rf.set(chargerX - w * (.09f + .05f * pu), groundY - h * (.013f + .008f * pu), chargerX + w * (.09f + .05f * pu), groundY + h * (.02f + .008f * pu)); c.drawOval(rf, p);
            p.setStyle(Paint.Style.FILL); p.setAlpha(255);
        }
    }
}
