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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A set of tracks sharing one sample rate. The first track added fixes the rate, and it
 * stays fixed (to be revisited in Milestone 4). Channel counts may differ per track.
 *
 * <p><b>Threading:</b> mutable; used from one thread at a time in Milestone 1.
 */
public final class Project {

    private final List<Track> tracks = new ArrayList<>();
    private int sampleRate;

    /** Creates an empty project. */
    public Project() {
        // nothing to initialise
    }

    /** @return the project sample rate, or empty if no track has been added */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * Adds a track.
     *
     * @param track the track
     * @throws SampleRateMismatchException if the track's rate differs from the project's rate
     */
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        if (sampleRate == 0) {
            sampleRate = track.sampleRate();
        } else if (track.sampleRate() != sampleRate) {
            throw new SampleRateMismatchException(sampleRate, track.sampleRate());
        }
        tracks.add(track);
    }

    /** @return an unmodifiable view of the tracks, in the order added */
    public List<Track> tracks() {
        return Collections.unmodifiableList(tracks);
    }
}
