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
 * Chooses the pixel column that keeps its time when the keyboard zooms: the cursor if it is visible, else
 * the middle of the lane area.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class ZoomAnchorPolicy {

    private ZoomAnchorPolicy() {
    }

    /**
     * Returns the anchor column.
     *
     * @param cursorFrame the cursor frame, at least 0
     * @param axis        the current axis
     * @param widthPx     the lane area width in pixels, greater than 0
     * @return the x of the cursor if {@code 0 <= x < widthPx}, otherwise {@code widthPx / 2.0}
     * @throws IllegalArgumentException if {@code widthPx} is not positive
     */
    public static double anchorX(long cursorFrame, TimeAxis axis, int widthPx) {
        Objects.requireNonNull(axis, "axis");
        if (widthPx <= 0) {
            throw new IllegalArgumentException("widthPx must be > 0: " + widthPx);
        }
        double x = axis.xAtFrame(cursorFrame);
        return x >= 0 && x < widthPx ? x : widthPx / 2.0;
    }
}
