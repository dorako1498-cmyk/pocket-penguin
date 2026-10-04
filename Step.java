package com.pocketpenguin;

/** One step of a behaviour sequence: state, duration (ms), optional movement goal and flags. */
final class Step {
    final State s; final int ms; final int goal; final int flag;
    Step(State s, int ms, int goal, int flag) { this.s = s; this.ms = ms; this.goal = goal; this.flag = flag; }
}
