package com.climbwheel.mk32.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SmoothCurveTest {
    @Test public void straightMeasurementsKeepTheirSlope() {
        assertEquals(2f, SmoothCurve.tangent(0, 0, 2, 4, 9, 18), .0001f);
    }

    @Test public void peakAndFlatMeasurementsHaveHorizontalTangents() {
        assertEquals(0f, SmoothCurve.tangent(0, 8, 1, 9, 2, 8), 0f);
        assertEquals(0f, SmoothCurve.tangent(0, 8, 1, 8, 2, 9), 0f);
    }

    @Test public void irregularSamplesDoNotCreateFalsePeaks() {
        float[] x = {0, 1, 9, 10, 80, 81};
        float[] y = {8, 8.2f, 8.7f, 8.1f, 8.3f, 8.2f};
        float[] m = new float[x.length];
        m[0] = (y[1] - y[0]) / (x[1] - x[0]);
        m[m.length - 1] = (y[5] - y[4]) / (x[5] - x[4]);
        for (int i = 1; i < x.length - 1; i++) {
            m[i] = SmoothCurve.tangent(x[i - 1], y[i - 1], x[i], y[i], x[i + 1], y[i + 1]);
        }
        for (int i = 0; i < x.length - 1; i++) {
            for (int step = 0; step <= 100; step++) {
                float t = step / 100f;
                float u = 1f - t;
                float h = (x[i + 1] - x[i]) / 3f;
                float value = u * u * u * y[i] + 3 * u * u * t * (y[i] + m[i] * h)
                        + 3 * u * t * t * (y[i + 1] - m[i + 1] * h) + t * t * t * y[i + 1];
                assertTrue(value >= Math.min(y[i], y[i + 1]) - .0001f);
                assertTrue(value <= Math.max(y[i], y[i + 1]) + .0001f);
            }
        }
    }
}
