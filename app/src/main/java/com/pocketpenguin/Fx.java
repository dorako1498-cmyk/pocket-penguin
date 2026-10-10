package com.pocketpenguin;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Small fixed-size particle pool for hearts, "!", "?", sparkles, Zzz, sweat, dust and bolts. Nothing is allocated per frame. */
final class Fx {
    static final int HEART = 1, EXCL = 2, QUEST = 3, SPARK = 4, ZZZ = 5, SWEAT = 6, PUFF = 7, BOLT = 8, BUBBLE = 9,
            ANGER = 10, TEAR = 11, NOTE = 12, HIT = 13;   // HIT: comic impact burst with a sound word (see HIT_WORDS)
    static final String[] HIT_WORDS = { "ポカッ！", "ドカッ！", "バシッ！", "ペチッ！", "ボコッ！", "ドーン！" };
    private final int[] sub = new int[56];   // ANGER: the red "vein" mark, TEAR: falls with gravity, NOTE: a music note
    private static final int N = 56;
    private final int[] type = new int[N];
    private final float[] x = new float[N], y = new float[N], vx = new float[N], vy = new float[N];
    private final float[] age = new float[N], life = new float[N], size = new float[N], rot = new float[N], vr = new float[N];
    private int next = 0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path(), star = new Path(), drop = new Path(), bolt = new Path(), burst = new Path();
    private final RectF rf = new RectF();

    Fx() {
        heart.moveTo(0, .55f);
        heart.cubicTo(-1.25f, -.25f, -.7f, -1.15f, 0, -.5f);
        heart.cubicTo(.7f, -1.15f, 1.25f, -.25f, 0, .55f);
        heart.close();
        star.moveTo(0, -1f); star.quadTo(.12f, -.12f, 1f, 0); star.quadTo(.12f, .12f, 0, 1f);
        star.quadTo(-.12f, .12f, -1f, 0); star.quadTo(-.12f, -.12f, 0, -1f); star.close();
        drop.moveTo(0, -1f); drop.cubicTo(.9f, -.1f, .8f, .9f, 0, .9f); drop.cubicTo(-.8f, .9f, -.9f, -.1f, 0, -1f); drop.close();
        bolt.moveTo(.25f, -1f); bolt.lineTo(-.55f, .15f); bolt.lineTo(-.05f, .15f); bolt.lineTo(-.3f, 1f);
        bolt.lineTo(.6f, -.2f); bolt.lineTo(.05f, -.2f); bolt.close();
        line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        fill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        fill.setTextAlign(Paint.Align.CENTER);
    }

    void clear() { for (int i = 0; i < N; i++) type[i] = 0; }

    void spawn(int t, float px, float py, float pvx, float pvy, float lifeS, float sz) {
        int i = next; next = (next + 1) % N;
        type[i] = t; x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy; age[i] = 0f; life[i] = lifeS; size[i] = sz;
        rot[i] = (t == HEART || t == SPARK || t == NOTE) ? (float) (Math.random() - .5) * 30f : 0f;
        vr[i] = t == SPARK ? 90f : 0f;
    }

    /** A comic impact burst with a sound word (index into HIT_WORDS). */
    void hit(float px, float py, float sz, int word) {
        final int i = next; spawn(HIT, px, py, 0f, -18f * sz / 40f, .7f, sz); sub[i] = word; rot[i] = (float) (Math.random() - .5) * 24f;
    }

    void update(float dt) {
        for (int i = 0; i < N; i++) {
            if (type[i] == 0) continue;
            age[i] += dt;
            if (age[i] >= life[i]) { type[i] = 0; continue; }
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; rot[i] += vr[i] * dt;
            if (type[i] == ZZZ) vx[i] += 10f * dt;
            if (type[i] == HEART) vy[i] *= (1f - .6f * dt);
            if (type[i] == BUBBLE) vx[i] += (float) Math.sin(age[i] * 6f + i) * 55f * dt;
            if (type[i] == TEAR) vy[i] += size[i] * 22f * dt;                         // falls
            if (type[i] == NOTE) vx[i] += (float) Math.sin(age[i] * 7f + i) * 70f * dt;
        }
    }

    boolean any() { for (int i = 0; i < N; i++) if (type[i] != 0) return true; return false; }

