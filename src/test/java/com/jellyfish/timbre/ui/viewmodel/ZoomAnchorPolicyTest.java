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

/** Axis: 44100 Hz, 100 frames per pixel, scroll 20000 (so frame f is at x = (f - 20000) / 100); width 1000. */
class ZoomAnchorPolicyTest {

    private static final TimeAxis AXIS = new TimeAxis(44100, 100.0, 20_000);

    @Test
    void aVisibleCursorIsTheAnchor() {
        assertEquals(125.0, ZoomAnchorPolicy.anchorX(32_500, AXIS, 1000), 1e-9);
    }

    @Test
    void aCursorExactlyAtTheLeftEdgeIsVisible() {
        assertEquals(0.0, ZoomAnchorPolicy.anchorX(20_000, AXIS, 1000), 0.0);
    }

    @Test
    void aCursorExactlyAtTheWidthIsOutside() {
        // frame 120000 is at x = 1000
        assertEquals(500.0, ZoomAnchorPolicy.anchorX(120_000, AXIS, 1000), 0.0);
        assertEquals(999.9, ZoomAnchorPolicy.anchorX(119_990, AXIS, 1000), 1e-9);
    }

    @Test
    void aCursorLeftOfTheViewGivesTheMiddle() {
        assertEquals(500.0, ZoomAnchorPolicy.anchorX(5_000, AXIS, 1000), 0.0);
        assertEquals(500.0, ZoomAnchorPolicy.anchorX(0, AXIS, 1000), 0.0);
    }

    @Test
    void aCursorRightOfTheViewGivesTheMiddle() {
        assertEquals(500.0, ZoomAnchorPolicy.anchorX(900_000, AXIS, 1000), 0.0);
        assertEquals(333.5, ZoomAnchorPolicy.anchorX(900_000, AXIS, 667), 0.0);
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> ZoomAnchorPolicy.anchorX(0, AXIS, 0));
        assertThrows(NullPointerException.class, () -> ZoomAnchorPolicy.anchorX(0, null, 10));
    }
}
