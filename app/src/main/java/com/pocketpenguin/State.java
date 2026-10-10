package com.pocketpenguin;

/** Every behaviour the penguin can be in. Most are played as part of a sequence (see Seqs). */
enum State {
    // micro / looking
    IDLE, BREATHE, BLINK, LOOK_LEFT, LOOK_RIGHT, LOOK_UP, LOOK_USER, TILT_HEAD,
    // locomotion
    WALK, START_WALK, STOP_WALK, TURN, WALK_FAST, RUN, WALK_TO_BED, FOLLOW_TOUCH,
    // body
    SIT, YAWN, SLEEP, SLEEPY, WAKE_UP, STRETCH,
    // playful
    FLAP, HAPPY, VERY_HAPPY, JUMP, BIG_JUMP, SLIDE, FALL, GET_UP, SHAKE_BODY, SPIN,
    // feelings
    CURIOUS, SURPRISED, CONFUSED, EXCITED, SHY,
    // touch reactions
    HEAD_PAT, BELLY_TICKLE, MULTI_TAP, PET, GREETING_USER,
    // phone / room awareness
    LOOK_AT_CLOCK, LOOK_WINDOW, STARGAZE, RUN_TO_CHARGER, REST_WHILE_CHARGING,
    EAT, DRINK, NOD_OFF, LOOK_AT_WING, LOOK_AT_FEET,
    // screen edge
    EXIT_SCREEN, OFF_SCREEN, PEEK_FROM_EDGE, ENTER_SCREEN,
    // emotions (v0.11): anger, sulking (back turned), sadness, crying, laughing, dancing, a cartoon scuffle
    ANGRY, SULK, SAD, CRY, LAUGH, DANCE, SCUFFLE,
    // v0.12: hiding (hide-and-seek, crouched behind the bed), brushing teeth
    HIDE, BRUSH;

    boolean isMove() {
        switch (this) {
            case START_WALK: case WALK: case WALK_FAST: case RUN: case WALK_TO_BED:
            case FOLLOW_TOUCH: case RUN_TO_CHARGER: case EXIT_SCREEN: case ENTER_SCREEN:
                return true;
            default: return false;
        }
    }

    /** Target gait amplitude (0 = standing, 1 = normal waddle). */
    float gait() {
        switch (this) {
            case START_WALK: return .65f;
            case WALK: case WALK_TO_BED: case FOLLOW_TOUCH: return 1f;
            case ENTER_SCREEN: return 1.15f;
            case WALK_FAST: case EXIT_SCREEN: return 1.45f;
            case RUN: case RUN_TO_CHARGER: return 1.9f;
            default: return 0f;
        }
    }

    boolean isSleeping() { return this == SLEEP || this == REST_WHILE_CHARGING; }

    /** Quiet poses that can drop to a lower frame rate and allow random micro-animations. */
    boolean isCalm() {
        switch (this) {
            case IDLE: case BREATHE: case BLINK: case LOOK_LEFT: case LOOK_RIGHT: case LOOK_UP:
            case LOOK_USER: case TILT_HEAD: case SIT: case SLEEPY: case LOOK_AT_CLOCK:
            case LOOK_WINDOW: case STARGAZE: case CURIOUS: case LOOK_AT_WING: case LOOK_AT_FEET:
            case EAT: case DRINK: case STOP_WALK: case SULK: case SAD:
                return true;
            default: return false;
        }
    }
}
