package com.climbwheel.mk32.video;

/** Drains an RTSP media backlog without seeking or discarding H.264 reference frames. */
final class LivePlaybackCatchUp {
    static final long TARGET_BUFFER_MS = 180;
    static final long START_BUFFER_MS = 300;
    static final long START_HOLD_MS = 300;
    private static final long FAST_BUFFER_MS = 350;

    private boolean catchingUp;
    private long overThresholdSinceMs = -1;

    float update(long bufferedMs, boolean playbackFlowing, long nowMs) {
        if (!playbackFlowing || bufferedMs <= TARGET_BUFFER_MS) {
            reset();
            return 1f;
        }
        if (!catchingUp) {
            if (bufferedMs < START_BUFFER_MS) {
                overThresholdSinceMs = -1;
                return 1f;
            }
            if (overThresholdSinceMs < 0) overThresholdSinceMs = nowMs;
            if (nowMs - overThresholdSinceMs < START_HOLD_MS) return 1f;
            catchingUp = true;
        }
        return bufferedMs >= FAST_BUFFER_MS ? 1.20f : 1.10f;
    }

    void reset() {
        catchingUp = false;
        overThresholdSinceMs = -1;
    }
}
