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

class WheelMappingTest {

    @Test
    void zoomFactorKnownAnswers() {
        assertEquals(2.0, WheelMapping.zoomFactor(40), 1e-12);
        assertEquals(0.5, WheelMapping.zoomFactor(-40), 1e-12);
        assertEquals(Math.sqrt(2.0), WheelMapping.zoomFactor(20), 1e-12);
        assertEquals(1.0, WheelMapping.zoomFactor(0), 0.0);
        assertEquals(4.0, WheelMapping.zoomFactor(80), 1e-12);
        assertEquals(0.25, WheelMapping.zoomFactor(-80), 1e-12);
        assertEquals(Math.pow(2.0, 0.075), WheelMapping.zoomFactor(3), 1e-12); // a touchpad delta of 3
    }

    @Test
    void scrollFramesKnownAnswers() {
        assertEquals(-4410, WheelMapping.scrollFrames(40, 44100));
        assertEquals(4410, WheelMapping.scrollFrames(-40, 44100));
        assertEquals(-2205, WheelMapping.scrollFrames(20, 44100));
        assertEquals(0, WheelMapping.scrollFrames(0, 44100));
        assertEquals(-8820, WheelMapping.scrollFrames(80, 44100));
    }

    @Test
    void scrollFramesRounding() {
        assertEquals(-4410, WheelMapping.scrollFrames(40, 44101));  // -4410.1
        assertEquals(-4411, WheelMapping.scrollFrames(40, 44106));  // -4410.6
        assertEquals(-4, WheelMapping.scrollFrames(40, 45));        // -4.5 rounds toward +infinity
        assertEquals(5, WheelMapping.scrollFrames(-40, 45));        // +4.5
        assertEquals(-1, WheelMapping.scrollFrames(1, 300));        // -(1/40) * 300 / 10 = -0.75
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> WheelMapping.zoomFactor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> WheelMapping.zoomFactor(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> WheelMapping.scrollFrames(Double.NaN, 10));
        assertThrows(IllegalArgumentException.class, () -> WheelMapping.scrollFrames(40, -1));
    }
}
