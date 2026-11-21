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
import com.jellyfish.timbre.edit.Clipboard;
import com.jellyfish.timbre.edit.Editor;
import com.jellyfish.timbre.edit.History;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.SelectionTrail;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import com.jellyfish.timbre.ui.viewmodel.TrackSelection;
import java.util.Objects;
import java.util.concurrent.Executor;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * The state the editor views draw: the project, the time axis, the selection (the cursor is its start), an
 * optional playhead frame and the width of the lane area. It owns the {@link PeakPyramidCache} and closes it
 * in {@link #close()}.
 *
 * <p>Two counters tell the views what to repaint: {@link #waveformVersion()} changes when the axis, the
 * project, the lane width or the pyramid readiness changes; {@link #overlayVersion()} additionally changes
 * when the selection or the playhead changes.
 *
 * <p>Milestone 4 adds the editing state: the {@link Editor} with its {@link History} and {@link Clipboard}
 * (all on the same {@link Project}, which is never replaced), the {@link TrackSelection}, the
 * {@link SelectionTrail} beside the history, a user message (the reason of a refused edit, for example) and
 * the modified flag. {@link #tracksVersion()} changes when the list of tracks may have changed, and
 * {@link #trackSelectionVersion()} when the track selection changed. All of it is used on the FX thread
 * only, so the history listener needs no thread hop.
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
    private final IntegerProperty tracksVersion = new SimpleIntegerProperty();
    private final IntegerProperty trackSelectionVersion = new SimpleIntegerProperty();
    private final StringProperty message = new SimpleStringProperty("");
    private final BooleanProperty modified = new SimpleBooleanProperty(false);
    private final Project project = new Project();
    private final History history = new History();
    private final Clipboard clipboard = new Clipboard();
    private final Editor editor = new Editor(project, history, clipboard);
    private final TrackSelection trackSelection = new TrackSelection();
    private final SelectionTrail trail = new SelectionTrail();
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
        history.addListener(() -> modified.set(history.isModified()));
    }

    /** @return the editor working on {@link #project()} */
    public Editor editor() {
        return editor;
    }

    /** @return the edit history */
    public History history() {
        return history;
    }

    /** @return the app clipboard */
    public Clipboard clipboard() {
        return clipboard;
    }

    /** @return the track selection */
    public TrackSelection trackSelection() {
        return trackSelection;
    }

    /** @return the selection trail kept in lockstep with the history */
    public SelectionTrail trail() {
        return trail;
    }

    /** @return the user message ("" when there is none) */
    public ReadOnlyStringProperty messageProperty() {
        return message;
    }

    /**
     * Sets the user message.
     *
     * @param text the text, "" to clear
     */
    public void setMessage(String text) {
        message.set(Objects.requireNonNull(text, "text"));
    }

    /** @return true while the history differs from its save point */
    public ReadOnlyBooleanProperty modifiedProperty() {
        return modified;
    }

    /** @return the counter that changes when the list of tracks may have changed */
    public ReadOnlyIntegerProperty tracksVersion() {
        return tracksVersion;
    }

    /** Signals that tracks were added, removed or re-ordered. */
    public void markTracksChanged() {
        tracksVersion.set(tracksVersion.get() + 1);
        markWaveformDirty();
    }

    /** @return the counter that changes when the track selection changed */
    public ReadOnlyIntegerProperty trackSelectionVersion() {
        return trackSelectionVersion;
    }

    /** Signals that the track selection changed. */
    public void markTrackSelectionChanged() {
        trackSelectionVersion.set(trackSelectionVersion.get() + 1);
    }

    /** @return the selection property, for listeners */
    public ReadOnlyObjectProperty<Selection> selectionProperty() {
        return selection;
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
        trackAdded(track, first);
    }

    /**
     * Updates the view state after a track was added to the project by someone else (an undoable import):
     * asks for the peak pyramid of every clip source (the cache builds each source once), fits the axis when
     * the project was empty before, otherwise re-clamps the scroll position.
     *
     * @param track     the added track
     * @param wasEmpty  true if the project had no tracks before
     */
    public void trackAdded(Track track, boolean wasEmpty) {
        Objects.requireNonNull(track, "track");
        for (Clip c : track.clips()) {
            cache.request(c.source(), () -> fx.execute(this::pyramidReady));
        }
        if (wasEmpty) {
            axis.set(TimeAxis.fit(sampleRate(), projectFrames(), laneWidth.get()));
        } else {
            TimeAxis a = axis.get();
            axis.set(a.scrolledTo(a.scrollFrame(), limits()));
        }
        markTracksChanged();
    }

    /**
     * Re-clamps the axis after the project changed: a new sample rate or an empty project gets the fitted
     * axis; otherwise the zoom is kept (as on a window resize, the view is never silently re-fitted) and the
     * scroll position is limited to the new scroll range.
     */
    public void reclampAxis() {
        TimeAxis a = axis.get();
        if (a.sampleRate() != sampleRate() || project.tracks().isEmpty()) {
            axis.set(TimeAxis.fit(sampleRate(), projectFrames(), laneWidth.get()));
        } else {
            axis.set(a.scrolledTo(a.scrollFrame(), limits()));
        }
        markWaveformDirty();
    }

    /**
     * Empties the project (the same {@link Project} object, so the editor stays valid) and resets everything
     * that belongs to it: axis, time selection, playhead, history (saved again), clipboard, selection trail,
     * track selection and message. Not undoable.
     */
    public void clear() {
        for (Track t : project.tracks().toArray(new Track[0])) {
            project.removeTrack(t);
        }
        axis.set(TimeAxis.fit(EMPTY_PROJECT_SAMPLE_RATE, 0, laneWidth.get()));
        selection.set(Selection.cursor(0));
        playhead.set(-1);
        history.clear();
        history.markSaved();
        modified.set(history.isModified());
        clipboard.clear();
        trail.clear();
        trackSelection.clear();
        message.set("");
        markTrackSelectionChanged();
        markTracksChanged();
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
