package com.pocketpenguin;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Random;

/**
 * The two pets talk to each other. Picks a dialogue from {@link TalkData} that fits the moment (time of day, weather, season,
 * hunger, battery, what the user just did ...), plays it line by line and draws speech bubbles above the speaker.
 * Nothing is allocated per frame (the text of a bubble is wrapped once when a line starts).
 */
final class Talk {
    private final TalkData.Pat[] pats = TalkData.build();
    private final Random rnd = new Random();
    private final int[] recent = new int[40]; private int recentN;

    // ---- conductor
    float time, nextTalk = 18f;
    private TalkData.Pat cur; private int line; private float lineT, lineDur; private boolean gap; private float gapT;
    private long stickyBits, stickyUntilMask; private final float[] stickyT = new float[40];
    // ---- what the engine should do when a new line starts
    boolean lineStarted; int lineWho, lineEmo;
    // ---- bubble
    private boolean on; private int who, kind; private final String[] rows = new String[4]; private int nRows; private float bw, bh, age, life;
    private int w, h; private float ts, pad, lh;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tail = new Path(); private final RectF rf = new RectF();

    Talk() {
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        txt.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    void layout(int ww, int hh) {
        w = ww; h = hh; ts = Math.max(26f, w * .039f); pad = ts * .62f; lh = ts * 1.25f;
        txt.setTextSize(ts); stroke.setStrokeWidth(Math.max(2f, ts * .09f));
    }

    boolean active() { return on || cur != null; }
    boolean speaking() { return cur != null; }

    /** Make the next conversation start soon (an event happened: fed, petted, ride ...). */
    void kick(float inSeconds) { if (cur == null) nextTalk = Math.min(nextTalk, time + inSeconds); }
    /** Keep a context bit switched on for a while after an event. */
    void sticky(long bit, float seconds) { final int i = Long.numberOfTrailingZeros(bit); if (i < 40) { stickyT[i] = time + seconds; stickyBits |= bit; } }
    long stickyNow() {
        long r = 0L;
        for (int i = 0; i < 40; i++) if (stickyT[i] > time) r |= 1L << i; else stickyBits &= ~(1L << i);
        return r;
    }

    private boolean fits(TalkData.Pat p, long ctx, int sp, int mode) {
        if (sp > 0) return p.sp == sp;
        if (p.sp != 0) return false;
        if ((ctx & p.req) != p.req) return false;
        if (p.any != 0 && (ctx & p.any) == 0) return false;
        if ((ctx & p.not) != 0) return false;
        if (((ctx & TalkData.RIDING) != 0) != ((p.req & TalkData.RIDING) != 0)) return false;
        final boolean sp1 = (ctx & TalkData.SLEEP_P) != 0, sj = (ctx & TalkData.SLEEP_J) != 0;
        switch (mode) {
            case 0: return p.kind == TalkData.CHAT;                                      // both awake
            case 1: return p.kind == TalkData.WHISPER || (p.kind == TalkData.SLEEPTALK_P && sp1) || (p.kind == TalkData.SLEEPTALK_J && sj);   // one asleep
            default: return (p.kind == TalkData.SLEEPTALK_P && sp1) || (p.kind == TalkData.SLEEPTALK_J && sj);                                   // both asleep
        }
    }

    private TalkData.Pat choose(long ctx, int sp) {
        final boolean sp1 = (ctx & TalkData.SLEEP_P) != 0, sj = (ctx & TalkData.SLEEP_J) != 0;
        final int mode = (sp1 && sj) ? 2 : (sp1 || sj) ? 1 : 0;
        float total = 0f; final float[] wt = weights;
        for (int i = 0; i < pats.length; i++) {
            final TalkData.Pat p = pats[i]; wt[i] = 0f;
            if (!fits(p, ctx, sp, mode)) continue;
            float v = p.wt;
            final int spec = Long.bitCount(p.req & ~TalkData.RIDING) + (p.any != 0 ? 1 : 0);
            v *= 1f + 1.6f * spec;                                                        // the more specific, the better the fit
            for (int r = 0; r < recentN; r++) if (recent[r] == p.id) { v *= .04f; break; }
            wt[i] = v; total += v;
        }
        if (total <= 0f) return null;
        float r = rnd.nextFloat() * total;
        for (int i = 0; i < pats.length; i++) { if (r < wt[i]) return pats[i]; r -= wt[i]; }
        return null;
    }
    private final float[] weights = new float[TalkData.build().length];

    /**
     * @param ctx   context bits right now (time of day, weather ... see TalkData)
     * @param sp    a freshly unlocked room item (index + 1) that must be talked about, else 0
     * @param ready true when the pets are in a state where a chat may start
     * @param scale pacing factor (1 normal, >1 slower)
     */
    void update(float dt, long ctx, int sp, boolean ready, float scale) {
        time += dt; lineStarted = false;
        // bubble animation
        if (on) { age += dt; if (age > life) on = false; }
        if (cur != null) {
            if (gap) { gapT -= dt; if (gapT <= 0f) { gap = false; startLine(); } }
            else { lineT += dt; if (lineT >= lineDur) { line++; if (line >= cur.text.length) { endChat(scale); } else { gap = true; gapT = .35f; } } }
        } else if (ready && (time >= nextTalk || sp > 0)) {
            final TalkData.Pat p = choose(ctx | stickyNow(), sp);
            if (p == null) { nextTalk = time + 20f; return; }
            cur = p; line = 0; gap = false; remember(p.id); startLine();
        }
    }

    private void remember(int id) { if (recentN < recent.length) recent[recentN++] = id; else { System.arraycopy(recent, 1, recent, 0, recent.length - 1); recent[recent.length - 1] = id; } }

    private void endChat(float scale) { cur = null; nextTalk = time + (38f + rnd.nextFloat() * 70f) * scale; }

    /** Stop everything (screen off, pet left the room ...). */
    void abort() { cur = null; on = false; nextTalk = time + 25f; }

    private String pick(String s) {
        if (s.indexOf('/') < 0) return s;
        final String[] a = s.split("/"); return a[rnd.nextInt(a.length)];
    }

    private void startLine() {
        final String text = pick(cur.text[line]);
        who = cur.who[line]; kind = cur.kind; lineWho = who; lineEmo = cur.emo[line]; lineStarted = true;
        lineDur = Math.max(1.9f, Math.min(5.2f, 1.2f + text.length() * .2f)); lineT = 0f;
        wrap(text);
        age = 0f; life = lineDur + .35f; on = true;
    }

    private static boolean noLineStart(char c) { return "。、！？…ー〜♪）」,.!?)".indexOf(c) >= 0; }

    private void wrap(String text) {
        final float maxW = w * .62f - pad * 2f; nRows = 0; int start = 0; float widest = 0f;
        while (start < text.length() && nRows < rows.length) {
            int end = start + 1;
            while (end < text.length() && (txt.measureText(text.substring(start, end + 1)) <= maxW || noLineStart(text.charAt(end)))) end++;
            rows[nRows] = text.substring(start, end); widest = Math.max(widest, txt.measureText(rows[nRows])); nRows++; start = end;
        }
        bw = widest + pad * 2f; bh = nRows * lh + pad * 1.2f;
    }

    /** Draws the current bubble above the speaker's head. */
    void draw(Canvas c, float penHeadX, float penHeadY, float budHeadX, float budHeadY) {
        if (!on || nRows == 0) return;
        final float ax = who == 0 ? penHeadX : budHeadX, ay = who == 0 ? penHeadY : budHeadY;
        final float pop = Math.min(1f, age / .18f), overs = 1f + .08f * (float) Math.sin(Math.min(1f, age / .18f) * Math.PI);
        final float fade = Math.min(1f, Math.max(0f, (life - age) / .25f));
        final float a = Math.min(pop, fade);
        final float margin = w * .03f, gapY = ts * .9f;
        float left = Math.max(margin, Math.min(w - margin - bw, ax - bw * .5f));
        float top = Math.max(h * .075f, ay - bh - gapY - ts * .5f);
        final boolean sleepy = cur != null && cur.kind >= TalkData.SLEEPTALK_P || (cur != null && cur.emo[Math.min(line, cur.emo.length - 1)] == 'z');
        final int bg = sleepy ? 0xF2EEE9FA : who == 0 ? 0xFFFFFFFF : 0xFFEAF7FF, bd = sleepy ? 0xFFB9B2DC : who == 0 ? 0xFF9FB1C6 : 0xFF7FB5D0, tc = 0xFF35404D;
        c.save();
        c.scale(overs * (.7f + .3f * pop), overs * (.7f + .3f * pop), ax, ay - ts);
        fill.setColor(bg); fill.setAlpha((int) (255 * a)); stroke.setColor(bd); stroke.setAlpha((int) (255 * a));
        rf.set(left, top, left + bw, top + bh); final float rad = ts * .9f;
        c.drawRoundRect(rf, rad, rad, fill); c.drawRoundRect(rf, rad, rad, stroke);
        // tail pointing at the speaker
        final float tx = Math.max(left + rad, Math.min(left + bw - rad, ax)), by = top + bh;
        tail.reset(); tail.moveTo(tx - ts * .45f, by - 1f); tail.lineTo(Math.max(left + ts * .5f, Math.min(left + bw - ts * .5f, ax)), by + gapY * .85f); tail.lineTo(tx + ts * .45f, by - 1f); tail.close();
        c.drawPath(tail, fill);
        stroke.setAlpha((int) (255 * a)); c.drawLine(tx - ts * .45f, by, Math.max(left + ts * .5f, Math.min(left + bw - ts * .5f, ax)), by + gapY * .85f, stroke);
        c.drawLine(tx + ts * .45f, by, Math.max(left + ts * .5f, Math.min(left + bw - ts * .5f, ax)), by + gapY * .85f, stroke);
        fill.setAlpha((int) (255 * a)); c.drawRect(tx - ts * .43f, by - stroke.getStrokeWidth() * .6f, tx + ts * .43f, by + stroke.getStrokeWidth() * .6f, fill);   // hides the bubble border under the tail
        txt.setColor(tc); txt.setAlpha((int) (255 * a));
        for (int i = 0; i < nRows; i++) c.drawText(rows[i], left + pad, top + pad * .6f + lh * (i + .8f), txt);
        c.restore();
    }
}
