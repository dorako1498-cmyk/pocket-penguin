package com.pocketpenguin;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.util.Calendar;

/**
 * Everything in the room that changes with care and time: the contents of the two bowls, the toy ball (it really rolls when a pet bumps it),
 * and the room items that unlock the longer you live together (poster, fairy lights, toy box, goldfish, photo frames, seasonal decorations).
 */
final class Decor {
    static final int POSTER = 0, LIGHTS = 1, TOYBOX = 2, FISH = 3, FRAMES = 4, PUMPKIN = 5, TREE = 6, COUNT = 7;
    static final String[] NAMES = { "poster", "lights", "toybox", "fish", "frames", "pumpkin", "tree" };

    private final Care care; private final Room room;
    int w, h; int mask, seen, newItem;         // newItem = item index + 1 of a freshly unlocked item (0 none)
    private float time;
    // ---- ball
    float ballV, ballH, ballHv, ballRot; private float ballR, ballFloor;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF(); private final Path path = new Path();

    Decor(Care care, Room room, int seenMask) { this.care = care; this.room = room; this.seen = seenMask; }

    void layout(int ww, int hh) {
        w = ww; h = hh; ballR = w * .037f;
        if (room.ballX < 0f || room.ballX > w) room.ballX = w * .445f;
    }

    /** Which items exist right now. */
    int computeMask(long nowMs) {
        if (care.unlockAll) return (1 << COUNT) - 1;
        final int d = care.days(nowMs), f = care.feeds; int m = 0;
        if (d >= 1) m |= 1 << POSTER;
        if (d >= 2 && f >= 3) m |= 1 << LIGHTS;
        if (d >= 4) m |= 1 << TOYBOX;
        if (d >= 6 && f >= 8) m |= 1 << FISH;
        if (d >= 9) m |= 1 << FRAMES;
        final Calendar cal = Calendar.getInstance(); final int mo = cal.get(Calendar.MONTH), dom = cal.get(Calendar.DAY_OF_MONTH);
        if (mo == Calendar.OCTOBER && dom >= 15) m |= 1 << PUMPKIN;
        if (mo == Calendar.DECEMBER && dom <= 26) m |= 1 << TREE;
        return m;
    }
    boolean has(int item) { return (mask & (1 << item)) != 0; }
    void markSeen(SeenSaver s) { seen = mask; newItem = 0; if (s != null) s.save(seen); }
    interface SeenSaver { void save(int seenMask); }

    /** Which bowl (1 food, 2 water) is at (x, y). Checked before the pets, so the bowls stay tappable when someone stands in front of them. */
    int bowlHit(float x, float y) {
        if (y < room.bowlY - h * .06f || y > room.bowlY + h * .035f) return 0;
        if (Math.abs(x - w * Room.FOOD_X) < w * .09f) return 1;
        if (Math.abs(x - w * Room.WATER_X) < w * .09f) return 2;
        return 0;
    }

    /** True when (x, y) is on or right around the ball (generous, it is small). */
    boolean ballHit(float x, float y) {
        final float by = room.groundY + h * .002f - ballH;
        return Math.abs(x - room.ballX) < ballR * 3.2f && Math.abs(y - by) < ballR * 3.2f;
    }
    /** The user flicked the ball: it rolls away from the finger, towards the middle of the room. */
    void flickBall() { kick(room.ballX < w * .5f ? 1 : -1, w * .55f); ballHv = h * .4f; }

