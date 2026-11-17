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
 * The numbers for the horizontal scrollbar of the lane area: it scrolls the timeline in whole frames.
 * {@code max} is the scroll limit (the largest allowed {@code scrollFrame} at the current zoom),
 * {@code visibleAmount} the number of frames the window shows, {@code value} the current scroll frame.
 * Dragging the bar gives a value that {@link #apply(double, TimeAxis, TimeAxis.Limits)} turns back into an
 * axis. When the whole project fits in the window {@code max} is 0 and nothing can be scrolled.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param max            the largest scroll frame, at least 0
 * @param visibleAmount  the frames visible in the window, at least 0
 * @param value          the scroll frame, between 0 and {@code max}
 * @param unitIncrement  the change for one arrow click: a twentieth of the window
 * @param blockIncrement the change for a click in the track: nine tenths of the window
 */
public record ScrollbarMapping(double max, double visibleAmount, double value, double unitIncrement,
        double blockIncrement) {

    /**
     * Computes the scrollbar numbers for an axis.
     *
     * @param axis   the current axis
     * @param limits the limits for the project and window (same sample rate as the axis)
     * @return the mapping
     * @throws IllegalArgumentException if the sample rates differ
     */
    public static ScrollbarMapping of(TimeAxis axis, TimeAxis.Limits limits) {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(limits, "limits");
        if (axis.sampleRate() != limits.sampleRate()) {
            throw new IllegalArgumentException("limits are for " + limits.sampleRate() + " Hz, axis is "
                    + axis.sampleRate() + " Hz");
        }
        long max = limits.maxScrollFrame(axis.framesPerPixel());
        long visible = axis.visibleFrames(limits.widthPx());
        long value = Math.min(axis.scrollFrame(), max);
        return new ScrollbarMapping(max, visible, value, Math.max(1, visible / 20.0), Math.max(1, visible * 0.9));
    }

    /** @return true if there is something to scroll ({@code max > 0}) */
    public boolean scrollable() {
        return max > 0;
    }

    /**
     * Turns a scrollbar value into the axis it stands for.
     *
     * @param barValue the value reported by the scrollbar (any finite number)
     * @param axis     the current axis (its zoom is kept)
     * @param limits   the limits for the project and window
     * @return the axis scrolled to {@code round(barValue)}, limited to the allowed range
     * @throws IllegalArgumentException if {@code barValue} is not finite or the sample rates differ
     */
    public static TimeAxis apply(double barValue, TimeAxis axis, TimeAxis.Limits limits) {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(limits, "limits");
        if (Double.isNaN(barValue) || Double.isInfinite(barValue)) {
            throw new IllegalArgumentException("barValue must be finite: " + barValue);
        }
        return axis.scrolledTo(Math.round(barValue), limits);
    }
}
