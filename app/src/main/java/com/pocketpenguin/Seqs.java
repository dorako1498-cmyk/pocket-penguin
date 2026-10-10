package com.pocketpenguin;

/**
 * Behaviour sequences. A sequence is a fixed list of steps (anticipation -> main action -> after-glow),
 * so the penguin looks like it is "doing something" rather than playing random states.
 * All arrays are built once; nothing is allocated while choosing or playing a sequence.
 */
final class Seqs {
    // where a moving step is headed
    static final int G_NONE = 0, G_RANDOM = 1, G_WINDOW = 2, G_BED = 3, G_FOOD = 4, G_WATER = 5,
            G_CUSHION = 6, G_CHARGER = 7, G_TOUCH = 8, G_EDGE = 9, G_ENTER = 10, G_KEEP = 11, G_BALL = 12,
            G_PACE = 13;   // a spot a little beside the bowl the penguin is begging at (Brain.begGoal)
    static final int F_JITTER = 1;   // randomise the duration (x0.75 .. x1.4)

    // autonomous sequences (0..SHY_MOMENT are chosen by Brain.think)
    static final int WANDER = 0, LOOK_AROUND = 1, CLOCK_CHECK = 2, SLIP = 3, JUMP_PLAY = 4, BIG_JUMP_PLAY = 5,
            FLAP_HAPPY = 6, SNACK = 7, COZY_SIT = 8, BED_ROUTINE = 9, MORNING = 10, RAIN_WATCH = 11,
            SNOW_EXCITED = 12, STARGAZE_SEQ = 13, EDGE_EXIT = 14, NAP_NODDING = 15, LOOK_WING = 16,
            LOOK_FEET = 17, SPIN_SEQ = 18, WINDOW_LONG = 19, STRETCH_ROUTINE = 20, SIT_REST = 21,
            SLEEP_MORE = 22, WAKE_ROUTINE = 23, SEEK_ATTENTION = 24, FOLLOW_TOUCH_SEQ = 25, CHARGE_REST = 26,
            SHY_MOMENT = 27;
    // reactions (triggered by touch / phone events)
    static final int R_HEAD_PAT = 28, R_BELLY = 29, R_MULTI = 30, R_PET = 31, R_GREET = 32, R_WAKE_GREET = 33,
            R_TOUCH = 34, R_CHARGE_START = 35, R_CHARGE_END = 36, R_TAP_JUMP = 37, R_TAP_FLAP = 38,
            R_TAP_TILT = 39, R_WAKE = 40, RIDE = 41,
            EAT_MEAL = 42, DRINK_WATER = 43, BEG_FOOD = 44, R_FED = 45, R_WATERED = 46, BALL_PLAY = 47,
            BEG_WATER = 48, WANT_PLAY = 49, R_PLAY = 50, R_REFUSE = 51,
            // emotions + shared scenes with Jinbei (v0.11; the scenes are directed by the engine, see PenguinWallpaperService.Drama)
            R_ANGRY = 52, R_SULK = 53, R_SAD = 54, R_CRY = 55, R_LAUGH = 56, DANCE_SEQ = 57, R_SCUFFLE = 58,
            R_APPROACH = 59, R_CHASE = 60, HOLD = 61, R_MAKEUP = 62,
            // v0.12: fetching a thrown fish, hide-and-seek, kicking the ball to Jinbei, popping bubbles, yawning, brushing teeth, found!
            R_FETCH = 63, R_HIDE = 64, R_KICK = 65, R_POP = 66, R_YAWN = 67, R_BRUSH = 68, R_FOUND = 69;   // care + toy sequences (started explicitly by Brain / the engine)   // RIDE: riding on the whale shark's back (started by Buddy, never picked by Brain.think)
    static final int COUNT = 70;

    static final Step[][] ALL = new Step[COUNT][];

    private static Step st(State s, int ms) { return new Step(s, ms, G_NONE, 0); }
    private static Step st(State s, int ms, int goal) { return new Step(s, ms, goal, 0); }
    private static Step st(State s, int ms, int goal, int flag) { return new Step(s, ms, goal, flag); }
    private static Step[] a(Step... s) { return s; }

