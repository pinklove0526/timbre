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

import java.util.Objects;

/**
 * A run of frames from a {@link SampleSource} placed on a track's timeline.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param source        the audio source
 * @param sourceStart   first source frame used, at least 0
 * @param frameCount    number of frames, greater than 0
 * @param timelineStart position of the clip on the timeline, at least 0
 */
public record Clip(SampleSource source, long sourceStart, long frameCount, long timelineStart) {

    /**
     * Validates the invariants.
     *
     * @throws IllegalArgumentException if any invariant is violated
     */
    public Clip {
        Objects.requireNonNull(source, "source");
        if (sourceStart < 0) {
            throw new IllegalArgumentException("sourceStart must be >= 0: " + sourceStart);
        }
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be > 0: " + frameCount);
        }
        if (sourceStart > source.frameCount() || frameCount > source.frameCount() - sourceStart) {
            throw new IllegalArgumentException("clip range [" + sourceStart + ", " + sourceStart
                    + "+" + frameCount + ") exceeds source length " + source.frameCount());
        }
        if (timelineStart < 0) {
            throw new IllegalArgumentException("timelineStart must be >= 0: " + timelineStart);
        }
        if (timelineStart > Long.MAX_VALUE - frameCount) {
            throw new IllegalArgumentException("timeline end overflows");
        }
    }

    /** @return the timeline frame just after the last frame of this clip */
    public long timelineEnd() {
        return timelineStart + frameCount;
    }

    /**
     * Returns a copy of this clip at another timeline position.
     *
     * @param newTimelineStart new timeline start, at least 0
     * @return the moved clip
     * @throws IllegalArgumentException if the position is invalid
     */
    public Clip withTimelineStart(long newTimelineStart) {
        return new Clip(source, sourceStart, frameCount, newTimelineStart);
    }

    /**
     * Splits this clip at a timeline frame strictly inside it. The two halves cover
     * exactly the original source range, with no gap or overlap.
     *
     * @param timelineFrame split position, {@code timelineStart < frame < timelineEnd()}
     * @return the left and right halves
     * @throws IllegalArgumentException if the position is not strictly inside the clip
     */
    public Split split(long timelineFrame) {
        if (timelineFrame <= timelineStart || timelineFrame >= timelineEnd()) {
            throw new IllegalArgumentException("split position " + timelineFrame
                    + " must be strictly inside (" + timelineStart + ", " + timelineEnd() + ")");
        }
        long leftLength = timelineFrame - timelineStart;
        Clip left = new Clip(source, sourceStart, leftLength, timelineStart);
        Clip right = new Clip(source, sourceStart + leftLength, frameCount - leftLength, timelineFrame);
        return new Split(left, right);
    }

    /**
     * The two halves produced by {@link #split(long)}.
     *
     * <p><b>Threading:</b> immutable; safe to share between threads.
     *
     * @param left  the half before the split position
     * @param right the half from the split position onwards
     */
    public record Split(Clip left, Clip right) {

        /**
         * Validates the components.
         *
         * @throws NullPointerException if either half is null
         */
        public Split {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }
}
