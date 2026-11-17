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

import com.jellyfish.timbre.model.Selection;
import java.util.Objects;
import java.util.Optional;

/**
 * Chooses what Play plays: the selection if there is one, otherwise from the cursor to the end of the
 * project, and from the start if the cursor is at or after the end.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class PlayRangePolicy {

    private PlayRangePolicy() {
    }

    /**
     * A range of timeline frames to play.
     *
     * <p><b>Threading:</b> immutable; safe to share between threads.
     *
     * @param fromFrame first frame to play
     * @param toFrame   frame just after the last frame to play, greater than {@code fromFrame}
     */
    public record Range(long fromFrame, long toFrame) {

        /**
         * Validates {@code 0 <= fromFrame < toFrame}.
         *
         * @throws IllegalArgumentException if the range is empty or negative
         */
        public Range {
            if (fromFrame < 0 || toFrame <= fromFrame) {
                throw new IllegalArgumentException("invalid range [" + fromFrame + ", " + toFrame + ")");
            }
        }
    }

    /**
     * Chooses the range to play.
     *
     * @param selection     the selection (empty = cursor)
     * @param projectFrames the project length in frames, at least 0
     * @return the range, or empty if the project is empty. A non-empty selection plays exactly itself,
     *         clamped to the project (a selection entirely after the end counts as a cursor there);
     *         otherwise the range runs from the cursor to the project end, or from 0 if the cursor is at or
     *         after the end
     * @throws IllegalArgumentException if {@code projectFrames} is negative
     */
    public static Optional<Range> choose(Selection selection, long projectFrames) {
        Objects.requireNonNull(selection, "selection");
        if (projectFrames < 0) {
            throw new IllegalArgumentException("projectFrames must be >= 0: " + projectFrames);
        }
        if (projectFrames == 0) {
            return Optional.empty();
        }
        Selection clamped = selection.clampedTo(projectFrames);
        if (!clamped.isEmpty()) {
            return Optional.of(new Range(clamped.startFrame(), clamped.endFrame()));
        }
        long cursor = clamped.startFrame();
        if (cursor >= projectFrames) {
            return Optional.of(new Range(0, projectFrames));
        }
        return Optional.of(new Range(cursor, projectFrames));
    }
}
