package com.pocketpenguin;

import android.content.SharedPreferences;

/**
 * Looking after the two pets: hunger / thirst of each, the penguin's mood, the two shared bowls, and how long they have lived together.
 * Everything is stored in SharedPreferences and advanced by REAL time (also while the wallpaper was not running),
 * but never dramatically: values only fall to a gentle floor, nobody starves.
 */
final class Care {
    private final SharedPreferences prefs;
    float pFull = .8f, pHyd = .85f, bFull = .8f, bHyd = .85f;     // 1 = content, 0 = very hungry / thirsty
    float pMood = .75f;                                              // the penguin's mood: 1 = cheerful, 0 = bored / lonely
    float food = .85f, water = .9f;                                  // bowl levels
    long first, lastMs; int feeds, strokes;
    long lastFedMs = -1L;                                            // when the user last filled a bowl
    long lastPlayMs = -1L;                                           // when the user last played with the penguin (ball tap)
    private float saveClock;
    boolean unlockAll;

    Care(SharedPreferences prefs, long nowMs) {
        this.prefs = prefs;
        first = prefs.getLong("care_first", 0L);
        if (first == 0L) { first = nowMs; lastMs = nowMs; save(); }
        lastMs = prefs.getLong("care_last", nowMs);
        pFull = f("care_pf", .8f); pHyd = f("care_ph", .85f); bFull = f("care_bf", .8f); bHyd = f("care_bh", .85f);
        food = f("care_food", .85f); water = f("care_water", .9f); pMood = f("care_pm", .75f);
        feeds = prefs.getInt("care_feeds", 0); strokes = prefs.getInt("care_strokes", 0);
        lastFedMs = prefs.getLong("care_fed", -1L); lastPlayMs = prefs.getLong("care_play", -1L);
        advance(nowMs);
    }
    private float f(String k, float d) { return prefs.getInt(k, Math.round(d * 1000f)) / 1000f; }

    /** Apply the real time that passed (capped at 18 h, floors keep the pets from looking miserable). */
    void advance(long nowMs) {
        float hours = Math.max(0f, Math.min(18f, (nowMs - lastMs) / 3600000f)); lastMs = nowMs;
        if (hours <= 0f) return;
        pFull = Math.max(.12f, pFull - .13f * hours); pHyd = Math.max(.15f, pHyd - .17f * hours);
        bFull = Math.max(.12f, bFull - .10f * hours); bHyd = Math.max(.15f, bHyd - .14f * hours);
        pMood = Math.max(.15f, pMood - .09f * hours);
        water = Math.max(0f, water - .015f * hours);
    }

    /** Called every frame (cheap); saves now and then. */
    void update(float dt, long nowMs) {
        saveClock += dt;
        if (saveClock > 20f) { saveClock = 0f; advance(nowMs); save(); }
    }

    void save() {
        prefs.edit().putLong("care_first", first).putLong("care_last", lastMs).putInt("care_pf", i(pFull)).putInt("care_ph", i(pHyd))
            .putInt("care_bf", i(bFull)).putInt("care_bh", i(bHyd)).putInt("care_food", i(food)).putInt("care_water", i(water))
            .putInt("care_feeds", feeds).putInt("care_strokes", strokes).putLong("care_fed", lastFedMs)
            .putInt("care_pm", i(pMood)).putLong("care_play", lastPlayMs).apply();
    }
    private static int i(float v) { return Math.round(Math.max(0f, Math.min(1f, v)) * 1000f); }

    void reset(long nowMs) {
        pFull = .8f; pHyd = .85f; bFull = .8f; bHyd = .85f; pMood = .75f; food = .85f; water = .9f; first = nowMs; lastMs = nowMs; feeds = 0; strokes = 0; lastFedMs = -1L; lastPlayMs = -1L;
        save(); prefs.edit().putInt("decor_seen", 0).apply();
    }

    // ---- the user
    void fillFood(long nowMs) { if (food < .5f) { feeds++; lastFedMs = nowMs; } food = 1f; save(); }
    void fillWater(long nowMs) { if (water < .5f) { feeds++; lastFedMs = nowMs; } water = 1f; save(); }
    void stroked() { strokes++; if (strokes % 5 == 0) save(); }
    /** The penguin was stroked / patted / fed: a little happier. */
    void cheer(float v) { pMood = Math.min(1f, pMood + v); }
    /** The user started a game with the ball (big mood boost, but not when spammed). */
    void played(long nowMs) { if (lastPlayMs < 0 || nowMs - lastPlayMs > 20000L) cheer(.35f); lastPlayMs = nowMs; save(); }

    // ---- the pets
    boolean foodEmpty() { return food < .08f; }
    boolean waterEmpty() { return water < .08f; }
    // a meal really empties the bowl (penguin + Jinbei finish one serving), so the next meal needs the user again
    void eatP() { if (foodEmpty()) return; pFull = Math.min(1f, pFull + .6f); food = Math.max(0f, food - .6f); cheer(.08f); save(); }
    void drinkP() { if (waterEmpty()) return; pHyd = Math.min(1f, pHyd + .55f); water = Math.max(0f, water - .45f); save(); }
    void eatB() { if (foodEmpty()) return; bFull = Math.min(1f, bFull + .55f); food = Math.max(0f, food - .4f); save(); }
    void drinkB() { if (waterEmpty()) return; bHyd = Math.min(1f, bHyd + .5f); water = Math.max(0f, water - .35f); save(); }
    boolean pHungry() { return pFull < .5f; }
    boolean pThirsty() { return pHyd < .45f; }
    boolean bHungry() { return bFull < .5f; }
    boolean bThirsty() { return bHyd < .45f; }
    boolean pStuffed() { return pFull > .85f; }
    boolean pQuenched() { return pHyd > .85f; }
    boolean pBored() { return pMood < .4f; }
    boolean recentlyFed(long nowMs) { return lastFedMs > 0 && nowMs - lastFedMs < 120000L; }

    /** Days together (0 on the first day). */
    int days(long nowMs) { return (int) Math.max(0L, (nowMs - first) / 86400000L); }
}
