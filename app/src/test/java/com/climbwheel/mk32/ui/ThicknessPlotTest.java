package com.climbwheel.mk32.ui;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class ThicknessPlotTest {
    private ThicknessHistory history() {
        ThicknessHistory history = new ThicknessHistory();
        history.add(1000, 8.2f);
        return history;
    }

    @Test public void expiredReadingReturnsToZeroWithoutInventingMeasurements() {
        ThicknessHistory history = history();
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history.samples(), 1000, 4000);
        assertEquals(0f, points.get(points.size() - 1).value, 0f);
        assertEquals(4000, points.get(points.size() - 1).timeMs);
        assertEquals(1, history.size());
        int measured = 0;
        for (ThicknessPlot.Point point : points) if (point.measured) measured++;
        assertEquals(1, measured);
        assertEquals(2500, points.get(points.size() - 2).timeMs);
    }

    @Test public void currentReadingAndCurveExpireAtTheSameTime() {
        ThicknessHistory history = history();
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history.samples(), 1000, 2400);
        assertTrue(points.get(points.size() - 1).value > 0f);
        points = ThicknessPlot.build(history.samples(), 1000, 2500);
        assertEquals(0f, points.get(points.size() - 1).value, 0f);
    }

    @Test public void zeroBaselineConnectsAcrossMissingDataAndNextRealPointStaysExact() {
        ThicknessHistory history = history();
        history.add(5000, 9.7f);
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history.samples(), 1000, 5000);
        long previous = 0;
        boolean flatZero = false;
        ThicknessPlot.Point last = null;
        for (ThicknessPlot.Point point : points) {
            assertTrue(point.timeMs > previous);
            if (last != null && last.value == 0 && point.value == 0) flatZero = true;
            if (point.measured) assertEquals(point.timeMs == 1000 ? 8.2f : 9.7f, point.value, 0f);
            previous = point.timeMs;
            last = point;
        }
        assertTrue(flatZero);
        assertTrue(last.measured);
        assertEquals(9.7f, last.value, 0f);
    }

    @Test public void freshContinuousSamplesDoNotGetZeroPointsBetweenThem() {
        ThicknessHistory history = history();
        history.add(1500, 8.4f);
        history.add(2000, 8.1f);
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history.samples(), 1000, 2000);
        assertEquals(3, points.size());
        for (ThicknessPlot.Point point : points) assertTrue(point.measured);
    }

    @Test public void expiredPointOutsideViewportStillLeavesAZeroLine() {
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history().samples(), 8000, 18000);
        assertEquals(0f, points.get(points.size() - 1).value, 0f);
        assertEquals(18000, points.get(points.size() - 1).timeMs);
    }

    @Test public void emptyRoundDisplaysZeroWithoutRealDots() {
        ThicknessHistory history = new ThicknessHistory();
        List<ThicknessPlot.Point> points = ThicknessPlot.build(history.samples(), 1000, 3000);
        assertEquals(2, points.size());
        for (ThicknessPlot.Point point : points) {
            assertEquals(0f, point.value, 0f);
            assertFalse(point.measured);
        }
    }
}
