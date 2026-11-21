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
 * {@link ClipWatch} with hand-written block sequences at 44100 Hz. With 1024-frame blocks one second needs 44
 * blocks (43 blocks are 44032 frames, short of 44100), so a full stereo window holds 45056 frames = 90112
 * samples, and 1 % of it is 901.12 samples.
 */
class ClipWatchTest {

    private static final int RATE = 44100;

    private static void blocks(ClipWatch w, int n, int frames, int full) {
        for (int i = 0; i < n; i++) {
            w.add(frames, full);
        }
    }

    @Test
    void shorterThanOneSecondUsesEverythingAndExactlyOnePercentIsNoWarning() {
        // 25 stereo blocks = 25600 frames = 51200 samples; 1 % = 512 exactly
        ClipWatch exact = new ClipWatch(RATE, 2);
        blocks(exact, 23, 1024, 0);
        exact.add(1024, 300); // 300 of 49152: below 1 %
        assertFalse(exact.warning());
        exact.add(1024, 212);
        assertFalse(exact.warning(), "512 of 51200 is exactly 1 %: not a warning");
        assertFalse(exact.everClipped(), "never above 1 % at any block");

        ClipWatch over = new ClipWatch(RATE, 2);
        over.add(1024, 300);
        blocks(over, 9, 1024, 0);
        over.add(1024, 213);
        assertTrue(over.warning(), "513 of 22528 so far is above 1 %");
        blocks(over, 14, 1024, 0);
        assertTrue(over.warning(), "513 of 51200 is above 1 % by one sample");
        assertTrue(over.everClipped());
    }

    @Test
    void fullWindowExactlyOnePercentVersusOneSampleMore() {
        // oldest block A rolls off; the window is B (968 frames) + 43 x 1024 = 45000 frames = 90000 samples
        ClipWatch exact = new ClipWatch(RATE, 2);
        exact.add(1024, 0);
        exact.add(968, 900);
        blocks(exact, 43, 1024, 0);
        assertFalse(exact.warning(), "900 of 90000 is exactly 1 %");

        ClipWatch over = new ClipWatch(RATE, 2);
        over.add(1024, 0);
        over.add(968, 901);
        blocks(over, 43, 1024, 0);
        assertTrue(over.warning(), "901 of 90000 is above 1 %");
    }

    @Test
    void theWindowRollsOffOldBlocks() {
        // A carries 1000 full-scale samples but has rolled off; counting it would give 1900 of 91024
        ClipWatch w = new ClipWatch(RATE, 2);
        w.add(1024, 1000);
        assertTrue(w.warning());
        w.add(968, 900);
        blocks(w, 43, 1024, 0);
        assertFalse(w.warning(), "block A must have left the window");
        assertTrue(w.everClipped(), "latched from the first block");
    }

    @Test
    void clippingFadesOutBlockByBlock() {
        ClipWatch w = new ClipWatch(RATE, 2);
        blocks(w, 44, 1024, 21); // 924 of 90112 = 1.025 %
        assertTrue(w.warning());
        w.add(1024, 0); // 43 clipped blocks: 903 of 90112 > 901.12
        assertTrue(w.warning());
        w.add(1024, 0); // 42 clipped blocks: 882 of 90112
        assertFalse(w.warning());
        assertTrue(w.everClipped());
        blocks(w, 100, 1024, 20); // 880 of 90112 = 0.977 %
        assertFalse(w.warning());
    }

    @Test
    void stereoCountsFramesTimesChannelsInTheDenominator() {
        ClipWatch mono = new ClipWatch(RATE, 1);
        mono.add(1024, 11); // 1100 > 1024
        assertTrue(mono.warning());
        ClipWatch stereo = new ClipWatch(RATE, 2);
        stereo.add(1024, 11); // 1100 < 2048
        assertFalse(stereo.warning());
        stereo.add(1024, 31); // 42 of 4096: 4200 > 4096
        assertTrue(stereo.warning());
        ClipWatch monoTen = new ClipWatch(RATE, 1);
        monoTen.add(1024, 10); // 1000 < 1024
        assertFalse(monoTen.warning());
    }

    @Test
    void oneFrameBlocksFitTheRingAndBlocksLongerThanOneSecondStandAlone() {
        ClipWatch w = new ClipWatch(RATE, 1);
        blocks(w, RATE + 500, 1, 0);
        assertFalse(w.warning());
        w.add(1, 1); // 1 of 44100 in the window
        assertFalse(w.warning());
        w.add(50_000, 501); // alone in the window: 501 of 50000 > 1 %
        assertTrue(w.warning());
        w.add(50_000, 499); // the previous long block rolls off: 499 of 50000
        assertFalse(w.warning());
    }

    @Test
    void zeroFrameBlocksAreIgnoredAndBadCountsRejected() {
        ClipWatch w = new ClipWatch(RATE, 2);
        w.add(0, 0);
        assertFalse(w.warning());
        assertThrows(IllegalArgumentException.class, () -> w.add(10, 21));
        assertThrows(IllegalArgumentException.class, () -> w.add(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> w.add(10, -1));
        assertThrows(IllegalArgumentException.class, () -> new ClipWatch(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClipWatch(RATE, 0));
        assertEquals(0.999, ClipWatch.FULL_SCALE);
    }
}
