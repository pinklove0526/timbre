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
 * Decides how the view follows the playhead during playback: it stays put until the playhead leaves the
 * window or passes 95% of its width, then it jumps so that the playhead is 10% from the left edge (a page
 * scroll).
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class FollowPolicy {

    /** Fraction of the width beyond which the view pages. */
    public static final double PAGE_AT = 0.95;
    /** Fraction of the width at which the playhead lands after a page. */
    public static final double LAND_AT = 0.10;

    private FollowPolicy() {
    }

    /**
     * Returns the scroll frame to use for the current playhead.
     *
     * @param playheadFrame the playhead position in timeline frames, at least 0
     * @param axis          the current time axis
     * @param widthPx       the view width in pixels, greater than 0
     * @param limits        the zoom and scroll limits
     * @return {@code axis.scrollFrame()} while the playhead is inside {@code [0, 0.95 * width]} pixels,
     *         otherwise the scroll frame that puts the playhead 10% from the left, limited to the allowed
     *         scroll range
     * @throws IllegalArgumentException if {@code widthPx} is not positive or {@code playheadFrame} is negative
     */
    public static long scrollFor(long playheadFrame, TimeAxis axis, int widthPx, TimeAxis.Limits limits) {
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(limits, "limits");
        if (widthPx <= 0) {
            throw new IllegalArgumentException("widthPx must be > 0: " + widthPx);
        }
        if (playheadFrame < 0) {
            throw new IllegalArgumentException("playheadFrame must be >= 0: " + playheadFrame);
        }
        double x = axis.xAtFrame(playheadFrame);
        if (x >= 0 && x <= PAGE_AT * widthPx) {
            return axis.scrollFrame();
        }
        long wanted = playheadFrame - Math.round(LAND_AT * widthPx * axis.framesPerPixel());
        return axis.scrolledTo(wanted, limits).scrollFrame();
    }
}
