package com.pocketpenguin;

/** Damped spring used for every animated part. Different stiffness per part gives lag / overshoot (secondary motion). */
final class Spring {
    float p, v;
    final float w, z;

    Spring(float freqHz, float damping) { w = 6.2831853f * freqHz; z = damping; }
    Spring(float freqHz, float damping, float start) { this(freqHz, damping); p = start; }

    /** Semi-implicit (backward) Euler: unconditionally stable, so even very stiff springs (eyes, lids) never blow up at any frame rate. */
    void update(float target, float dt) {
        final int n = 2; final float h = dt / n;
        for (int i = 0; i < n; i++) {
            v = (v + w * w * (target - p) * h) / (1f + 2f * z * w * h + w * w * h * h);
            p += v * h;
        }
    }
    void snap(float x) { p = x; v = 0f; }
}