    // ===================================================================== simulation
    void update(float dt, long nowMs, float penX, int penFace, boolean penMoving, boolean penKick, float budX, int budFace, boolean budMoving) {
        time += dt;
        final int m = computeMask(nowMs);
        if (m != mask) {
            final int fresh = m & ~seen; mask = m;
            if (fresh != 0 && newItem == 0) { for (int i = 0; i < COUNT; i++) if ((fresh & (1 << i)) != 0) { newItem = i + 1; break; } }
        }
        // ball contacts
        final float pf = penX + penFace * w * .075f, bf = budX + budFace * w * .19f;
        float bx = room.ballX;
        if (Math.abs(bx - pf) < w * .05f && penMoving) kick(penFace, w * .42f);
        if (penKick && Math.abs(bx - (penX + penFace * w * .1f)) < w * .13f) kick(penFace, w * .75f);
        if (Math.abs(bx - bf) < w * .06f && budMoving) kick(budFace, w * .5f);
        // physics
        bx += ballV * dt; ballV *= (float) Math.exp(-1.5f * dt);
        if (bx < w * .14f) { bx = w * .14f; ballV = Math.abs(ballV) * .55f; }
        if (bx > w * .86f) { bx = w * .86f; ballV = -Math.abs(ballV) * .55f; }
        if (Math.abs(ballV) < 3f) ballV = 0f;
        ballRot += ballV * dt / Math.max(1f, ballR) * 57.3f;
        if (ballH > 0f || ballHv > 0f) { ballHv -= h * 1.6f * dt; ballH += ballHv * dt; if (ballH <= 0f) { ballH = 0f; ballHv = Math.abs(ballHv) > h * .12f ? -ballHv * .35f : 0f; } }
        room.ballX = bx;
    }
    private void kick(int dir, float speed) { ballV = dir * speed; if (ballH <= 0f) ballHv = h * .28f; }
    boolean ballMoving() { return Math.abs(ballV) > 5f; }

    // ===================================================================== drawing
    /** `night` 0..1 : how dark the room is (fairy lights / lanterns glow). */
    void draw(Canvas c, float night) {
        p.setStyle(Paint.Style.FILL); p.setShader(null); p.setAlpha(255);
        if (has(LIGHTS)) lights(c, night);
        if (has(POSTER)) poster(c);
        if (has(FRAMES)) frames(c);
        final float back = room.wallBottom + h * .045f;
        if (has(TOYBOX)) toybox(c, w * .10f, back);
        if (has(FISH)) fish(c, w * .27f, back);
        if (has(PUMPKIN)) pumpkin(c, w * .72f, back, night);
        if (has(TREE)) tree(c, w * .72f, back);
        ball(c);
    }

    private void oval(Canvas c, float l, float t, float r, float b, int col) { p.setColor(col); rf.set(l, t, r, b); c.drawOval(rf, p); }
    private void rr(Canvas c, float l, float t, float r, float b, float rad, int col) { p.setColor(col); rf.set(l, t, r, b); c.drawRoundRect(rf, rad, rad, p); }

    /** The two bowls in the foreground (drawn after the pets, so whoever eats stands behind the bowl). Bigger = closer to the viewer. */
    void drawBowls(Canvas c) {
        p.setStyle(Paint.Style.FILL); p.setShader(null); p.setAlpha(255);
        final float S = 1.4f, y0 = room.groundY + h * .004f;          // geometry below is drawn at the old floor line, then moved and scaled
        c.save(); c.translate(0f, room.bowlY - y0);
        for (int k = 1; k <= 2; k++) {
            final float x = w * (k == 1 ? Room.FOOD_X : Room.WATER_X);
            c.save(); c.scale(S, S, x, y0);
            if (k == 1) { bowlBody(c, x, y0, 0xFFE8604F, 0xFFC4452F); food(c, x, y0); } else { bowlBody(c, x, y0, 0xFF5FA8E8, 0xFF3F87C8); water(c, x, y0); }
            c.restore();
        }
        c.restore();
    }
    private void bowlBody(Canvas c, float x, float y, int col, int dark) {
        final float bw = w * .055f;
        oval(c, x - bw * 1.12f, y + h * .002f, x + bw * 1.12f, y + h * .019f, 0x33000000);         // contact shadow
        oval(c, x - bw, y - h * .018f, x + bw, y + h * .014f, dark);                                // outer wall
        oval(c, x - bw * .92f, y - h * .02f, x + bw * .92f, y + h * .006f, col);                    // rim
        oval(c, x - bw * .78f, y - h * .021f, x + bw * .78f, y - h * .003f, dark);                  // inside (empty)
    }

