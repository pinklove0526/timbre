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
package com.jellyfish.timbre.edit;

import com.jellyfish.timbre.model.Clip;
import java.util.List;
import java.util.Objects;

/**
 * A piece of one track's timeline, as copied to the clipboard: a length and the clips inside it, with
 * timeline starts relative to the start of the piece. Gaps (leading, inner, trailing) are silence and are
 * kept, so a fragment may hold no clips at all.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param length the length in frames, at least 0
 * @param clips  the clips, sorted, non-overlapping and inside {@code [0, length)}; copied
 */
public record Fragment(long length, List<Clip> clips) {

    /**
     * Validates and copies.
     *
     * @throws IllegalArgumentException if the length is negative or a clip lies outside the fragment, or the
     *                                  clips are not sorted and non-overlapping
     */
    public Fragment {
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0: " + length);
        }
        clips = List.copyOf(Objects.requireNonNull(clips, "clips"));
        if (!ClipOps.isWellFormed(clips)) {
            throw new IllegalArgumentException("fragment clips are not sorted and non-overlapping");
        }
        if (!clips.isEmpty() && clips.get(clips.size() - 1).timelineEnd() > length) {
            throw new IllegalArgumentException("a clip ends after the fragment length " + length);
        }
    }
}
