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
 * An ordered set of tracks sharing one sample rate. The rate is derived from the tracks: an empty project
 * has none and accepts a track of any rate; otherwise every track must have the rate of the tracks already
 * there. Removing the last track therefore frees the rate again, and re-inserting tracks restores it.
 * Channel counts may differ per track. Tracks are identified by identity; a track can be in the project
 * only once.
 *
 * <p><b>Threading:</b> mutable and not thread-safe; mutate it on one thread only (the JavaFX application
 * thread in the app).
 */
public final class Project {

    private final List<Track> tracks = new ArrayList<>();

    /** Creates an empty project. */
    public Project() {
        // nothing to initialise
    }

    /** @return the project sample rate, or empty if no track has been added */
    public OptionalInt sampleRate() {
        return tracks.isEmpty() ? OptionalInt.empty() : OptionalInt.of(tracks.get(0).sampleRate());
    }

    /**
     * Adds a track at the end.
     *
     * @param track the track
     * @throws SampleRateMismatchException if the track's rate differs from the project's rate
     * @throws IllegalArgumentException    if the track is already in the project
     */
    public void addTrack(Track track) {
        insertTrack(tracks.size(), track);
    }

    /**
     * Inserts a track at a position; later tracks move down by one.
     *
     * @param index the position, {@code 0 <= index <= tracks().size()}
     * @param track the track
     * @throws SampleRateMismatchException if the track's rate differs from the project's rate
     * @throws IllegalArgumentException    if the track is already in the project
     * @throws IndexOutOfBoundsException   if the index is out of range
     */
    public void insertTrack(int index, Track track) {
        Objects.requireNonNull(track, "track");
        if (index < 0 || index > tracks.size()) {
            throw new IndexOutOfBoundsException("index " + index + " not in [0, " + tracks.size() + "]");
        }
        if (indexOf(track) >= 0) {
            throw new IllegalArgumentException("track \"" + track.name() + "\" is already in the project");
        }
        if (!tracks.isEmpty() && track.sampleRate() != tracks.get(0).sampleRate()) {
            throw new SampleRateMismatchException(tracks.get(0).sampleRate(), track.sampleRate());
        }
        tracks.add(index, track);
    }

    /**
     * Removes a track.
     *
     * @param track the track (compared by identity)
     * @return the index the track had
     * @throws IllegalArgumentException if the track is not in the project
     */
    public int removeTrack(Track track) {
        Objects.requireNonNull(track, "track");
        int index = indexOf(track);
        if (index < 0) {
            throw new IllegalArgumentException("track \"" + track.name() + "\" is not in the project");
        }
        tracks.remove(index);
        return index;
    }

    /**
     * Returns the position of a track.
     *
     * @param track the track (compared by identity)
     * @return its index, or -1 if it is not in the project
     */
    public int indexOf(Track track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i) == track) {
                return i;
            }
        }
        return -1;
    }

    /** @return an unmodifiable view of the tracks, in the order added */
    public List<Track> tracks() {
        return Collections.unmodifiableList(tracks);
    }
}
