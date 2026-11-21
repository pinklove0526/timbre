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
import com.jellyfish.timbre.model.Track;
import java.util.List;
import java.util.Objects;

/**
 * Replaces the clips of one track: apply sets the list "after", revert sets the list "before". There is no
 * inverse logic, only two immutable snapshots of the clip list.
 *
 * <p><b>Threading:</b> the thread that owns the track (see {@link Track}).
 */
public final class TrackClipsCommand implements Command {

    private final String name;
    private final Track track;
    private final List<Clip> before;
    private final List<Clip> after;

    /**
     * Creates the command.
     *
     * @param name   the name
     * @param track  the track
     * @param before the clips before the edit (copied)
     * @param after  the clips after the edit (copied)
     */
    public TrackClipsCommand(String name, Track track, List<Clip> before, List<Clip> after) {
        this.name = Objects.requireNonNull(name, "name");
        this.track = Objects.requireNonNull(track, "track");
        this.before = List.copyOf(before);
        this.after = List.copyOf(after);
    }

    @Override
    public String name() {
        return name;
    }

    /** @return the track */
    public Track track() {
        return track;
    }

    /** @return the clips before the edit */
    public List<Clip> before() {
        return before;
    }

    /** @return the clips after the edit */
    public List<Clip> after() {
        return after;
    }

    @Override
    public void apply() {
        track.replaceClips(after);
    }

    @Override
    public void revert() {
        track.replaceClips(before);
    }
}
