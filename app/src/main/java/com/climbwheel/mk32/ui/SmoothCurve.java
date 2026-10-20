package com.climbwheel.mk32.ui;

/** Shape-preserving tangent for non-uniformly spaced measurement samples. */
public final class SmoothCurve {
    private SmoothCurve() { }

    public static float tangent(float x0, float y0, float x1, float y1,
                                float x2, float y2) {
        float left = x1 - x0;
        float right = x2 - x1;
        if (left <= 0f || right <= 0f) return 0f;
        float a = (y1 - y0) / left;
        float b = (y2 - y1) / right;
        if (a == 0f || b == 0f || Math.signum(a) != Math.signum(b)) return 0f;
        double w1 = 2.0 * right + left;
        double w2 = right + 2.0 * left;
        return (float) ((w1 + w2) / (w1 / a + w2 / b));
    }
}
