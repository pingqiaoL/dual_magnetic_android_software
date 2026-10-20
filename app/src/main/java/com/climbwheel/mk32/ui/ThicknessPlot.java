package com.climbwheel.mk32.ui;

import java.util.ArrayList;
import java.util.List;

/** Display-only zero return; synthetic points must never enter measurement storage. */
public final class ThicknessPlot {
    public static final long HOLD_MS = 1500;
    private static final long FALL_MS = 250;

    public static final class Point {
        public final long timeMs;
        public final float value;
        public final boolean measured;

        Point(long timeMs, float value, boolean measured) {
            this.timeMs = timeMs;
            this.value = value;
            this.measured = measured;
        }
    }

    private ThicknessPlot() { }

    public static List<Point> build(Iterable<ThicknessHistory.Sample> samples,
                                    long startMs, long endMs) {
        List<Point> result = new ArrayList<>();
        if (endMs < startMs) return result;
        ThicknessHistory.Sample previous = null;
        for (ThicknessHistory.Sample sample : samples) {
            if (sample.timestampMs > endMs) break;
            if (sample.timestampMs < startMs) { previous = sample; continue; }
            if (result.isEmpty()) {
                if (previous != null) append(result, previous.timestampMs, previous.value, true);
                else append(result, startMs, 0f, false);
            }
            if (previous != null && sample.timestampMs - previous.timestampMs > HOLD_MS) {
                appendTail(result, previous, previous.timestampMs + HOLD_MS);
                // Remain at zero until just before the next real sample.
                append(result, Math.max(previous.timestampMs + HOLD_MS,
                        sample.timestampMs - FALL_MS), 0f, false);
            }
            if (previous == null) append(result, Math.max(startMs, sample.timestampMs - FALL_MS), 0f, false);
            append(result, sample.timestampMs, sample.value, true);
            previous = sample;
        }
        if (result.isEmpty()) {
            if (previous != null) append(result, previous.timestampMs, previous.value, true);
            else append(result, startMs, 0f, false);
        }
        if (previous != null) appendTail(result, previous, endMs);
        else append(result, endMs, 0f, false);
        return result;
    }

    private static void appendTail(List<Point> result, ThicknessHistory.Sample last, long endMs) {
        long fallStart = last.timestampMs + HOLD_MS - FALL_MS;
        long zeroAt = last.timestampMs + HOLD_MS;
        append(result, Math.min(endMs, fallStart), last.value, false);
        if (endMs > fallStart) {
            float value = endMs >= zeroAt ? 0f : last.value * (zeroAt - endMs) / FALL_MS;
            append(result, Math.min(endMs, zeroAt), value, false);
        }
        if (endMs > zeroAt) append(result, endMs, 0f, false);
    }

    private static void append(List<Point> result, long time, float value, boolean measured) {
        if (!result.isEmpty()) {
            Point last = result.get(result.size() - 1);
            if (time < last.timeMs) return;
            if (time == last.timeMs) {
                if (measured) result.set(result.size() - 1, new Point(time, value, true));
                return;
            }
        }
        result.add(new Point(time, value, measured));
    }
}
