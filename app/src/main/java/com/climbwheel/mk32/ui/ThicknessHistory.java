package com.climbwheel.mk32.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/** Bounded, time-ordered history of actual THICKNESS message arrivals. */
public final class ThicknessHistory {
    public static final long RETENTION_MS = 30L * 60L * 1000L;
    public static final int MAX_SAMPLES = 20_000;

    public static final class Sample {
        public final long timestampMs;
        public final float value;

        Sample(long timestampMs, float value) {
            this.timestampMs = timestampMs;
            this.value = value;
        }
    }

    public static final class Stats {
        public final int count;
        public final float minimum;
        public final float maximum;
        public final float average;

        Stats(int count, float minimum, float maximum, float average) {
            this.count = count;
            this.minimum = minimum;
            this.maximum = maximum;
            this.average = average;
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final boolean bounded;

    public ThicknessHistory() { this(true); }

    public ThicknessHistory(boolean bounded) { this.bounded = bounded; }

    /** Returns false for a duplicate/out-of-order timestamp or a non-finite value. */
    public boolean add(long timestampMs, float value) {
        if (timestampMs <= 0 || Float.isNaN(value) || Float.isInfinite(value)) return false;
        Sample last = samples.peekLast();
        if (last != null && timestampMs <= last.timestampMs) return false;
        samples.addLast(new Sample(timestampMs, value));
        if (bounded) prune(timestampMs);
        return true;
    }

    private void prune(long newestTimestampMs) {
        long cutoff = newestTimestampMs - RETENTION_MS;
        while (!samples.isEmpty()
                && (samples.size() > MAX_SAMPLES || samples.peekFirst().timestampMs < cutoff)) {
            samples.removeFirst();
        }
    }

    public int size() { return samples.size(); }
    public boolean isEmpty() { return samples.isEmpty(); }
    public void clear() { samples.clear(); }
    public long firstTimestampMs() { return samples.isEmpty() ? 0 : samples.peekFirst().timestampMs; }
    public long lastTimestampMs() { return samples.isEmpty() ? 0 : samples.peekLast().timestampMs; }
    Iterable<Sample> samples() { return samples; }

    public Stats stats(long startMs, long endMs) {
        int count = 0;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        double sum = 0;
        for (Sample sample : samples) {
            if (sample.timestampMs < startMs) continue;
            if (sample.timestampMs > endMs) break;
            count++;
            min = Math.min(min, sample.value);
            max = Math.max(max, sample.value);
            sum += sample.value;
        }
        return count == 0 ? new Stats(0, 0, 0, 0)
                : new Stats(count, min, max, (float) (sum / count));
    }

    public Sample nearest(long timestampMs, long startMs, long endMs) {
        Sample best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Sample sample : samples) {
            if (sample.timestampMs < startMs) continue;
            if (sample.timestampMs > endMs) break;
            long distance = Math.abs(sample.timestampMs - timestampMs);
            if (distance < bestDistance) {
                best = sample;
                bestDistance = distance;
            }
        }
        return best;
    }
}
