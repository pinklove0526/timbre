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
package com.jellyfish.timbre.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FollowPolicyTest {

    // 44100 Hz, 100 frames per pixel, 1000 px window: 100000 frames visible; project 10 million frames
    private static final TimeAxis.Limits LIMITS = new TimeAxis.Limits(44100, 10_000_000, 1000);

    @Test
    void unchangedWhileThePlayheadIsInsideTheWindow() {
        TimeAxis axis = new TimeAxis(44100, 100, 500_000);
        assertEquals(500_000, FollowPolicy.scrollFor(500_000, axis, 1000, LIMITS), "at x = 0");
        assertEquals(500_000, FollowPolicy.scrollFor(550_000, axis, 1000, LIMITS));
        // x = 950 is exactly 95% of the width: not beyond it
        assertEquals(500_000, FollowPolicy.scrollFor(595_000, axis, 1000, LIMITS));
    }

    @Test
    void pagesWhenThePlayheadPassesNinetyFivePercent() {
        TimeAxis axis = new TimeAxis(44100, 100, 500_000);
        // x = 950.01 -> beyond; the playhead goes to 10% = 100 px = 10000 frames from the left
        assertEquals(595_100 - 10_000, FollowPolicy.scrollFor(595_100, axis, 1000, LIMITS));
        assertEquals(700_000 - 10_000, FollowPolicy.scrollFor(700_000, axis, 1000, LIMITS), "far beyond the edge");
        assertEquals(600_000 - 10_000, FollowPolicy.scrollFor(600_000, axis, 1000, LIMITS), "at the right edge");
    }

    @Test
    void pagesWhenThePlayheadIsLeftOfTheWindow() {
        TimeAxis axis = new TimeAxis(44100, 100, 500_000);
        assertEquals(300_000 - 10_000, FollowPolicy.scrollFor(300_000, axis, 1000, LIMITS));
        assertEquals(0, FollowPolicy.scrollFor(5_000, axis, 1000, LIMITS), "clamped to the start");
        assertEquals(0, FollowPolicy.scrollFor(0, axis, 1000, LIMITS));
    }

    @Test
    void clampedAtTheEndOfTheScrollRange() {
        TimeAxis axis = new TimeAxis(44100, 100, 9_800_000);
        long max = LIMITS.maxScrollFrame(100); // 10_000_000 - 100_000 / 10
        assertEquals(9_990_000, max);
        assertEquals(max, FollowPolicy.scrollFor(10_500_000, axis, 1000, LIMITS));
    }

    @Test
    void argumentChecks() {
        TimeAxis axis = new TimeAxis(44100, 100, 0);
        assertThrows(IllegalArgumentException.class, () -> FollowPolicy.scrollFor(0, axis, 0, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> FollowPolicy.scrollFor(-1, axis, 1000, LIMITS));
        assertThrows(NullPointerException.class, () -> FollowPolicy.scrollFor(0, null, 1000, LIMITS));
    }
}
