package com.climbwheel.mk32.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class ThicknessHistoryTest {
    @Test public void savedHistoryRetainsTheWholeRecording() {
        ThicknessHistory history = new ThicknessHistory(false);
        history.add(1000, 123f);
        history.add(1000 + ThicknessHistory.RETENTION_MS + 1, 125f);
        assertEquals(2, history.size());
        assertEquals(1000, history.firstTimestampMs());
    }

    @Test public void storesOnlyNewOrderedMeasurements() {
        ThicknessHistory history = new ThicknessHistory();
        assertTrue(history.add(1000, 128.1f));
        assertFalse(history.add(1000, 129f));
        assertFalse(history.add(999, 127f));
        assertTrue(history.add(2000, 128.5f));
        assertEquals(2, history.size());
    }

    @Test public void calculatesVisibleStatsAndNearestPoint() {
        ThicknessHistory history = new ThicknessHistory();
        history.add(1000, 10f);
        history.add(2000, 20f);
        history.add(3000, 30f);
        ThicknessHistory.Stats stats = history.stats(1500, 3500);
        assertEquals(2, stats.count);
        assertEquals(20f, stats.minimum, 0.001f);
        assertEquals(30f, stats.maximum, 0.001f);
        assertEquals(25f, stats.average, 0.001f);
        ThicknessHistory.Sample nearest = history.nearest(2700, 1500, 3500);
        assertNotNull(nearest);
        assertEquals(3000, nearest.timestampMs);
    }

    @Test public void dropsDataOutsideRetentionWindow() {
        ThicknessHistory history = new ThicknessHistory();
        history.add(1000, 1f);
        long newest = 1000 + ThicknessHistory.RETENTION_MS + 1;
        history.add(newest, 2f);
        assertEquals(1, history.size());
        assertEquals(newest, history.firstTimestampMs());
    }

    @Test public void clearRemovesTheCompletedRound() {
        ThicknessHistory history = new ThicknessHistory();
        history.add(1000, 128.1f);
        history.add(2000, 128.4f);
        history.clear();
        assertTrue(history.isEmpty());
        assertEquals(0, history.size());
        assertEquals(0, history.firstTimestampMs());
        assertEquals(0, history.lastTimestampMs());
    }
}
