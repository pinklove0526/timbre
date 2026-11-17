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

/**
 * Maps mouse wheel deltas to a zoom factor and to a horizontal scroll distance. One wheel notch is 40
 * units; touchpads report smaller deltas that scale smoothly.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class WheelMapping {

    /** Wheel units of one notch. */
    public static final double UNITS_PER_NOTCH = 40.0;

    private WheelMapping() {
    }

    /**
     * Returns the zoom factor of a wheel delta: {@code 2^(deltaY / 40)}. Wheel up (positive delta) gives a
     * factor above 1, which zooms in.
     *
     * @param deltaY the vertical wheel delta in units (any finite value)
     * @return the factor, greater than 0 ({@code 1.0} for a delta of 0)
     * @throws IllegalArgumentException if {@code deltaY} is not finite
     */
    public static double zoomFactor(double deltaY) {
        requireFinite(deltaY);
        return Math.pow(2.0, deltaY / UNITS_PER_NOTCH);
    }

    /**
     * Returns the horizontal scroll distance of a wheel delta: {@code -(delta / 40) * visibleFrames / 10},
     * rounded with {@link Math#round(double)} (halves round toward positive infinity). The sign is the
     * scroll direction: wheel up (positive delta) gives a negative distance, toward earlier time.
     *
     * @param delta         the wheel delta in units (any finite value)
     * @param visibleFrames the frames shown by the window, at least 0
     * @return the change of the scroll frame, in whole frames
     * @throws IllegalArgumentException if {@code delta} is not finite or {@code visibleFrames} is negative
     */
    public static long scrollFrames(double delta, long visibleFrames) {
        requireFinite(delta);
        if (visibleFrames < 0) {
            throw new IllegalArgumentException("visibleFrames must be >= 0: " + visibleFrames);
        }
        return Math.round(-(delta / UNITS_PER_NOTCH) * visibleFrames / 10.0);
    }

    private static void requireFinite(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("delta must be finite: " + v);
        }
    }
}
