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

import org.junit.jupiter.api.Test;

/** Hand-computed rows: row = round(45 - 44 v) clamped to [1, 89]. */
class LaneGeometryTest {

    @Test
    void yOfKnownAnswers() {
        assertEquals(45, LaneGeometry.yOf(0.0));
        assertEquals(1, LaneGeometry.yOf(1.0));
        assertEquals(89, LaneGeometry.yOf(-1.0));
        assertEquals(12, LaneGeometry.yOf(0.75));   // 45 - 33 = 12
        assertEquals(56, LaneGeometry.yOf(-0.25));  // 45 + 11 = 56
        assertEquals(36, LaneGeometry.yOf(0.2));    // 36.2
        assertEquals(27, LaneGeometry.yOf(0.4));    // 27.4
        assertEquals(19, LaneGeometry.yOf(0.6));    // 18.6
        assertEquals(10, LaneGeometry.yOf(0.8));    // 9.8
        assertEquals(67, LaneGeometry.yOf(-0.5));   // 67
        assertEquals(63, LaneGeometry.yOf(-0.4));   // 62.6
    }

    @Test
    void halvesRoundUp() {
        assertEquals(40, LaneGeometry.yOf(0.125));  // 39.5 -> 40
        assertEquals(51, LaneGeometry.yOf(-0.125)); // 50.5 -> 51
    }

    @Test
    void outOfRangeClampsAndNanIsTheCentre() {
        assertEquals(1, LaneGeometry.yOf(3.5));
        assertEquals(89, LaneGeometry.yOf(-7.0));
        assertEquals(1, LaneGeometry.yOf(Float.MAX_VALUE));
        assertEquals(89, LaneGeometry.yOf(-Float.MAX_VALUE));
        assertEquals(45, LaneGeometry.yOf(Double.NaN));
    }

    @Test
    void peakBandRunsFromTheMaximumRowToTheMinimumRowInclusive() {
        LaneGeometry.Band b = LaneGeometry.peakBand(-0.4f, 0.8f);
        assertEquals(10, b.top());
        assertEquals(63, b.bottom());
        assertEquals(54, b.height());
    }

    @Test
    void aFlatColumnStillHasOneRow() {
        LaneGeometry.Band b = LaneGeometry.peakBand(0.75f, 0.75f);
        assertEquals(12, b.top());
        assertEquals(12, b.bottom());
        assertEquals(1, b.height());
        LaneGeometry.Band silence = LaneGeometry.peakBand(0f, 0f);
        assertEquals(45, silence.top());
        assertEquals(1, silence.height());
    }

    @Test
    void rmsBandIsClampedInsideThePeakBand() {
        // peak -0.4..0.8 = rows 10..63; rms = sqrt(0.2) = 0.4472 -> rows 25 (25.3) and 65 (64.7), bottom clamped to 63
        LaneGeometry.Band peak = LaneGeometry.peakBand(-0.4f, 0.8f);
        LaneGeometry.Band rms = LaneGeometry.rmsBand(peak, 0.4472136f);
        assertEquals(25, rms.top());
        assertEquals(63, rms.bottom());
    }

    @Test
    void rmsBandOfASymmetricColumn() {
        // peak +-0.5 = rows 23..67; rms 0.25 -> rows 34 (34.0) and 56 (56.0)
        LaneGeometry.Band rms = LaneGeometry.rmsBand(LaneGeometry.peakBand(-0.5f, 0.5f), 0.25f);
        assertEquals(34, rms.top());
        assertEquals(56, rms.bottom());
    }

    @Test
    void rmsBandOfAConstantColumnIsItsSingleRow() {
        LaneGeometry.Band peak = LaneGeometry.peakBand(0.75f, 0.75f);
        LaneGeometry.Band rms = LaneGeometry.rmsBand(peak, 0.75f);
        assertEquals(12, rms.top());
        assertEquals(12, rms.bottom());
    }

    @Test
    void dotsFromSixPixelsPerFrame() {
        assertTrue(LaneGeometry.showDots(1.0 / 6.0));
        assertTrue(LaneGeometry.showDots(1.0 / 16.0));
        assertTrue(LaneGeometry.showDots(TimeAxis.MIN_FRAMES_PER_PIXEL));
        assertFalse(LaneGeometry.showDots(0.17));
        assertFalse(LaneGeometry.showDots(0.5));
        assertFalse(LaneGeometry.showDots(1.0));
    }

    @Test
    void onlyExactZeroIsSilent() {
        assertTrue(LaneGeometry.isSilent(0f, 0f));
        assertFalse(LaneGeometry.isSilent(0f, 0.0001f));
        assertFalse(LaneGeometry.isSilent(-0.0001f, 0f));
        assertFalse(LaneGeometry.isSilent(-0.5f, 0.5f));
    }

    @Test
    void constantsAgree() {
        assertEquals(91, LaneGeometry.ROW_STRIDE);
        assertEquals(90, LaneGeometry.SEPARATOR_ROW);
        assertThrows(IllegalArgumentException.class, () -> new LaneGeometry.Band(5, 4));
    }
}