    private void food(Canvas c, float fx, float y) {
        final float bw = w * .055f, lv = Math.max(0f, Math.min(1f, care.food));
        if (lv > .04f) {
            final float top = y - h * .003f - (h * .004f + h * .02f * lv);
            oval(c, fx - bw * .7f, top, fx + bw * .7f, y - h * .003f, 0xFF8A5B38);
            p.setColor(0xFF9C6B45);
            final int n = (int) (3 + lv * 9);
            for (int i = 0; i < n; i++) { final float kx = fx - bw * .55f + (i * 37 % 11) / 10f * bw * 1.1f, ky = top + h * .003f + ((i * 13) % 5) * h * .0018f; c.drawCircle(kx, ky, w * .0082f, p); }
            p.setColor(0x55FFFFFF); c.drawCircle(fx - bw * .2f, top + h * .002f, w * .004f, p);
        }
    }
    private void water(Canvas c, float wx, float y) {
        final float bw = w * .055f, wl = Math.max(0f, Math.min(1f, care.water));
        if (wl > .04f) {
            final float k = .45f + .3f * wl;
            oval(c, wx - bw * k, y - h * .019f + (1f - wl) * h * .008f, wx + bw * k, y - h * .004f, 0xFFAEDCF7);
            oval(c, wx - bw * .4f, y - h * .017f + (1f - wl) * h * .008f, wx - bw * .1f, y - h * .011f + (1f - wl) * h * .008f, 0x99FFFFFF);
        }
    }

    private void ball(Canvas c) {
        final float bx = room.ballX, by = room.groundY + h * .002f - ballH, r = ballR;
        final float sh = Math.max(.4f, 1f - ballH / (h * .08f));
        oval(c, bx - r * 1.1f * sh, room.groundY + h * .012f, bx + r * 1.1f * sh, room.groundY + h * .026f, 0x26000000);
        p.setColor(0xFFFFD27A); c.drawCircle(bx, by, r, p);
        c.save(); c.rotate(ballRot, bx, by);
        p.setColor(0xFFF28C5B); rf.set(bx - r, by - r, bx + r, by + r); c.drawArc(rf, 200f, 70f, true, p); c.drawArc(rf, 20f, 70f, true, p);
        p.setColor(0x66FFFFFF); c.drawCircle(bx - r * .35f, by - r * .4f, r * .25f, p);
        c.restore();
    }

    private void poster(Canvas c) {
        final float l = w * .55f, t = h * .365f, r = w * .66f, b = h * .515f;
        rr(c, l - w * .008f, t - w * .008f, r + w * .008f, b + w * .008f, w * .008f, 0xFFFFFFFF);
        rr(c, l, t, r, b, w * .004f, 0xFFBFE3F5);
        oval(c, l, b - h * .05f, r, b + h * .06f, 0xFFE6F6FF);                      // snowy ground
        final float cx = (l + r) * .5f, py = b - h * .03f;
        oval(c, cx - w * .045f, py - h * .035f, cx + w * .01f, py + h * .02f, 0xFF45474D);   // penguin
        oval(c, cx - w * .035f, py - h * .022f, cx, py + h * .018f, 0xFFFFF6E3);
        p.setColor(0xFF45474D); c.drawCircle(cx - w * .018f, py - h * .045f, w * .018f, p);
        oval(c, cx - w * .025f, py - h * .048f, cx - w * .01f, py - h * .036f, 0xFFFFF6E3);
        oval(c, cx - w * .0215f, py - h * .0405f, cx - w * .0135f, py - h * .0375f, 0xFFF5B85E);
        oval(c, cx + w * .0f, py - h * .02f, cx + w * .052f, py + h * .02f, 0xFF7AB8CE);     // little whale shark
        p.setColor(0xFFFFFFFF); for (int i = 0; i < 4; i++) c.drawCircle(cx + w * (.01f + i * .011f), py - h * (.006f - (i % 2) * .008f), w * .0033f, p);
        p.setColor(0xFFFFE680); c.drawCircle(r - w * .022f, t + h * .026f, w * .014f, p);          // sun
    }

    private void frames(Canvas c) {
        final float[] xs = { .69f, .755f }; final int[] cols = { 0xFFFFD9E3, 0xFFD9EEC8 };
        for (int i = 0; i < 2; i++) {
            final float l = w * xs[i], t = h * (.405f + .012f * i), r = l + w * .05f, b = t + h * .045f;
            rr(c, l - w * .006f, t - w * .006f, r + w * .006f, b + w * .006f, w * .005f, 0xFFB98660);
            rr(c, l, t, r, b, w * .003f, cols[i]);
            p.setColor(i == 0 ? 0xFF45474D : 0xFF7AB8CE); c.drawCircle((l + r) * .5f, (t + b) * .5f + h * .004f, w * .013f, p);
            p.setColor(0xFFFFF6E3); c.drawCircle((l + r) * .5f, (t + b) * .5f + h * .008f, w * .008f, p);
        }
    }

