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
import java.util.Objects;

/**
 * Adds a track at the end of the project ("Import"); revert removes it again. Redo adds the SAME
 * {@link Track} object, so everything that refers to it (clips, sources, pyramids) stays valid.
 *
 * <p><b>Threading:</b> the thread that owns the project (see {@link Project}).
 */
public final class AddTrackCommand implements Command {

    /** The history name of an import. */
    public static final String NAME = "Import";

    private final Project project;
    private final Track track;

    /**
     * Creates the command; nothing happens until {@link #apply()}.
     *
     * @param project the project
     * @param track   the track to add (not yet in the project)
     */
    public AddTrackCommand(Project project, Track track) {
        this.project = Objects.requireNonNull(project, "project");
        this.track = Objects.requireNonNull(track, "track");
    }

    @Override
    public String name() {
        return NAME;
    }

    /** @return the track this command adds */
    public Track track() {
        return track;
    }

    /**
     * Adds the track at the end.
     *
     * @throws IllegalArgumentException if the track is already in the project or its sample rate differs
     */
    @Override
    public void apply() {
        project.addTrack(track);
    }

    @Override
    public void revert() {
        project.removeTrack(track);
    }
}
