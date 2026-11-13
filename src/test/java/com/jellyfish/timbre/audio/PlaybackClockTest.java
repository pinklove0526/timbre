/*
 * Copyright 2026 Jellyfish Ng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jellyfish.timbre.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Synthetic scenarios for {@link PlaybackClock}; the real traces are replayed in PlaybackClockReplayTest. */
class PlaybackClockTest {

    private static final int RATE = 44100;
    private static final int BUFFER = 8820; // 200 ms, so the capped window is 400 ms

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private static long framesAt(long millis) {
        return millis * RATE / 1000;
    }

    private static PlaybackClock started() {
        PlaybackClock c = new PlaybackClock(RATE, BUFFER);
        c.onStart(0);
        return c;
    }

    @Test
    void windowIsTwoBufferDurations() {
        assertEquals(ms(400), new PlaybackClock(RATE, BUFFER).windowNanos());
    }

    @Test
    void idleEstimatesZero() {
        PlaybackClock c = new PlaybackClock(RATE, BUFFER);
        assertEquals(0, c.estimate(ms(10), 5000, 9000));
        c.onStart(0);
        c.estimate(ms(100), 4410, 20000);
        c.onStop();
        assertEquals(0, c.estimate(ms(200), 8820, 20000));
    }

    @Test
    void aDevicePositionThatLeadsByOneBufferIsCappedByElapsedTimeInTheWindow() {
        PlaybackClock c = started();
        // Phase A: within 5 ms of the first write the position reads one full buffer
        assertEquals(framesAt(5), c.estimate(ms(5), 8820, 17408), 1);
        // still flat at 150 ms: the estimate follows elapsed time, not the device
        assertEquals(framesAt(150), c.estimate(ms(150), 8820, 17408), 1);
        assertEquals(framesAt(350), c.estimate(ms(350), 20000, 30000), 1);
    }

    @Test
    void afterTheWindowTheDevicePositionIsTrustedEvenIfItRunsFaster() {
        PlaybackClock c = started();
        for (long t = 5; t <= 395; t += 5) {
            c.estimate(ms(t), Math.min(framesAt(t) + 100, 100000), 100000);
        }
        // device clock 2 % fast: at 1 s the device says 44982 while elapsed time says 44100
        long est = c.estimate(ms(1000), 44982, 100000);
        assertEquals(44982, est);
    }

    @Test
    void neverExceedsFramesWritten() {
        PlaybackClock c = started();
        assertEquals(3000, c.estimate(ms(1000), 40000, 3000));
    }

    @Test
    void extrapolationIsLimitedToOneDeviceStep() {
        PlaybackClock c = started();
        c.estimate(ms(500), 22050, 100000);
        c.estimate(ms(520), 22050 + 882, 100000); // a 20 ms step
        long soon = c.estimate(ms(530), 22932, 100000);
        assertEquals(22932 + framesAt(10), soon, 1);
        long late = c.estimate(ms(2000), 22932, 100000);
        assertEquals(22932 + 882, late);
    }

    @Test
    void neverDecreasesWithinARun() {
        PlaybackClock c = started();
        long a = c.estimate(ms(500), 30000, 100000);
        long b = c.estimate(ms(510), 100, 100000);
        assertTrue(b >= a, a + " then " + b);
    }

    @Test
    void pauseFreezesTheEstimateWhateverTheDeviceReports() {
        PlaybackClock c = started();
        c.estimate(ms(900), framesAt(900), 100000);
        long before = c.estimate(ms(1000), framesAt(1000), 100000);
        c.onPause(ms(1000));
        // Phase A: at stop() the position jumped one buffer ahead
        assertEquals(before, c.estimate(ms(1010), framesAt(1000) + BUFFER, 100000));
        assertEquals(before, c.estimate(ms(1500), framesAt(1000) + BUFFER, 100000));
    }

    @Test
    void resumeContinuesFromTheFrozenValueAndTheWindowAppliesAgain() {
        PlaybackClock c = started();
        c.estimate(ms(900), framesAt(900), 100000);
        long frozen = c.estimate(ms(1000), framesAt(1000), 100000);
        c.onPause(ms(1000));
        c.onResume(ms(1500));
        // the device is stuck one buffer ahead after the restart; elapsed playing time is 1000 ms + 5 ms
        long est = c.estimate(ms(1505), framesAt(1000) + BUFFER, 100000);
        assertTrue(est >= frozen, "must not go back");
        assertEquals(framesAt(1005), est, 1);
        // 100 ms later, still inside the window
        assertEquals(framesAt(1100), c.estimate(ms(1600), framesAt(1000) + BUFFER, 100000), 1);
    }

    @Test
    void invalidTransitionsAndArgumentsAreRejected() {
        PlaybackClock idle = new PlaybackClock(RATE, BUFFER);
        assertThrows(IllegalStateException.class, () -> idle.onPause(0));
        assertThrows(IllegalStateException.class, () -> idle.onResume(0));
        PlaybackClock playing = started();
        assertThrows(IllegalStateException.class, () -> playing.onResume(1));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackClock(0, BUFFER));
        assertThrows(IllegalArgumentException.class, () -> new PlaybackClock(RATE, 0));
    }
}