    /** "Go to X" building block: anticipation -> walk -> settle. */
    private static Step[] go(int goal, State walk, int timeout) {
        return a(st(State.START_WALK, 260, goal), st(walk, timeout, G_KEEP), st(State.STOP_WALK, 380));
    }
    private static Step[] cat(Step[]... parts) {
        int n = 0; for (Step[] p : parts) n += p.length;
        Step[] r = new Step[n]; int i = 0;
        for (Step[] p : parts) for (Step s : p) r[i++] = s;
        return r;
    }

    private static Step[] beg(int bowl) {
        return cat(go(bowl, State.WALK, 6000), a(st(State.LOOK_AT_FEET, 1300), st(State.LOOK_USER, 1100)),
                go(G_PACE, State.WALK, 2500), a(st(State.LOOK_USER, 900), st(State.TILT_HEAD, 800)),
                go(G_PACE, State.WALK, 2500), a(st(State.LOOK_AT_FEET, 1000), st(State.LOOK_USER, 1200), st(State.CONFUSED, 1200),
                        st(State.SIT, 2500, G_NONE, F_JITTER), st(State.LOOK_USER, 1300), st(State.SAD, 2200)));
    }

    static {
        ALL[WANDER] = cat(go(G_RANDOM, State.WALK, 5000),
                a(st(State.LOOK_UP, 1000), st(State.TILT_HEAD, 900), st(State.BLINK, 240)));
        ALL[LOOK_AROUND] = a(st(State.LOOK_LEFT, 900), st(State.LOOK_RIGHT, 1000), st(State.LOOK_UP, 800),
                st(State.BLINK, 230), st(State.TILT_HEAD, 800));
        ALL[CLOCK_CHECK] = a(st(State.LOOK_AT_CLOCK, 1800), st(State.TILT_HEAD, 800), st(State.BLINK, 230));
        ALL[SLIP] = a(st(State.START_WALK, 250, G_RANDOM), st(State.WALK, 1300, G_KEEP), st(State.SLIDE, 800),
                st(State.FALL, 1500), st(State.CONFUSED, 1100), st(State.GET_UP, 1500), st(State.SHAKE_BODY, 900),
                st(State.LOOK_USER, 800));
        ALL[JUMP_PLAY] = a(st(State.JUMP, 1250), st(State.HAPPY, 900));
        ALL[BIG_JUMP_PLAY] = a(st(State.EXCITED, 900), st(State.BIG_JUMP, 1700), st(State.HAPPY, 1000),
                st(State.SHAKE_BODY, 800));
        ALL[FLAP_HAPPY] = a(st(State.FLAP, 1500), st(State.HAPPY, 900), st(State.LOOK_USER, 600));
        ALL[SNACK] = cat(go(G_FOOD, State.WALK, 6000),
                a(st(State.EAT, 3200), st(State.LOOK_USER, 700)),
                go(G_WATER, State.WALK, 3000),
                a(st(State.DRINK, 2400), st(State.HAPPY, 1000)));
        ALL[COZY_SIT] = cat(go(G_CUSHION, State.WALK, 6000),
                a(st(State.SIT, 4500, G_NONE, F_JITTER), st(State.YAWN, 1500), st(State.BLINK, 240), st(State.SIT, 2500)));
        ALL[BED_ROUTINE] = a(st(State.YAWN, 1400), st(State.LOOK_AT_CLOCK, 1500), st(State.SLEEPY, 1300),
                st(State.START_WALK, 300, G_BED), st(State.WALK_TO_BED, 9000, G_KEEP), st(State.STOP_WALK, 420),
                st(State.SIT, 1600), st(State.SLEEP, 45000, G_NONE, F_JITTER));
        ALL[MORNING] = cat(a(st(State.WAKE_UP, 1100), st(State.STRETCH, 2200)),
                go(G_WINDOW, State.WALK, 6000),
                a(st(State.LOOK_WINDOW, 2600), st(State.HAPPY, 900), st(State.START_WALK, 250, G_RANDOM),
                        st(State.WALK_FAST, 3500, G_KEEP), st(State.STOP_WALK, 400)));
        ALL[RAIN_WATCH] = cat(go(G_WINDOW, State.WALK, 7000),
                a(st(State.LOOK_WINDOW, 4200), st(State.TILT_HEAD, 1100), st(State.LOOK_WINDOW, 2200), st(State.BLINK, 240)));
        ALL[SNOW_EXCITED] = cat(go(G_WINDOW, State.WALK_FAST, 6000),
                a(st(State.LOOK_WINDOW, 1400), st(State.EXCITED, 1900), st(State.FLAP, 1600), st(State.JUMP, 1250),
                        st(State.HAPPY, 1000)));
        ALL[STARGAZE_SEQ] = cat(go(G_WINDOW, State.WALK, 7000),
                a(st(State.STARGAZE, 6000), st(State.SIT, 2500), st(State.BLINK, 240)));
        ALL[EDGE_EXIT] = a(st(State.START_WALK, 250, G_EDGE), st(State.EXIT_SCREEN, 8000, G_KEEP),
                st(State.OFF_SCREEN, 2800, G_NONE, F_JITTER), st(State.PEEK_FROM_EDGE, 1900),
                st(State.ENTER_SCREEN, 5000, G_ENTER), st(State.LOOK_USER, 800), st(State.HAPPY, 700));
        ALL[NAP_NODDING] = a(st(State.SIT, 1200), st(State.SLEEPY, 1400), st(State.NOD_OFF, 2600),
                st(State.LOOK_LEFT, 700), st(State.LOOK_RIGHT, 700), st(State.BLINK, 240));
        ALL[LOOK_WING] = a(st(State.LOOK_AT_WING, 2600), st(State.HAPPY, 600));
        ALL[LOOK_FEET] = a(st(State.LOOK_AT_FEET, 2800), st(State.CURIOUS, 900));
        ALL[SPIN_SEQ] = a(st(State.CURIOUS, 400), st(State.SPIN, 1500), st(State.HAPPY, 900));
        ALL[WINDOW_LONG] = cat(go(G_WINDOW, State.WALK, 7000),
                a(st(State.LOOK_WINDOW, 11000, G_NONE, F_JITTER), st(State.BLINK, 240), st(State.SLEEPY, 1200)));
        ALL[STRETCH_ROUTINE] = a(st(State.STRETCH, 2200), st(State.SHAKE_BODY, 800), st(State.LOOK_USER, 700));
        ALL[SIT_REST] = a(st(State.SIT, 5000, G_NONE, F_JITTER), st(State.BREATHE, 1800), st(State.LOOK_LEFT, 800),
                st(State.LOOK_RIGHT, 800), st(State.BLINK, 230));
        ALL[SLEEP_MORE] = a(st(State.SLEEP, 40000, G_NONE, F_JITTER));
        ALL[WAKE_ROUTINE] = a(st(State.WAKE_UP, 1100), st(State.YAWN, 1300), st(State.STRETCH, 2000), st(State.LOOK_USER, 700));
        ALL[SEEK_ATTENTION] = a(st(State.LOOK_USER, 1000), st(State.TILT_HEAD, 800), st(State.HAPPY, 1100));
        ALL[FOLLOW_TOUCH_SEQ] = a(st(State.LOOK_USER, 600), st(State.START_WALK, 250, G_TOUCH),
                st(State.FOLLOW_TOUCH, 4000, G_KEEP), st(State.STOP_WALK, 350), st(State.LOOK_USER, 900), st(State.HAPPY, 800));
        ALL[CHARGE_REST] = a(st(State.REST_WHILE_CHARGING, 40000, G_NONE, F_JITTER));
        ALL[SHY_MOMENT] = a(st(State.SHY, 2200), st(State.BLINK, 240), st(State.LOOK_USER, 700));

        ALL[R_HEAD_PAT] = a(st(State.HEAD_PAT, 3300), st(State.HAPPY, 2600), st(State.LOOK_USER, 700));
        ALL[R_BELLY] = a(st(State.BELLY_TICKLE, 2300), st(State.SURPRISED, 600), st(State.LOOK_USER, 900));
        ALL[R_MULTI] = a(st(State.MULTI_TAP, 2200), st(State.CONFUSED, 1500), st(State.LOOK_USER, 1000));
        ALL[R_PET] = a(st(State.PET, 1000), st(State.VERY_HAPPY, 1800), st(State.HAPPY, 2400), st(State.LOOK_USER, 700));
        ALL[R_GREET] = a(st(State.GREETING_USER, 2000), st(State.HAPPY, 900));
        ALL[R_WAKE_GREET] = a(st(State.WAKE_UP, 1000), st(State.YAWN, 1000), st(State.GREETING_USER, 1800));
        ALL[R_TOUCH] = a(st(State.CURIOUS, 900), st(State.START_WALK, 250, G_TOUCH), st(State.FOLLOW_TOUCH, 5000, G_KEEP),
                st(State.STOP_WALK, 350), st(State.LOOK_USER, 900), st(State.TILT_HEAD, 800), st(State.HAPPY, 700));
        ALL[R_CHARGE_START] = a(st(State.EXCITED, 900), st(State.START_WALK, 250, G_CHARGER),
                st(State.RUN_TO_CHARGER, 8000, G_KEEP), st(State.STOP_WALK, 350),
                st(State.REST_WHILE_CHARGING, 45000, G_NONE, F_JITTER));
        ALL[R_CHARGE_END] = a(st(State.WAKE_UP, 900), st(State.YAWN, 900), st(State.LOOK_USER, 700));
        ALL[R_TAP_JUMP] = a(st(State.JUMP, 1250), st(State.HAPPY, 900));
        ALL[R_TAP_FLAP] = a(st(State.FLAP, 1400), st(State.HAPPY, 900));
        ALL[R_TAP_TILT] = a(st(State.TILT_HEAD, 1200), st(State.HAPPY, 800));
        ALL[RIDE] = a(st(State.JUMP, 900), st(State.HAPPY, 1300), st(State.SIT, 120000));
        ALL[EAT_MEAL] = cat(go(G_FOOD, State.WALK, 6000), a(st(State.EAT, 3600), st(State.HAPPY, 900), st(State.LOOK_USER, 600)));
        ALL[DRINK_WATER] = cat(go(G_WATER, State.WALK, 6000), a(st(State.DRINK, 2800), st(State.HAPPY, 900)));
        // empty bowl: peeks into it, glances at the user, paces up and down in front of it, glances again ...
        ALL[BEG_FOOD] = beg(G_FOOD);
        ALL[BEG_WATER] = beg(G_WATER);
        ALL[R_FED] = cat(a(st(State.EXCITED, 800)), go(G_FOOD, State.WALK_FAST, 6000), a(st(State.EAT, 3400), st(State.HAPPY, 1000), st(State.LOOK_USER, 800)));
        ALL[R_WATERED] = cat(a(st(State.EXCITED, 700)), go(G_WATER, State.WALK_FAST, 6000), a(st(State.DRINK, 2600), st(State.HAPPY, 1000)));
        ALL[BALL_PLAY] = cat(go(G_BALL, State.WALK, 5000), a(st(State.HAPPY, 600)), go(G_BALL, State.WALK_FAST, 4000), a(st(State.JUMP, 1250)),
                go(G_BALL, State.WALK_FAST, 4000), a(st(State.HAPPY, 900), st(State.FLAP, 1200)));
        // bored: goes to the ball, looks at it, looks at the user ("play with me?")
        ALL[WANT_PLAY] = cat(go(G_BALL, State.WALK, 5000), a(st(State.LOOK_AT_FEET, 1200), st(State.LOOK_USER, 1200), st(State.TILT_HEAD, 900),
                st(State.SIT, 2500, G_NONE, F_JITTER), st(State.LOOK_USER, 1200)));
        // the user tapped the ball: chases it, jumps, flaps
        ALL[R_PLAY] = cat(a(st(State.EXCITED, 700)), go(G_BALL, State.WALK_FAST, 4000), a(st(State.JUMP, 1250)),
                go(G_BALL, State.WALK_FAST, 4000), a(st(State.HAPPY, 800)), go(G_BALL, State.WALK_FAST, 4000), a(st(State.JUMP, 1250), st(State.FLAP, 1300), st(State.LOOK_USER, 800)));
        // full: looks at the bowl, shakes itself ("no thanks"), pats down and looks at the user
        ALL[R_REFUSE] = a(st(State.LOOK_AT_FEET, 800), st(State.SHAKE_BODY, 900), st(State.TILT_HEAD, 900), st(State.LOOK_USER, 900));
        ALL[R_ANGRY] = a(st(State.ANGRY, 2800));
        ALL[R_SULK] = a(st(State.SULK, 7000));
        ALL[R_SAD] = a(st(State.SAD, 3000), st(State.LOOK_USER, 900));
        ALL[R_CRY] = a(st(State.CRY, 3200), st(State.SAD, 1600));
        ALL[R_LAUGH] = a(st(State.LAUGH, 2600), st(State.HAPPY, 700));
        ALL[DANCE_SEQ] = a(st(State.DANCE, 5200), st(State.HAPPY, 900), st(State.LOOK_USER, 600));
        ALL[R_SCUFFLE] = a(st(State.SCUFFLE, 3400), st(State.SHAKE_BODY, 800));
        ALL[R_APPROACH] = a(st(State.START_WALK, 250, G_TOUCH), st(State.WALK_FAST, 4500, G_KEEP), st(State.STOP_WALK, 350), st(State.BREATHE, 2500));
        ALL[R_CHASE] = a(st(State.START_WALK, 180, G_TOUCH), st(State.RUN, 3000, G_KEEP), st(State.STOP_WALK, 300), st(State.EXCITED, 900));
        ALL[HOLD] = a(st(State.BREATHE, 700));
        ALL[R_MAKEUP] = a(st(State.SHY, 1600), st(State.VERY_HAPPY, 1900), st(State.HAPPY, 900));
        ALL[R_FETCH] = a(st(State.EXCITED, 500), st(State.START_WALK, 180, G_TOUCH), st(State.RUN, 3500, G_KEEP), st(State.STOP_WALK, 250),
                st(State.EAT, 1900), st(State.HAPPY, 900), st(State.LOOK_USER, 600));
        ALL[R_HIDE] = a(st(State.LOOK_USER, 600), st(State.START_WALK, 200, G_BED), st(State.WALK_FAST, 5000, G_KEEP), st(State.STOP_WALK, 250), st(State.HIDE, 30000));
        ALL[R_KICK] = a(st(State.FLAP, 700), st(State.HAPPY, 500));
        ALL[R_POP] = a(st(State.EXCITED, 500), st(State.JUMP, 1250), st(State.JUMP, 1250), st(State.HAPPY, 600), st(State.JUMP, 1250), st(State.VERY_HAPPY, 1200));
        ALL[R_YAWN] = a(st(State.YAWN, 1600), st(State.BLINK, 300));
        ALL[R_BRUSH] = a(st(State.BRUSH, 5000), st(State.HAPPY, 700));
        ALL[R_FOUND] = a(st(State.SURPRISED, 600), st(State.LAUGH, 2000), st(State.HAPPY, 600));
        ALL[R_WAKE] = a(st(State.WAKE_UP, 1100), st(State.YAWN, 1000), st(State.LOOK_USER, 800));
    }

