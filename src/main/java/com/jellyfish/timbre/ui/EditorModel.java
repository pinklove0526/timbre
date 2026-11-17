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
package com.jellyfish.timbre.ui;

import com.jellyfish.timbre.dsp.PeakPyramidCache;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.util.Objects;
import java.util.concurrent.Executor;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * The state the editor views draw: the project, the time axis, the selection (the cursor is its start), an
 * optional playhead frame and the width of the lane area. It owns the {@link PeakPyramidCache} and closes it
 * in {@link #close()}.
 *
 * <p>Two counters tell the views what to repaint: {@link #waveformVersion()} changes when the axis, the
 * project, the lane width or the pyramid readiness changes; {@link #overlayVersion()} additionally changes
 * when the selection or the playhead changes.
 *
 * <p><b>Threading:</b> JavaFX application thread only. The pyramid callback arrives on the
 * {@code timbre-peaks} thread and is handed to the FX thread through the executor given at construction
 * ({@code Platform::runLater} by default).
 */
public final class EditorModel implements AutoCloseable {

    /** Lane width used before the first layout pass reports the real width. */
    public static final int DEFAULT_LANE_WIDTH = 800;
    /** Sample rate of the axis of an empty project. */
    public static final int EMPTY_PROJECT_SAMPLE_RATE = 44100;

    private final Executor fx;
    private final PeakPyramidCache cache = new PeakPyramidCache();
    private final ObjectProperty<TimeAxis> axis = new SimpleObjectProperty<>();
    private final ObjectProperty<Selection> selection = new SimpleObjectProperty<>(Selection.cursor(0));
    private final LongProperty playhead = new SimpleLongProperty(-1);
    private final IntegerProperty laneWidth = new SimpleIntegerProperty(DEFAULT_LANE_WIDTH);
    private final IntegerProperty waveformVersion = new SimpleIntegerProperty();
    private final IntegerProperty overlayVersion = new SimpleIntegerProperty();
    private Project project = new Project();
    private boolean closed;

    /** Creates a model whose pyramid callbacks run on the JavaFX thread via {@link Platform#runLater}. */
    public EditorModel() {
        this(Platform::runLater);
    }

    /**
     * Creates a model with a given executor for the hop from the {@code timbre-peaks} thread.
     *
     * @param fxExecutor runs a task on the thread that owns the model
     */
    public EditorModel(Executor fxExecutor) {
        this.fx = Objects.requireNonNull(fxExecutor, "fxExecutor");
        axis.set(TimeAxis.fit(EMPTY_PROJECT_SAMPLE_RATE, 0, DEFAULT_LANE_WIDTH));
    }

    /** @return the project (do not add tracks directly; use {@link #addTrack(Track)}) */
    public Project project() {
        return project;
    }

    /** @return the peak pyramid cache */
    public PeakPyramidCache cache() {
        return cache;
    }

    /** @return the current axis */
    public TimeAxis axis() {
        return axis.get();
    }

    /** @return the axis property, for listeners */
    public ObjectProperty<TimeAxis> axisProperty() {
        return axis;
    }

    /**
     * Replaces the axis and marks both layers dirty.
     *
     * @param newAxis the axis; its sample rate must match the project's
     * @throws IllegalArgumentException if the sample rate differs from the project's
     */
    public void setAxis(TimeAxis newAxis) {
        Objects.requireNonNull(newAxis, "newAxis");
        if (newAxis.sampleRate() != sampleRate()) {
            throw new IllegalArgumentException("axis is " + newAxis.sampleRate() + " Hz, project is "
                    + sampleRate() + " Hz");
        }
        axis.set(newAxis);
        markWaveformDirty();
    }

    /** @return the selection (empty = cursor at its start) */
    public Selection selection() {
        return selection.get();
    }

    /**
     * Sets the selection (from code; there is no mouse handling in B1) and marks the overlay dirty.
     *
     * @param newSelection the selection, not clamped
     */
    public void setSelection(Selection newSelection) {
        selection.set(Objects.requireNonNull(newSelection, "newSelection"));
        overlayVersion.set(overlayVersion.get() + 1);
    }

    /** @return true if a playhead frame is set */
    public boolean hasPlayhead() {
        return playhead.get() >= 0;
    }

    /** @return the playhead frame, or -1 if none */
    public long playheadFrame() {
        return playhead.get();
    }

    /**
     * Sets the playhead frame and marks the overlay dirty if it changed.
     *
     * @param frame the frame, at least 0
     * @throws IllegalArgumentException if {@code frame} is negative
     */
    public void setPlayheadFrame(long frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be >= 0: " + frame);
        }
        if (playhead.get() != frame) {
            playhead.set(frame);
            overlayVersion.set(overlayVersion.get() + 1);
        }
    }

    /** Removes the playhead (the overlay then draws at the cursor). */
    public void clearPlayhead() {
        if (playhead.get() >= 0) {
            playhead.set(-1);
            overlayVersion.set(overlayVersion.get() + 1);
        }
    }

    /** @return the width of the lane area in pixels, at least 1 */
    public int laneWidth() {
        return laneWidth.get();
    }

    /**
     * Sets the lane width. The zoom is kept and the scroll position is re-clamped; the view is never
     * silently re-fitted.
     *
     * @param width the width, at least 1; a smaller value is ignored
     */
    public void setLaneWidth(int width) {
        if (width < 1 || width == laneWidth.get()) {
            return;
        }
        laneWidth.set(width);
        TimeAxis a = axis.get();
        axis.set(a.scrolledTo(a.scrollFrame(), limits()));
        markWaveformDirty();
    }

    /** @return the lane width property, for bindings (change it only through {@link #setLaneWidth(int)}) */
    public ReadOnlyIntegerProperty laneWidthProperty() {
        return laneWidth;
    }

    /** @return the sample rate of the project, or {@value #EMPTY_PROJECT_SAMPLE_RATE} for an empty one */
    public int sampleRate() {
        return project.sampleRate().orElse(EMPTY_PROJECT_SAMPLE_RATE);
    }

    /** @return the project length in frames: the end of the last clip of any track, 0 if empty */
    public long projectFrames() {
        long end = 0;
        for (Track t : project.tracks()) {
            for (Clip c : t.clips()) {
                end = Math.max(end, c.timelineEnd());
            }
        }
        return end;
    }

    /** @return the zoom and scroll limits for the current project and lane width */
    public TimeAxis.Limits limits() {
        return new TimeAxis.Limits(sampleRate(), projectFrames(), laneWidth.get());
    }

    /** @return the counter that changes whenever the waveform layer must be repainted */
    public ReadOnlyIntegerProperty waveformVersion() {
        return waveformVersion;
    }

    /** @return the counter that changes whenever the overlay must be repainted */
    public ReadOnlyIntegerProperty overlayVersion() {
        return overlayVersion;
    }

    /** Marks the waveform layer (and the overlay) dirty. */
    public void markWaveformDirty() {
        waveformVersion.set(waveformVersion.get() + 1);
        overlayVersion.set(overlayVersion.get() + 1);
    }

    /**
     * Adds a track and asks for the peak pyramid of every clip source. When it is the first track the axis
     * becomes {@link TimeAxis#fit} for the project and the lane width; otherwise the scroll position is
     * re-clamped for the new project length.
     *
     * @param track the track
     * @throws IllegalArgumentException if the sample rate differs from the project's
     */
    public void addTrack(Track track) {
        Objects.requireNonNull(track, "track");
        boolean first = project.tracks().isEmpty();
        project.addTrack(track);
        for (Clip c : track.clips()) {
            cache.request(c.source(), () -> fx.execute(this::pyramidReady));
        }
        if (first) {
            axis.set(TimeAxis.fit(sampleRate(), projectFrames(), laneWidth.get()));
        } else {
            TimeAxis a = axis.get();
            axis.set(a.scrolledTo(a.scrollFrame(), limits()));
        }
        markWaveformDirty();
    }

    /** Replaces the project with an empty one and resets the axis, selection and playhead. */
    public void clear() {
        project = new Project();
        axis.set(TimeAxis.fit(EMPTY_PROJECT_SAMPLE_RATE, 0, laneWidth.get()));
        selection.set(Selection.cursor(0));
        playhead.set(-1);
        markWaveformDirty();
    }

    private void pyramidReady() {
        if (!closed) {
            markWaveformDirty();
        }
    }

    /** Closes the pyramid cache. Idempotent. */
    @Override
    public void close() {
        closed = true;
        cache.close();
    }
}
