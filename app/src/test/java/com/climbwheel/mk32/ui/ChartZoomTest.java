package com.climbwheel.mk32.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class ChartZoomTest {
    @Test public void zoomInAndOutAreConsistent() {
        assertEquals(5000, ChartZoom.scale(10_000, 2));
        assertEquals(10_000, ChartZoom.scale(5000, .5f));
    }

    @Test public void boundsPreventEmptyOrUnboundedWindows() {
        assertEquals(ChartZoom.MIN_MS, ChartZoom.scale(1000, 100));
        assertEquals(ChartZoom.MAX_MS, ChartZoom.scale(1_800_000, .01f));
    }

    @Test public void invalidPinchDoesNotChangeWindow() {
        assertEquals(10_000, ChartZoom.scale(10_000, Float.NaN));
        assertEquals(10_000, ChartZoom.scale(10_000, 0));
        assertEquals(10_000, ChartZoom.scale(10_000, Float.POSITIVE_INFINITY));
    }

    @Test public void historicalFocusTimeStaysUnderTheFinger() {
        long oldEnd = 100_000;
        long oldWindow = 10_000;
        long newWindow = 5000;
        float fraction = .25f;
        long newEnd = ChartZoom.anchoredEnd(oldEnd, oldWindow, newWindow, fraction);
        assertEquals(oldEnd - oldWindow + Math.round(oldWindow * fraction),
                newEnd - newWindow + Math.round(newWindow * fraction));
    }
}