    private void lights(Canvas c, float night) {
        final int n = 22; final int[] cols = { 0xFFFF8FA8, 0xFFFFD866, 0xFF7CC4F2, 0xFF8FE0A0 };
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, w * .003f)); p.setColor(0xFF7A5B49);
        path.reset(); float lx = 0, ly = 0;
        for (int i = 0; i <= 60; i++) { final float t = i / 60f, x = t * w, y = h * .052f + h * .017f * Math.abs(Rig.sin(t * 9.4248f)); if (i == 0) path.moveTo(x, y); else path.lineTo(x, y); }
        c.drawPath(path, p); p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            final float t = (i + .5f) / n, x = t * w, y = h * .052f + h * .017f * Math.abs(Rig.sin(t * 9.4248f)) + h * .006f;
            final float tw = .65f + .35f * Rig.sin(time * 2.2f + i * 1.7f), br = Math.min(1f, .45f + night * .6f) * tw;
            p.setColor(cols[i % 4]); p.setAlpha(255); c.drawCircle(x, y, w * .0085f, p);
            p.setColor(cols[i % 4]); p.setAlpha((int) (70 * br + 20 * night)); c.drawCircle(x, y, w * .024f, p);
            p.setAlpha(255);
        }
    }

    private void toybox(Canvas c, float x, float base) {
        final float bw = w * .075f, bh = h * .05f;
        oval(c, x - bw * 1.05f, base - h * .004f, x + bw * 1.05f, base + h * .012f, 0x22000000);
        // toys poking out
        p.setColor(0xFF45474D); c.drawCircle(x - bw * .3f, base - bh - h * .012f, w * .02f, p);                  // stuffed penguin
        oval(c, x - bw * .3f - w * .013f, base - bh - h * .02f, x - bw * .3f + w * .013f, base - bh + h * .002f, 0xFFFFF6E3);
        oval(c, x - bw * .3f - w * .005f, base - bh - h * .008f, x - bw * .3f + w * .005f, base - bh - h * .003f, 0xFFF5B85E);
        p.setColor(0xFFFF8FA8); c.drawCircle(x + bw * .35f, base - bh - h * .004f, w * .018f, p);                // ball
        rr(c, x + bw * .05f, base - bh - h * .02f, x + bw * .25f, base - bh + h * .004f, w * .003f, 0xFF7CC4F2); // block
        rr(c, x - bw, base - bh, x + bw, base, w * .008f, 0xFFD9A066);
        rr(c, x - bw, base - bh, x + bw, base - bh + h * .009f, w * .004f, 0xFFB98660);
        rr(c, x - bw * .85f, base - bh * .62f, x + bw * .85f, base - bh * .5f, w * .002f, 0xFFE9BE8A);
        p.setColor(0xFFB98660); c.drawCircle(x, base - bh * .3f, w * .006f, p);
    }

    private void fish(Canvas c, float x, float base) {
        final float r = w * .045f;
        rr(c, x - r * .55f, base - h * .022f, x + r * .55f, base, w * .004f, 0xFFB98660);                      // little stool
        rr(c, x - r * .75f, base - h * .026f, x + r * .75f, base - h * .018f, w * .004f, 0xFFD9A066);
        final float cy = base - h * .026f - r;
        p.setColor(0x55DFF3FF); c.drawCircle(x, cy, r, p);
        p.setColor(0x66AEDCF7); rf.set(x - r, cy - r * .1f, x + r, cy + r); c.drawArc(rf, 0f, 180f, true, p);
        p.setColor(0xFFC9B79C); for (int i = 0; i < 5; i++) c.drawCircle(x - r * .5f + i * r * .25f, cy + r * .88f, w * .005f, p);
        final float fx = x + (float) Math.sin(time * .7f) * r * .45f, dir = Math.cos(time * .7f) > 0 ? 1f : -1f, fy = cy + r * .1f + (float) Math.sin(time * 1.3f) * r * .15f;
        oval(c, fx - w * .014f, fy - w * .008f, fx + w * .014f, fy + w * .008f, 0xFFFF9A4D);
        path.reset(); path.moveTo(fx - dir * w * .012f, fy); path.lineTo(fx - dir * w * .026f, fy - w * .008f + (float) Math.sin(time * 9f) * w * .003f); path.lineTo(fx - dir * w * .026f, fy + w * .008f); path.close();
        p.setColor(0xFFFF7A33); c.drawPath(path, p);
        p.setColor(0xFF26282E); c.drawCircle(fx + dir * w * .007f, fy - w * .002f, w * .0017f, p);
        p.setColor(0x99FFFFFF); c.drawCircle(x - r * .4f, cy - r * .45f, r * .12f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, w * .0035f)); p.setColor(0xAAFFFFFF); c.drawCircle(x, cy, r, p); p.setStyle(Paint.Style.FILL);
    }

    private void pumpkin(Canvas c, float x, float base, float night) {
        final float r = w * .05f;
        oval(c, x - r * 1.1f, base - h * .004f, x + r * 1.1f, base + h * .012f, 0x22000000);
        oval(c, x - r, base - r * 1.5f, x + r, base, 0xFFF08A2E);
        oval(c, x - r * .55f, base - r * 1.5f, x + r * .55f, base, 0xFFFFA23D);
        oval(c, x - r * .22f, base - r * 1.5f, x + r * .22f, base, 0xFFF08A2E);
        rr(c, x - r * .1f, base - r * 1.72f, x + r * .12f, base - r * 1.4f, w * .004f, 0xFF5E7A3A);
        final int glow = night > .3f ? 0xFFFFE680 : 0xFF5B3A1A;
        path.reset(); path.moveTo(x - r * .5f, base - r * .95f); path.lineTo(x - r * .2f, base - r * .95f); path.lineTo(x - r * .35f, base - r * .7f); path.close(); p.setColor(glow); c.drawPath(path, p);
        path.reset(); path.moveTo(x + r * .5f, base - r * .95f); path.lineTo(x + r * .2f, base - r * .95f); path.lineTo(x + r * .35f, base - r * .7f); path.close(); c.drawPath(path, p);
        rf.set(x - r * .5f, base - r * .62f, x + r * .5f, base - r * .2f); c.drawArc(rf, 10f, 160f, true, p);
    }

    private void tree(Canvas c, float x, float base) {
        final float r = w * .05f;
        oval(c, x - r, base - h * .004f, x + r, base + h * .012f, 0x22000000);
        rr(c, x - r * .12f, base - r * .4f, x + r * .12f, base, w * .002f, 0xFF7A5B49);
        final int[] g = { 0xFF3E9C62, 0xFF4FB373, 0xFF6BC98A };
        for (int i = 0; i < 3; i++) {
            final float yb = base - r * (.3f + i * .62f), hw = r * (.95f - i * .24f);
            path.reset(); path.moveTo(x - hw, yb); path.lineTo(x + hw, yb); path.lineTo(x, yb - r * .95f); path.close(); p.setColor(g[i]); c.drawPath(path, p);
        }
        p.setColor(0xFFFFD84D); path.reset(); final float sy = base - r * 2.05f; path.moveTo(x, sy - r * .22f); path.lineTo(x + r * .08f, sy); path.lineTo(x + r * .22f, sy); path.lineTo(x + r * .1f, sy + r * .1f); path.lineTo(x + r * .14f, sy + r * .28f); path.lineTo(x, sy + r * .16f); path.lineTo(x - r * .14f, sy + r * .28f); path.lineTo(x - r * .1f, sy + r * .1f); path.lineTo(x - r * .22f, sy); path.lineTo(x - r * .08f, sy); path.close(); c.drawPath(path, p);
        final int[] oc = { 0xFFFF6B8E, 0xFFFFD84D, 0xFF7CC4F2, 0xFFFF6B8E, 0xFFFFD84D };
        for (int i = 0; i < 5; i++) { p.setColor(oc[i]); p.setAlpha((int) (170 + 85 * Rig.sin(time * 2f + i * 1.3f))); c.drawCircle(x + r * (-.5f + (i * .37f) % 1f) * (1f - (i % 3) * .2f), base - r * (.45f + (i % 3) * .55f), w * .0065f, p); p.setAlpha(255); }
    }
}
