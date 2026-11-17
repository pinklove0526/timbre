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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ScrollbarMappingTest {

    // 44100 Hz, project of 100000 frames, window 1000 px.
    private static final TimeAxis.Limits LIMITS = new TimeAxis.Limits(44100, 100_000, 1000);

    @Test
    void knownAnswerAtTwoFramesPerPixel() {
        // visible = 1000 * 2 = 2000 frames; max scroll = 100000 - 2000/10 = 99800
        TimeAxis axis = new TimeAxis(44100, 2.0, 12_345);
        ScrollbarMapping m = ScrollbarMapping.of(axis, LIMITS);
        assertEquals(99_800.0, m.max(), 0.0);
        assertEquals(2_000.0, m.visibleAmount(), 0.0);
        assertEquals(12_345.0, m.value(), 0.0);
        assertEquals(100.0, m.unitIncrement(), 1e-9);
        assertEquals(1_800.0, m.blockIncrement(), 1e-9);
        assertTrue(m.scrollable());
    }

    @Test
    void wholeProjectFitsNothingToScroll() {
        // 100 frames per pixel: visible 100000 frames = the whole project
        TimeAxis axis = new TimeAxis(44100, 100.0, 0);
        ScrollbarMapping m = ScrollbarMapping.of(axis, LIMITS);
        assertEquals(0.0, m.max(), 0.0);
        assertEquals(100_000.0, m.visibleAmount(), 0.0);
        assertEquals(0.0, m.value(), 0.0);
        assertFalse(m.scrollable());
        assertEquals(0, ScrollbarMapping.apply(5_000.0, axis, LIMITS).scrollFrame());
    }

    @Test
    void endLimitIsEnforcedOnApply() {
        TimeAxis axis = new TimeAxis(44100, 2.0, 0);
        assertEquals(99_800, ScrollbarMapping.apply(150_000.0, axis, LIMITS).scrollFrame());
        assertEquals(99_800, ScrollbarMapping.apply(99_800.0, axis, LIMITS).scrollFrame());
        assertEquals(0, ScrollbarMapping.apply(-40.0, axis, LIMITS).scrollFrame());
    }

    @Test
    void applyRoundsToTheNearestFrame() {
        TimeAxis axis = new TimeAxis(44100, 2.0, 0);
        assertEquals(1235, ScrollbarMapping.apply(1234.6, axis, LIMITS).scrollFrame());
        assertEquals(1234, ScrollbarMapping.apply(1234.4, axis, LIMITS).scrollFrame());
        assertEquals(2.0, ScrollbarMapping.apply(1234.6, axis, LIMITS).framesPerPixel(), 0.0);
    }

    @Test
    void roundTripWithinOneFrame() {
        Random rnd = new Random(7);
        for (int i = 0; i < 2000; i++) {
            long frames = 1 + rnd.nextInt(5_000_000);
            int width = 100 + rnd.nextInt(2000);
            TimeAxis.Limits limits = new TimeAxis.Limits(48000, frames, width);
            double fpp = limits.clampFramesPerPixel(Math.pow(2.0, rnd.nextDouble() * 20 - 5));
            long scroll = rnd.nextInt((int) Math.min(Integer.MAX_VALUE, limits.maxScrollFrame(fpp) + 1));
            TimeAxis axis = new TimeAxis(48000, fpp, scroll);
            ScrollbarMapping m = ScrollbarMapping.of(axis, limits);
            long back = ScrollbarMapping.apply(m.value(), axis, limits).scrollFrame();
            assertTrue(Math.abs(back - scroll) <= 1, "scroll " + scroll + " came back as " + back);
            assertTrue(m.value() >= 0 && m.value() <= m.max());
        }
    }

    @Test
    void rejectsBadInput() {
        TimeAxis axis = new TimeAxis(44100, 2.0, 0);
        assertThrows(IllegalArgumentException.class, () -> ScrollbarMapping.apply(Double.NaN, axis, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> ScrollbarMapping.apply(Double.POSITIVE_INFINITY, axis, LIMITS));
        assertThrows(IllegalArgumentException.class,
                () -> ScrollbarMapping.of(new TimeAxis(48000, 2.0, 0), LIMITS));
        assertThrows(NullPointerException.class, () -> ScrollbarMapping.of(null, LIMITS));
    }
}
