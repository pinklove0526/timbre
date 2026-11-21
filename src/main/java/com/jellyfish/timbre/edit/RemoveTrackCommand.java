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

import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Track;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Removes one track; the index it had is recorded at apply time and revert inserts the same {@link Track}
 * object back at that index. The project's sample rate is derived from its tracks, so removing the last
 * track frees the rate and reverting restores it; no rate state needs to be kept here.
 *
 * <p>Several tracks are removed as one history entry with {@link #forTracks(Project, List)}.
 *
 * <p><b>Threading:</b> the thread that owns the project (see {@link Project}).
 */
public final class RemoveTrackCommand implements Command {

    /** The history name of a removal. */
    public static final String NAME = "Remove Track";

    private final Project project;
    private final Track track;
    private int index = -1;

    /**
     * Creates the command; nothing happens until {@link #apply()}.
     *
     * @param project the project
     * @param track   the track to remove
     */
    public RemoveTrackCommand(Project project, Track track) {
        this.project = Objects.requireNonNull(project, "project");
        this.track = Objects.requireNonNull(track, "track");
    }

    /**
     * Builds the removal of several tracks as ONE command named {@value #NAME}: the parts are ordered by
     * descending index so that every index is still valid when its part applies, and the compound's revert
     * (reverse order, ascending indices) puts every track back at its original position.
     *
     * @param project the project
     * @param tracks  the tracks to remove, all in the project, no duplicates, at least one
     * @return the compound command
     * @throws IllegalArgumentException if the list is empty, has duplicates or a track is not in the project
     */
    public static CompoundCommand forTracks(Project project, List<Track> tracks) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(tracks, "tracks");
        if (tracks.isEmpty()) {
            throw new IllegalArgumentException("no tracks to remove");
        }
        List<Track> sorted = new ArrayList<>(tracks);
        for (Track t : sorted) {
            if (project.indexOf(Objects.requireNonNull(t, "track")) < 0) {
                throw new IllegalArgumentException("track \"" + t.name() + "\" is not in the project");
            }
        }
        sorted.sort(Comparator.comparingInt(project::indexOf).reversed());
        List<Command> parts = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0 && sorted.get(i) == sorted.get(i - 1)) {
                throw new IllegalArgumentException("track \"" + sorted.get(i).name() + "\" is given twice");
            }
            parts.add(new RemoveTrackCommand(project, sorted.get(i)));
        }
        return new CompoundCommand(NAME, parts);
    }

    @Override
    public String name() {
        return NAME;
    }

    /** @return the track this command removes */
    public Track track() {
        return track;
    }

    /**
     * Removes the track and remembers its index.
     *
     * @throws IllegalArgumentException if the track is not in the project
     */
    @Override
    public void apply() {
        index = project.removeTrack(track);
    }

    /**
     * Inserts the track back at the index it had.
     *
     * @throws IllegalStateException if the command was never applied
     */
    @Override
    public void revert() {
        if (index < 0) {
            throw new IllegalStateException("revert before apply");
        }
        project.insertTrack(index, track);
    }
}
