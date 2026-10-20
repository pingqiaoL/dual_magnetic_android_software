package com.climbwheel.mk32.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LivePlaybackCatchUpTest {
    @Test public void drainsTheObserved625msBacklogWithoutChangingTheSource() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        double bufferedMs = 625;
        boolean caughtUp = false;
        for (long nowMs = 0; nowMs < 8_000; nowMs += 100) {
            float speed = control.update(Math.round(bufferedMs), true, nowMs);
            assertTrue(speed >= 1f && speed <= 1.20f);
            // A realtime source adds 100 ms while the renderer consumes 100 * speed ms.
            bufferedMs += 100 - 100 * speed;
            assertTrue("Must retain a jitter margin", bufferedMs >= 150);
            if (bufferedMs <= LivePlaybackCatchUp.TARGET_BUFFER_MS) {
                caughtUp = true;
                assertEquals(1f, control.update(Math.round(bufferedMs), true, nowMs + 100), 0f);
                break;
            }
        }
        assertTrue("The previous fixed backlog must be consumed", caughtUp);
    }

    @Test public void shortBurstDoesNotTriggerAcceleration() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        assertEquals(1f, control.update(625, true, 0), 0f);
        assertEquals(1f, control.update(625, true, 100), 0f);
        assertEquals(1f, control.update(200, true, 200), 0f);
        // A new burst has to pass the full hold period again.
        assertEquals(1f, control.update(625, true, 300), 0f);
        assertEquals(1f, control.update(625, true, 500), 0f);
        assertEquals(1.20f, control.update(625, true, 600), 0f);
    }

    @Test public void retainsHysteresisUntilTargetIsReached() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        control.update(625, true, 0);
        assertEquals(1.20f, control.update(625, true, 300), 0f);
        assertEquals(1.10f, control.update(250, true, 400), 0f);
        assertEquals(1f, control.update(180, true, 500), 0f);
        assertEquals(1f, control.update(250, true, 600), 0f);
    }

    @Test public void stopsAccelerationWhenInputOrRenderingStops() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        control.update(625, true, 0);
        assertEquals(1.20f, control.update(625, true, 300), 0f);
        assertEquals(1f, control.update(625, false, 400), 0f);
        assertEquals(1f, control.update(625, true, 500), 0f);
        assertEquals(1f, control.update(625, true, 700), 0f);
    }

    @Test public void lowOrInvalidBufferStopsAcceleration() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        control.update(625, true, 0);
        control.update(625, true, 300);
        assertEquals(1f, control.update(0, true, 400), 0f);
        assertEquals(1f, control.update(-1, true, 500), 0f);
        assertEquals(1f, control.update(160, true, 600), 0f);
    }

    @Test public void reconnectDiscardsThePreviousPolicyState() {
        LivePlaybackCatchUp control = new LivePlaybackCatchUp();
        control.update(625, true, 0);
        control.update(625, true, 300);
        control.reset();
        assertEquals(1f, control.update(250, true, 400), 0f);
        assertEquals(1f, control.update(625, true, 500), 0f);
    }
}
