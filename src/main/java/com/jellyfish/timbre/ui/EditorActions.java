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
import com.jellyfish.timbre.audio.PlaybackEngine;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.ui.viewmodel.PlayRangePolicy;
import com.jellyfish.timbre.ui.viewmodel.SelectionController;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import com.jellyfish.timbre.ui.viewmodel.TransportRules;
import com.jellyfish.timbre.ui.viewmodel.WheelMapping;
import com.jellyfish.timbre.ui.viewmodel.ZoomAnchorPolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The one place where the interaction rules live: zoom, scroll, selection (mouse and keyboard), Play, Stop
 * and Pause. Menu items, key handling and mouse handlers all call these methods. It changes only the
 * {@link EditorModel} and reaches the engine only through its public methods.
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
    private final Consumer<String> messages;
    private SelectionController mouse;

    /**
     * Creates the actions.
     *
     * @param model    the editor model
     * @param engine   the playback engine; its plan is set by the owner
     * @param messages receives short user-facing messages (an empty string clears the message)
     */
    public EditorActions(EditorModel model, PlaybackEngine engine, Consumer<String> messages) {
        this.model = Objects.requireNonNull(model, "model");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.mouse = new SelectionController(model.axis(), model.projectFrames());
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
                messages.accept("");
            } else {
                messages.accept("Nothing to play from this position.");
            }
        } catch (AudioDeviceException e) {
            messages.accept(e.getMessage());
        }
    }

    /** Stops playback (no effect when already STOPPED). */
    public void stop() {
        engine.stop();
    }
}
