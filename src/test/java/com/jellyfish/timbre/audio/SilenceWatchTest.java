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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SilenceWatch} at 44100 Hz: 3 s = 132300 frames = 129 blocks of 1024 (132096) plus 204 frames.
 * -90 dBFS is 3.1623e-5; one 16-bit LSB (1/32768 = 3.05e-5) is below it, two LSB (6.1e-5) are above.
 */
class SilenceWatchTest {

    private static final int RATE = 44100;
    private static final float ONE_LSB = 1f / 32768f;
    private static final float TWO_LSB = 2f / 32768f;

    private static void silent(SilenceWatch w, int blocks) {
        for (int i = 0; i < blocks; i++) {
            w.add(1024, 0f);
        }
    }

    @Test
    void exactlyThreeSecondsWarnsAndOneFrameLessDoesNot() {
        SilenceWatch less = new SilenceWatch(RATE);
        silent(less, 129);
        less.add(203, 0f);
        assertFalse(less.warning(), "132299 frames");
        assertFalse(less.everSilentWarning());

        SilenceWatch exact = new SilenceWatch(RATE);
        silent(exact, 129);
        exact.add(204, ONE_LSB);
        assertTrue(exact.warning(), "132300 frames");
        assertTrue(exact.everSilentWarning());
    }

    @Test
    void aLouderBlockResetsTheAccumulationAndTheLatchStays() {
        SilenceWatch w = new SilenceWatch(RATE);
        silent(w, 100);
        w.add(1024, TWO_LSB); // louder than -90 dBFS: reset
        silent(w, 129);
        assertFalse(w.warning(), "only 132096 silent frames since the reset");
        w.add(204, 0f);
        assertTrue(w.warning());
        w.add(1, 0.5f);
        assertFalse(w.warning(), "sound again");
        assertTrue(w.everSilentWarning(), "latched");
    }

    @Test
    void thresholdIsMinus90DbfsAndNegativePeaksCountByMagnitude() {
        assertEquals(3.1622776601683795E-5, SilenceWatch.THRESHOLD, 1e-18);
        SilenceWatch w = new SilenceWatch(RATE);
        w.add(132_299, 3.16e-5f); // just below
        w.add(1, -3.1e-5f);
        assertTrue(w.warning());
        SilenceWatch loud = new SilenceWatch(RATE);
        loud.add(132_299, 0f);
        loud.add(1, -3.17e-5f); // just above, negative
        assertFalse(loud.warning());
    }

    @Test
    void zeroFrameBlocksChangeNothingAndBadArgumentsAreRejected() {
        SilenceWatch w = new SilenceWatch(RATE);
        w.add(132_299, 0f);
        w.add(0, 0.9f); // ignored: does not reset
        w.add(1, 0f);
        assertTrue(w.warning());
        assertThrows(IllegalArgumentException.class, () -> w.add(-1, 0f));
        assertThrows(IllegalArgumentException.class, () -> new SilenceWatch(0));
    }
}
