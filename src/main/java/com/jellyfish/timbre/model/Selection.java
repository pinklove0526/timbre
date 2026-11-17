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
package com.jellyfish.timbre.model;

/**
 * A time range on the project timeline, in frames, applying to all tracks. An empty range
 * ({@code startFrame == endFrame}) is the cursor position.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param startFrame first selected frame, at least 0
 * @param endFrame   frame just after the last selected frame, at least {@code startFrame}
 */
public record Selection(long startFrame, long endFrame) {

    /**
     * Validates the invariants {@code 0 <= startFrame <= endFrame}.
     *
     * @throws IllegalArgumentException if they do not hold
     */
    public Selection {
        if (startFrame < 0) {
            throw new IllegalArgumentException("startFrame must be >= 0: " + startFrame);
        }
        if (endFrame < startFrame) {
            throw new IllegalArgumentException("endFrame " + endFrame + " must be >= startFrame " + startFrame);
        }
    }

    /**
     * Creates the empty selection at a frame.
     *
     * @param frame the cursor frame, at least 0
     * @return an empty selection
     * @throws IllegalArgumentException if {@code frame} is negative
     */
    public static Selection cursor(long frame) {
        return new Selection(frame, frame);
    }

    /**
     * Creates a selection from two frames in any order.
     *
     * @param a one end, at least 0
     * @param b the other end, at least 0
     * @return the selection from the smaller to the larger frame
     * @throws IllegalArgumentException if either frame is negative
     */
    public static Selection between(long a, long b) {
        return new Selection(Math.min(a, b), Math.max(a, b));
    }

    /** @return true if the selection has no length (it is a cursor) */
    public boolean isEmpty() {
        return startFrame == endFrame;
    }

    /** @return the number of selected frames */
    public long length() {
        return endFrame - startFrame;
    }

    /**
     * Returns this selection with both ends limited to {@code [0, maxFrame]}.
     *
     * @param maxFrame the largest allowed frame, at least 0
     * @return the clamped selection (this instance if nothing changes)
     * @throws IllegalArgumentException if {@code maxFrame} is negative
     */
    public Selection clampedTo(long maxFrame) {
        if (maxFrame < 0) {
            throw new IllegalArgumentException("maxFrame must be >= 0: " + maxFrame);
        }
        if (endFrame <= maxFrame) {
            return this;
        }
        return new Selection(Math.min(startFrame, maxFrame), maxFrame);
    }
}
