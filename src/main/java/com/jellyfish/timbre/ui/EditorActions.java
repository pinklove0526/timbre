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

import com.jellyfish.timbre.audio.AudioDeviceException;
import com.jellyfish.timbre.audio.MixPlan;
import com.jellyfish.timbre.audio.PlaybackEngine;
import com.jellyfish.timbre.audio.WavReader;
import com.jellyfish.timbre.edit.AddTrackCommand;
import com.jellyfish.timbre.edit.EditOutcome;
import com.jellyfish.timbre.edit.RemoveTrackCommand;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.PlayRangePolicy;
import com.jellyfish.timbre.ui.viewmodel.SelectionController;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import com.jellyfish.timbre.ui.viewmodel.TransportRules;
import com.jellyfish.timbre.ui.viewmodel.WheelMapping;
import com.jellyfish.timbre.ui.viewmodel.ZoomAnchorPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * The one place where the interaction rules live: zoom, scroll, selection (mouse and keyboard), Play, Stop
 * and Pause. Menu items, key handling and mouse handlers all call these methods. It changes only the
 * {@link EditorModel} and reaches the engine only through its public methods.
 *
 * <p>Milestone 4 adds the edits (cut, copy, paste, delete, silence, trim, split, undo, redo, import, remove
 * track, clear tracks). They are locked while PLAYING or PAUSED ({@link TransportRules#editsLocked}), except
 * copy; every recorded edit runs the after-edit pipeline (plan rebuild, axis re-clamp, repaint, the new time
 * selection scrolled into view) and moves the {@link com.jellyfish.timbre.ui.viewmodel.SelectionTrail} in
 * lockstep with the history. The enabling properties ({@link #cutEnabled()} and friends) are recomputed by
 * {@link #refreshEnabled()} after every action, history change, engine state change and selection change.
 *
 * <p>Selection changes (mouse, Ctrl+A, Esc, Home, End) are ignored while the engine is PLAYING or PAUSED
 * ({@link TransportRules#selectionLocked}); zoom and scroll always work. With an empty project the selection
 * actions and Play do nothing. The owned {@link SelectionController} is synchronised with the model's axis
 * and project length before every mouse call, and with the model's selection whenever that was changed by
 * something else.
 *
 * <p><b>Threading:</b> JavaFX application thread only. The engine's own methods are thread-safe and never
 * block this thread for long.
 */
public final class EditorActions {

    private final EditorModel model;
    private final PlaybackEngine engine;
    private SelectionController mouse;
    private MixPlan plan = MixPlan.empty();

    private final BooleanProperty cutEnabled = new SimpleBooleanProperty();
    private final BooleanProperty copyEnabled = new SimpleBooleanProperty();
    private final BooleanProperty pasteEnabled = new SimpleBooleanProperty();
    private final BooleanProperty splitEnabled = new SimpleBooleanProperty();
    private final BooleanProperty undoEnabled = new SimpleBooleanProperty();
    private final BooleanProperty redoEnabled = new SimpleBooleanProperty();
    private final BooleanProperty importEnabled = new SimpleBooleanProperty(true);
    private final BooleanProperty removeTrackEnabled = new SimpleBooleanProperty();
    private final StringProperty undoLabel = new SimpleStringProperty("Undo");
    private final StringProperty redoLabel = new SimpleStringProperty("Redo");

    /**
     * Creates the actions. User messages go to {@link EditorModel#setMessage}.
     *
     * @param model  the editor model
     * @param engine the playback engine; the actions set its plan after every edit
     */
    public EditorActions(EditorModel model, PlaybackEngine engine) {
        this.model = Objects.requireNonNull(model, "model");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.mouse = new SelectionController(model.axis(), model.projectFrames());
        model.history().addListener(this::refreshEnabled);
        model.selectionProperty().addListener((obs, old, now) -> refreshEnabled());
        refreshEnabled();
    }

    private void messages(String text) {
        model.setMessage(text);
    }

    private boolean locked() {
        return TransportRules.selectionLocked(engine.state());
    }

    // ------------------------------------------------------------------ zoom and scroll

    /** Zooms in 2x, anchored at the cursor if it is visible, else at the middle of the lane area. */
    public void zoomIn() {
        zoomAtCursor(2.0);
    }

    /** Zooms out 2x, anchored at the cursor if it is visible, else at the middle of the lane area. */
    public void zoomOut() {
        zoomAtCursor(0.5);
    }

    private void zoomAtCursor(double factor) {
        double anchor = ZoomAnchorPolicy.anchorX(model.selection().startFrame(), model.axis(), model.laneWidth());
        zoomAt(factor, anchor);
    }

    /**
     * Zooms about a pixel column within the zoom limits.
     *
     * @param factor  the zoom factor, above 1 zooms in
     * @param anchorX the column that keeps its time, in lane-area pixels
     */
    public void zoomAt(double factor, double anchorX) {
        model.setAxis(model.axis().zoomedAt(factor, anchorX, model.limits()));
    }

    /** Fits the whole project into the lane area ({@link TimeAxis#fit}). */
    public void fitProject() {
        model.setAxis(TimeAxis.fit(model.sampleRate(), model.projectFrames(), model.laneWidth()));
    }

    /**
     * Scrolls horizontally, within the scroll limits.
     *
     * @param frames the change of the scroll frame (negative scrolls toward earlier time)
     */
    public void scrollBy(long frames) {
        TimeAxis a = model.axis();
        model.setAxis(a.scrolledTo(a.scrollFrame() + frames, model.limits()));
    }

    /**
     * Handles a wheel event over the lane area.
     *
     * <p>With the shortcut key: zoom by {@link WheelMapping#zoomFactor} of {@code deltaY} about {@code x}.
     * With Shift: scroll by {@link WheelMapping#scrollFrames} of {@code deltaY}, or of {@code deltaX} if
     * {@code deltaY} is 0 (some platforms report a shifted wheel that way). Without a modifier a mostly
     * horizontal event ({@code |deltaX| > |deltaY|}) scrolls horizontally the same way; anything else is
     * left for the vertical scrolling of the track stack.
     *
     * @param x        the pointer column in lane-area pixels
     * @param deltaX   the horizontal wheel delta
     * @param deltaY   the vertical wheel delta
     * @param shortcut true if the platform shortcut key (Ctrl) is down
     * @param shift    true if Shift is down
     * @return true if the event was used and must be consumed
     */
    public boolean onWheel(double x, double deltaX, double deltaY, boolean shortcut, boolean shift) {
        if (shortcut) {
            if (deltaY != 0) {
                zoomAt(WheelMapping.zoomFactor(deltaY), x);
            }
            return true;
        }
        if (shift) {
            double d = deltaY != 0 ? deltaY : deltaX;
            if (d != 0) {
                scrollBy(WheelMapping.scrollFrames(d, model.axis().visibleFrames(model.laneWidth())));
            }
            return true;
        }
        if (Math.abs(deltaX) > Math.abs(deltaY)) {
            scrollBy(WheelMapping.scrollFrames(deltaX, model.axis().visibleFrames(model.laneWidth())));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ selection (keyboard)

    /** Selects the whole project; ignored while PLAYING or PAUSED and for an empty project. */
    public void selectAll() {
        long end = model.projectFrames();
        if (locked() || end == 0) {
            return;
        }
        setSelection(new Selection(0, end));
    }

    /** Turns the selection into the cursor at its start; ignored while PLAYING or PAUSED or if empty project. */
    public void clearSelection() {
        if (locked() || model.projectFrames() == 0) {
            return;
        }
        setSelection(Selection.cursor(model.selection().startFrame()));
    }

    /** Moves the cursor to frame 0 and scrolls it into view; ignored while PLAYING or PAUSED or if empty. */
    public void cursorToStart() {
        moveCursor(0);
    }

    /** Moves the cursor to the project end and scrolls it into view; ignored while PLAYING or PAUSED. */
    public void cursorToEnd() {
        moveCursor(model.projectFrames());
    }

    private void moveCursor(long frame) {
        if (locked() || model.projectFrames() == 0) {
            return;
        }
        setSelection(Selection.cursor(frame));
        scrollIntoView(frame);
    }

    /** Scrolls the minimum distance that brings a frame into the lane area (no zoom change). */
    private void scrollIntoView(long frame) {
        TimeAxis a = model.axis();
        double x = a.xAtFrame(frame);
        long wanted = a.scrollFrame();
        if (x < 0) {
            wanted = frame;
        } else if (x >= model.laneWidth()) {
            wanted = frame - Math.round((model.laneWidth() - 1) * a.framesPerPixel());
        }
        if (wanted != a.scrollFrame()) {
            model.setAxis(a.scrolledTo(wanted, model.limits()));
        }
    }

    private void setSelection(Selection selection) {
        model.setSelection(selection);
        syncMouse();
        mouse.setSelection(selection);
    }

    // ------------------------------------------------------------------ selection (mouse)

    private void syncMouse() {
        mouse.setAxis(model.axis());
        mouse.setProjectFrames(model.projectFrames());
    }

    /**
     * Handles a press on a track row of the lane area; ignored while PLAYING or PAUSED.
     *
     * @param x     the pointer column in lane-area pixels
     * @param shift true if Shift is down (extend from the anchor)
     * @return true if the press was used
     */
    public boolean onPress(double x, boolean shift) {
        if (locked()) {
            return false;
        }
        syncMouse();
        if (!model.selection().equals(mouse.selection())) {
            mouse.setSelection(model.selection());
        }
        mouse.press(x, shift);
        model.setSelection(mouse.selection());
        return true;
    }

    /**
     * Handles a drag of a gesture started by {@link #onPress}; ignored if none is in progress or while
     * PLAYING or PAUSED.
     *
     * @param x the pointer column in lane-area pixels (it may lie outside the lane area)
     */
    public void onDrag(double x) {
        if (locked() || !mouse.isPressed()) {
            return;
        }
        syncMouse();
        mouse.drag(x);
        model.setSelection(mouse.selection());
    }

    /**
     * Handles the release of a gesture started by {@link #onPress}; ignored if none is in progress or while
     * PLAYING or PAUSED.
     *
     * @param x the pointer column in lane-area pixels
     */
    public void onRelease(double x) {
        if (locked() || !mouse.isPressed()) {
            return;
        }
        syncMouse();
        mouse.release(x);
        model.setSelection(mouse.selection());
    }

    // ------------------------------------------------------------------ transport

    /** Space: plays when STOPPED, stops when PLAYING or PAUSED. */
    public void toggleSpace() {
        switch (TransportRules.spaceAction(engine.state())) {
            case PLAY -> play();
            case STOP -> stop();
            default -> { }
        }
    }

    /** P: pauses when PLAYING, resumes when PAUSED, nothing when STOPPED. */
    public void togglePause() {
        switch (TransportRules.pauseAction(engine.state())) {
            case PAUSE -> engine.pause();
            case RESUME -> engine.resume();
            default -> { }
        }
    }

    /**
     * Plays the selection, or from the cursor to the end of the project ({@link PlayRangePolicy}); ignored
     * unless STOPPED.
     */
    public void play() {
        if (!TransportRules.playEnabled(engine.state())) {
            return;
        }
        Optional<PlayRangePolicy.Range> range = PlayRangePolicy.choose(model.selection(), model.projectFrames());
        if (range.isEmpty()) {
            return;
        }
        try {
            if (engine.play(range.get().fromFrame(), range.get().toFrame())) {
                messages("");
            } else {
                messages("Nothing to play from this position.");
            }
        } catch (AudioDeviceException e) {
            messages(e.getMessage());
        }
    }

    /** Stops playback (no effect when already STOPPED). */
    public void stop() {
        engine.stop();
    }

    // ------------------------------------------------------------------ editing (Milestone 4)

    private boolean editsLocked() {
        return TransportRules.editsLocked(engine.state());
    }

    private boolean hasTracks() {
        return !model.project().tracks().isEmpty();
    }

    private List<Track> targets() {
        return model.trackSelection().targets(model.project().tracks());
    }

    private boolean canRangeEdit() {
        return !editsLocked() && hasTracks() && !model.selection().isEmpty();
    }

    /**
     * Recomputes every enabling property and the undo / redo labels from the current state. Must run after
     * every action, history change, engine state change and selection change (a disabled menu item's
     * accelerator does not fire, so stale states would block shortcuts).
     */
    public void refreshEnabled() {
        boolean locked = editsLocked();
        boolean range = canRangeEdit();
        cutEnabled.set(range);
        copyEnabled.set(TransportRules.copyAllowed(engine.state()) && hasTracks() && !model.selection().isEmpty());
        pasteEnabled.set(!locked && hasTracks() && !model.clipboard().isEmpty());
        splitEnabled.set(!locked && hasTracks());
        undoEnabled.set(!locked && model.history().canUndo());
        redoEnabled.set(!locked && model.history().canRedo());
        importEnabled.set(!locked);
        removeTrackEnabled.set(!locked && !model.trackSelection().selected(model.project().tracks()).isEmpty());
        undoLabel.set(model.history().undoName().map(n -> "Undo " + n).orElse("Undo"));
        redoLabel.set(model.history().redoName().map(n -> "Redo " + n).orElse("Redo"));
    }

    /** @return enabled state of Cut, Delete, Silence and Trim */
    public ReadOnlyBooleanProperty cutEnabled() {
        return cutEnabled;
    }

    /** @return enabled state of Copy */
    public ReadOnlyBooleanProperty copyEnabled() {
        return copyEnabled;
    }

    /** @return enabled state of Paste */
    public ReadOnlyBooleanProperty pasteEnabled() {
        return pasteEnabled;
    }

    /** @return enabled state of Split */
    public ReadOnlyBooleanProperty splitEnabled() {
        return splitEnabled;
    }

    /** @return enabled state of Undo */
    public ReadOnlyBooleanProperty undoEnabled() {
        return undoEnabled;
    }

    /** @return enabled state of Redo */
    public ReadOnlyBooleanProperty redoEnabled() {
        return redoEnabled;
    }

    /** @return enabled state of File > Open WAV (import) */
    public ReadOnlyBooleanProperty importEnabled() {
        return importEnabled;
    }

    /** @return enabled state of Track > Remove Track */
    public ReadOnlyBooleanProperty removeTrackEnabled() {
        return removeTrackEnabled;
    }

    /** @return the Undo menu label: "Undo" plus the name of the entry, or plain "Undo" */
    public ReadOnlyStringProperty undoLabel() {
        return undoLabel;
    }

    /** @return the Redo menu label: "Redo" plus the name of the entry, or plain "Redo" */
    public ReadOnlyStringProperty redoLabel() {
        return redoLabel;
    }

    /** @return the plan the engine was last given */
    public MixPlan plan() {
        return plan;
    }

    /** Rebuilds the playback plan from fresh track snapshots and hands it to the engine (engine stopped). */
    public void rebuildPlan() {
        plan = MixPlan.of(model.project());
        engine.setPlan(plan);
    }

    /** Cuts the time selection of the target tracks to the clipboard (ripple). */
    public void cut() {
        rangeEdit(sel -> model.editor().cut(sel, targets()));
    }

    /** Copies the time selection of the target tracks to the clipboard; allowed while playing. */
    public void copy() {
        messages("");
        if (!TransportRules.copyAllowed(engine.state()) || !hasTracks() || model.selection().isEmpty()) {
            refreshEnabled();
            return;
        }
        model.editor().copy(model.selection(), targets());
        model.editor().lastRefusal().ifPresent(this::messages);
        refreshEnabled();
    }

    /** Inserts the clipboard at the selection start of the target tracks. */
    public void paste() {
        messages("");
        if (editsLocked() || !hasTracks() || model.clipboard().isEmpty()) {
            refreshEnabled();
            return;
        }
        Selection before = model.selection();
        finishEdit(before, model.editor().paste(before.startFrame(), targets()));
    }

    /** Deletes the time selection of the target tracks (ripple); Delete and Backspace. */
    public void deleteSelection() {
        rangeEdit(sel -> model.editor().delete(sel, targets()));
    }

    /** Turns the time selection of the target tracks into silence. */
    public void silence() {
        rangeEdit(sel -> model.editor().silence(sel, targets()));
    }

    /** Keeps only the time selection of the target tracks, moved to frame 0. */
    public void trim() {
        rangeEdit(sel -> model.editor().trim(sel, targets()));
    }

    /** Splits the clips of the target tracks at the cursor (the selection start). */
    public void split() {
        messages("");
        if (editsLocked() || !hasTracks()) {
            refreshEnabled();
            return;
        }
        Selection before = model.selection();
        finishEdit(before, model.editor().split(before.startFrame(), targets()));
    }

    private void rangeEdit(Function<Selection, Optional<EditOutcome>> edit) {
        messages("");
        if (!canRangeEdit()) {
            refreshEnabled();
            return;
        }
        Selection before = model.selection();
        finishEdit(before, edit.apply(before));
    }

    private void finishEdit(Selection before, Optional<EditOutcome> outcome) {
        if (outcome.isPresent()) {
            Selection after = outcome.get().selectionAfter();
            model.trail().record(before, after);
            afterEdit(after, false);
        } else {
            model.editor().lastRefusal().ifPresent(this::messages);
            refreshEnabled();
        }
    }

    /** Undoes the last edit and restores the time selection from before it. */
    public void undo() {
        messages("");
        if (editsLocked() || !model.editor().undo()) {
            refreshEnabled();
            return;
        }
        Selection s = model.trail().undo().orElse(model.selection());
        afterEdit(s, true);
    }

    /** Redoes the next edit and restores the time selection from after it. */
    public void redo() {
        messages("");
        if (editsLocked() || !model.editor().redo()) {
            refreshEnabled();
            return;
        }
        Selection s = model.trail().redo().orElse(model.selection());
        afterEdit(s, true);
    }

    /**
     * Applies a track-header click to the track selection (allowed while playing).
     *
     * @param index  the clicked track's index
     * @param toggle true if Ctrl/Cmd is held
     * @param range  true if Shift is held
     */
    public void selectTrack(int index, boolean toggle, boolean range) {
        model.trackSelection().click(model.project().tracks(), index, toggle, range);
        model.markTrackSelectionChanged();
        refreshEnabled();
    }

    /** Removes the explicitly selected tracks as one undoable entry "Remove Track". */
    public void removeSelectedTracks() {
        messages("");
        List<Track> selected = model.trackSelection().selected(model.project().tracks());
        if (editsLocked() || selected.isEmpty()) {
            refreshEnabled();
            return;
        }
        Selection sel = model.selection();
        model.history().execute(RemoveTrackCommand.forTracks(model.project(), selected));
        model.trail().record(sel, sel);
        afterEdit(sel, true);
    }

    /**
     * Reads a WAV file and imports it as an undoable "Import" (the path File &gt; Open WAV ends in, used
     * directly by tests). The file is read on the calling thread.
     *
     * @param file the WAV file
     * @return true if a track was added
     * @throws IOException if the file cannot be read
     */
    public boolean importFile(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (editsLocked()) {
            refreshEnabled();
            return false;
        }
        Track track = Track.of(EditorPane.baseName(file.getFileName().toString()), WavReader.read(file).source());
        return importTrack(track);
    }

    /**
     * Imports a track as an undoable "Import": adds it at the end through the history, asks for its peak
     * pyramids and runs the after-edit pipeline. A track whose sample rate differs from the project's is
     * refused with a message.
     *
     * @param track the new track (not in the project)
     * @return true if it was added
     */
    public boolean importTrack(Track track) {
        Objects.requireNonNull(track, "track");
        messages("");
        if (editsLocked()) {
            refreshEnabled();
            return false;
        }
        Project project = model.project();
        boolean wasEmpty = project.tracks().isEmpty();
        try {
            model.history().execute(new AddTrackCommand(project, track));
        } catch (IllegalArgumentException e) { // includes SampleRateMismatchException
            messages(e.getMessage());
            refreshEnabled();
            return false;
        }
        Selection sel = model.selection();
        model.trail().record(sel, sel);
        model.trackAdded(track, wasEmpty);
        afterEdit(sel, true);
        return true;
    }

    /**
     * Clear tracks (not undoable): stops the engine, empties the project, the history (saved again), the
     * clipboard, the selection trail and the track selection; resets the time selection and the axis.
     */
    public void clearTracks() {
        engine.stop();
        model.clear();
        mouse = new SelectionController(model.axis(), model.projectFrames());
        rebuildPlan();
        refreshEnabled();
    }

    /**
     * The after-edit pipeline: rebuild the plan from fresh snapshots, drop removed tracks from the track
     * selection, re-clamp the axis to the new limits (repainting the waveform layer), apply the selection
     * and scroll its start into view by the minimum amount, then refresh the enabling properties.
     *
     * @param selection     the time selection to show
     * @param tracksChanged true if the list of tracks may have changed (import, remove, undo, redo)
     */
    private void afterEdit(Selection selection, boolean tracksChanged) {
        rebuildPlan();
        model.trackSelection().retain(model.project().tracks());
        model.markTrackSelectionChanged();
        model.reclampAxis();
        if (tracksChanged) {
            model.markTracksChanged();
        }
        setSelection(selection);
        scrollIntoView(selection.startFrame());
        refreshEnabled();
    }
}
