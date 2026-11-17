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

import java.util.Objects;

/**
 * The vertical geometry of one channel lane and the pure drawing rules that follow from it, kept free of
 * JavaFX so they can be tested headless.
 *
 * <p>A lane is {@value #LANE_HEIGHT} rows (0 to 89) followed by a {@value #SEPARATOR_HEIGHT} row separator
 * (row 90). The centre row is {@value #CENTER_ROW}; an amplitude {@code v} in [-1, 1] maps to the row
 * {@code clamp(round(45.0 - 44.0 * v), 1, 89)} where {@code round} is {@link Math#round(double)} (halves
 * round up).
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class LaneGeometry {

    /** Height of the waveform area of one lane in rows. */
    public static final int LANE_HEIGHT = 90;
    /** Height of the separator below one lane in rows. */
    public static final int SEPARATOR_HEIGHT = 1;
    /** Rows taken by one lane including its separator. */
    public static final int ROW_STRIDE = LANE_HEIGHT + SEPARATOR_HEIGHT;
    /** The centre row (amplitude 0). */
    public static final int CENTER_ROW = 45;
    /** The row of the separator inside a lane canvas. */
    public static final int SEPARATOR_ROW = LANE_HEIGHT;
    /** Rows between the centre and amplitude 1 (or -1). */
    public static final double HALF_RANGE = 44.0;
    /** The topmost row a waveform may use. */
    public static final int MIN_ROW = 1;
    /** The bottommost row a waveform may use. */
    public static final int MAX_ROW = 89;
    /** Side of the square dot drawn at a sample in pixels. */
    public static final int DOT_SIZE = 3;
    /** Dots are drawn at samples when the zoom is at or below this many frames per pixel (6 px per frame). */
    public static final double DOT_MAX_FRAMES_PER_PIXEL = 1.0 / 6.0;

    private LaneGeometry() {
    }

    /**
     * An inclusive range of rows.
     *
     * <p><b>Threading:</b> immutable; safe to share between threads.
     *
     * @param top    the first row, at most {@code bottom}
     * @param bottom the last row (inclusive)
     */
    public record Band(int top, int bottom) {

        /**
         * Validates the rows.
         *
         * @throws IllegalArgumentException if {@code top > bottom}
         */
        public Band {
            if (top > bottom) {
                throw new IllegalArgumentException("top " + top + " > bottom " + bottom);
            }
        }

        /** @return the number of rows in the band, at least 1 */
        public int height() {
            return bottom - top + 1;
        }
    }

    /**
     * Maps an amplitude to a lane row.
     *
     * @param value the amplitude; values outside [-1, 1] clamp, NaN maps to the centre row
     * @return the row in [1, 89]
     */
    public static int yOf(double value) {
        if (Double.isNaN(value)) {
            return CENTER_ROW;
        }
        double row = CENTER_ROW - HALF_RANGE * value;
        if (row <= MIN_ROW) {
            return MIN_ROW;
        }
        if (row >= MAX_ROW) {
            return MAX_ROW;
        }
        return (int) Math.round(row);
    }

    /**
     * Returns the rows of the peak band of a column: {@code yOf(max)} to {@code yOf(min)} inclusive.
     *
     * @param min the column minimum
     * @param max the column maximum
     * @return the band, at least one row tall (a swapped pair is ordered)
     */
    public static Band peakBand(float min, float max) {
        int a = yOf(max);
        int b = yOf(min);
        return new Band(Math.min(a, b), Math.max(a, b));
    }

    /**
     * Returns the rows of the RMS band inside a peak band: {@code yOf(+rms)} to {@code yOf(-rms)} clamped
     * to the peak band.
     *
     * @param peak the peak band of the same column
     * @param rms  the column RMS, at least 0
     * @return the RMS band, at least one row tall and inside {@code peak}
     */
    public static Band rmsBand(Band peak, float rms) {
        Objects.requireNonNull(peak, "peak");
        int top = Math.max(peak.top(), yOf(rms));
        int bottom = Math.min(peak.bottom(), yOf(-rms));
        if (top > bottom) { // the RMS can never lie outside the peak band; keep one row if rounding says so
            int row = Math.min(Math.max(top, peak.top()), peak.bottom());
            return new Band(row, row);
        }
        return new Band(top, bottom);
    }

    /**
     * Returns whether a column is silent: its minimum and maximum are both exactly 0. Silent columns draw no
     * band (the lane keeps only its centre line), so silence, gaps between clips and a placeholder lane look
     * the same.
     *
     * @param min the column minimum
     * @param max the column maximum
     * @return true if {@code min == 0 && max == 0}
     */
    public static boolean isSilent(float min, float max) {
        return min == 0f && max == 0f;
    }

    /**
     * Returns whether a dot is drawn at every sample at this zoom.
     *
     * @param framesPerPixel the zoom
     * @return true if {@code framesPerPixel <= 1/6}
     */
    public static boolean showDots(double framesPerPixel) {
        return framesPerPixel <= DOT_MAX_FRAMES_PER_PIXEL;
    }
}