    void draw(Canvas c) {
        for (int i = 0; i < N; i++) {
            if (type[i] == 0) continue;
            float a = Math.min(1f, Math.min(age[i] / .12f, (life[i] - age[i]) / .35f));
            float pop = type[i] == PUFF ? 1f : Math.min(1f, .55f + age[i] / .18f * .45f);
            float over = type[i] == PUFF ? (.5f + age[i] / life[i]) : (age[i] < .18f ? 1f + .25f * (1f - age[i] / .18f) : 1f);
            float s = size[i] * pop * over;
            c.save();
            c.translate(x[i], y[i]);
            c.rotate(rot[i]);
            switch (type[i]) {
                case HEART:
                    c.scale(s, s);
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFF6B8E); fill.setAlpha((int) (255 * a));
                    c.drawPath(heart, fill);
                    line.setStrokeWidth(.12f); line.setColor(0xFFFFFFFF); line.setAlpha((int) (200 * a));
                    c.drawPath(heart, line);
                    fill.setColor(0xFFFFFFFF); fill.setAlpha((int) (210 * a));
                    c.drawCircle(-.45f, -.35f, .13f, fill);
                    break;
                case EXCL:
                    c.scale(s, s);
                    rf.set(-.2f, -1f, .2f, .3f);
                    line.setStrokeWidth(.14f); line.setColor(0xFFFFFFFF); line.setAlpha((int) (230 * a));
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFF4D3D); fill.setAlpha((int) (255 * a));
                    c.drawRoundRect(rf, .2f, .2f, line); c.drawCircle(0, .68f, .2f, line);
                    c.drawRoundRect(rf, .2f, .2f, fill); c.drawCircle(0, .68f, .2f, fill);
                    break;
                case QUEST:
                    fill.setStyle(Paint.Style.FILL); fill.setTextSize(s * 2.1f);
                    fill.setColor(0xFF5B6FD6); fill.setAlpha((int) (255 * a));
                    c.drawText("?", 0, s * .75f, fill);
                    break;
                case SPARK:
                    c.scale(s, s);
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFFD84D); fill.setAlpha((int) (255 * a));
                    c.drawPath(star, fill);
                    break;
                case ZZZ:
                    fill.setStyle(Paint.Style.FILL); fill.setTextSize(s * 1.6f); fill.setColor(0xFF6E86C9); fill.setAlpha((int) (235 * a));
                    c.drawText("Z", 0, s * .55f, fill);
                    break;
                case SWEAT:
                    c.scale(s, s);
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFF7CC4F2); fill.setAlpha((int) (240 * a));
                    c.drawPath(drop, fill);
                    break;
                case PUFF:
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFFFFFF); fill.setAlpha((int) (150 * a * (1f - age[i] / life[i])));
                    c.drawCircle(0, 0, s, fill);
                    break;
                case BOLT:
                    c.scale(s, s);
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFFE04A); fill.setAlpha((int) (255 * a));
                    c.drawPath(bolt, fill);
                    break;
                case BUBBLE:
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFCDEBFF); fill.setAlpha((int) (95 * a));
                    c.drawCircle(0, 0, s, fill);
                    line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(Math.max(2f, s * .14f)); line.setColor(0xFF8FCBEF); line.setAlpha((int) (230 * a));
                    c.drawCircle(0, 0, s, line);
                    fill.setColor(0xFFFFFFFF); fill.setAlpha((int) (230 * a));
                    c.drawCircle(-s * .35f, -s * .35f, s * .2f, fill);
                    break;
                case ANGER: {                                  // four bent strokes around a centre: the classic "anger vein"
                    final float k = 1f + .12f * (float) Math.sin(age[i] * 18f);
                    c.scale(s * k, s * k);
                    line.setStyle(Paint.Style.STROKE); line.setColor(0xFFE8394A); line.setAlpha((int) (255 * a)); line.setStrokeWidth(.22f);
                    for (int q = 0; q < 4; q++) {
                        c.save(); c.rotate(q * 90f);
                        c.drawLine(.18f, -.75f, .18f, -.18f, line); c.drawLine(.18f, -.18f, .75f, -.18f, line);
                        c.restore();
                    }
                    break; }
                case HIT: {
                    c.scale(s / 10f, s / 10f);
                    burst.reset();
                    for (int k = 0; k < 24; k++) { final double ang = k * Math.PI / 12.0; final float r = (k % 2 == 0) ? 10f : 6.2f; final float px = (float) Math.cos(ang) * r * 1.35f, py = (float) Math.sin(ang) * r; if (k == 0) burst.moveTo(px, py); else burst.lineTo(px, py); }
                    burst.close();
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFFFFE14D); fill.setAlpha((int) (255 * a)); c.drawPath(burst, fill);
                    line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(.9f); line.setColor(0xFFE23B3B); line.setAlpha((int) (255 * a)); c.drawPath(burst, line);
                    fill.setColor(0xFFB3141E); fill.setAlpha((int) (255 * a)); fill.setTextSize(4.6f); c.drawText(HIT_WORDS[sub[i] % HIT_WORDS.length], 0f, 1.6f, fill);
                    break; }
                case TEAR:
                    c.scale(s, s);
                    fill.setStyle(Paint.Style.FILL); fill.setColor(0xFF5AB4F0); fill.setAlpha((int) (235 * a));
                    c.drawPath(drop, fill);
                    fill.setColor(0xFFFFFFFF); fill.setAlpha((int) (200 * a)); c.drawCircle(-.25f, .1f, .18f, fill);
                    break;
                case NOTE:
                    fill.setStyle(Paint.Style.FILL); fill.setTextSize(s * 2f); fill.setColor((i & 1) == 0 ? 0xFFF06A9A : 0xFF5B8FE0); fill.setAlpha((int) (240 * a));
                    c.drawText((i & 1) == 0 ? "♪" : "♫", 0, s * .7f, fill);
                    break;
                default: break;
            }
            c.restore();
        }
        fill.setAlpha(255);
    }
}