    static final String[] NAMES = {
        "WANDER", "LOOK_AROUND", "CLOCK_CHECK", "SLIP", "JUMP_PLAY", "BIG_JUMP_PLAY", "FLAP_HAPPY", "SNACK",
        "COZY_SIT", "BED_ROUTINE", "MORNING", "RAIN_WATCH", "SNOW_EXCITED", "STARGAZE", "EDGE_EXIT", "NAP_NODDING",
        "LOOK_WING", "LOOK_FEET", "SPIN", "WINDOW_LONG", "STRETCH_ROUTINE", "SIT_REST", "SLEEP_MORE", "WAKE_ROUTINE",
        "SEEK_ATTENTION", "FOLLOW_TOUCH_SEQ", "CHARGE_REST", "SHY_MOMENT",
        "R_HEAD_PAT", "R_BELLY", "R_MULTI", "R_PET", "R_GREET", "R_WAKE_GREET", "R_TOUCH", "R_CHARGE_START",
        "R_CHARGE_END", "R_TAP_JUMP", "R_TAP_FLAP", "R_TAP_TILT", "R_WAKE", "RIDE",
        "EAT_MEAL", "DRINK_WATER", "BEG_FOOD", "R_FED", "R_WATERED", "BALL_PLAY", "BEG_WATER", "WANT_PLAY", "R_PLAY", "R_REFUSE",
        "R_ANGRY", "R_SULK", "R_SAD", "R_CRY", "R_LAUGH", "DANCE", "R_SCUFFLE", "R_APPROACH", "R_CHASE", "HOLD", "R_MAKEUP", "R_FETCH", "R_HIDE", "R_KICK", "R_POP", "R_YAWN", "R_BRUSH", "R_FOUND" };

    private Seqs() {}
}
