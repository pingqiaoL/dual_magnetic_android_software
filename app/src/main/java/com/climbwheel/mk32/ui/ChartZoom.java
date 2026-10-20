package com.climbwheel.mk32.ui;

/** Shared time-window math for button zoom, pinch zoom, dragging and hit testing. */
public final class ChartZoom {
    public static final long DEFAULT_MS = 10_000;
    public static final long MIN_MS = 1_000;
    public static final long MAX_MS = 30 * 60_000;

    private ChartZoom() { }

    public static long scale(long windowMs, float factor) {
        if (Float.isNaN(factor) || Float.isInfinite(factor) || factor <= 0) return windowMs;
        return Math.max(MIN_MS, Math.min(MAX_MS, Math.round(windowMs / (double) factor)));
    }

    public static long anchoredEnd(long endMs, long oldWindow, long newWindow, float fraction) {
        double focus = Math.max(0, Math.min(1, fraction));
        return endMs + Math.round((newWindow - oldWindow) * (1 - focus));
    }
}
