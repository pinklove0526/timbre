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
package com.jellyfish.timbre.ui.viewmodel;

import com.jellyfish.timbre.model.Selection;
import java.util.Objects;

/**
 * The mouse rules of time-range selection, without any UI toolkit: feed it pointer positions in pixels and read
 * back the selection.
 *
 * <p>Rules: a press sets the anchor at the frame under the pointer (clamped to {@code [0, projectFrames]}) and
 * the selection becomes the empty cursor there. Moving the pointer 3 pixels or more from the press position
 * (at any moment before the release) turns the gesture into a drag: the selection is the ordered range between
 * the anchor and the frame under the pointer, so dragging left of the anchor works. A gesture that never reached
 * 3 pixels is a click and leaves the cursor at the anchor. A shift-press keeps the existing anchor and
 * immediately extends the selection from it to the pointer; dragging after it keeps extending.
 *
 * <p><b>Threading:</b> not thread-safe; call it from one thread (the UI thread).
 */
public final class SelectionController {

    /** Movement in pixels from the press at which a click becomes a drag. */
    public static final double DRAG_THRESHOLD_PX = 3.0;

    private TimeAxis axis;
    private long projectFrames;
    private long anchor;
    private Selection selection = Selection.cursor(0);
    private boolean pressed;
    private boolean dragging;
    private double pressX;

    /**
     * Creates a controller with the cursor at frame 0.
     *
     * @param axis          the current time axis
     * @param projectFrames the project length in frames, at least 0
     * @throws IllegalArgumentException if {@code projectFrames} is negative
     */
    public SelectionController(TimeAxis axis, long projectFrames) {
        this.axis = Objects.requireNonNull(axis, "axis");
        requireLength(projectFrames);
        this.projectFrames = projectFrames;
    }

    private static void requireLength(long projectFrames) {
        if (projectFrames < 0) {
            throw new IllegalArgumentException("projectFrames must be >= 0: " + projectFrames);
        }
    }

    /**
     * Sets the axis used to convert pixels to frames (after a zoom or scroll). A gesture in progress keeps
     * its anchor in frames.
     *
     * @param newAxis the new axis
     */
    public void setAxis(TimeAxis newAxis) {
        this.axis = Objects.requireNonNull(newAxis, "axis");
    }

    /**
     * Sets the project length; the anchor and the selection are clamped to it.
     *
     * @param frames the project length in frames, at least 0
     * @throws IllegalArgumentException if {@code frames} is negative
     */
    public void setProjectFrames(long frames) {
        requireLength(frames);
        this.projectFrames = frames;
        this.anchor = Math.min(anchor, frames);
        this.selection = selection.clampedTo(frames);
    }

    /**
     * Handles a mouse press.
     *
     * @param x     pointer position in pixels from the left edge
     * @param shift true if shift is held (extend from the existing anchor)
     */
    public void press(double x, boolean shift) {
        pressed = true;
        pressX = x;
        if (shift) {
            dragging = true;
            selection = Selection.between(anchor, frameAt(x));
        } else {
            dragging = false;
            anchor = frameAt(x);
            selection = Selection.cursor(anchor);
        }
    }

    /**
     * Handles a mouse drag; ignored if no press is in progress.
     *
     * @param x pointer position in pixels from the left edge
     */
    public void drag(double x) {
        if (!pressed) {
            return;
        }
        if (Math.abs(x - pressX) >= DRAG_THRESHOLD_PX) {
            dragging = true;
        }
        if (dragging) {
            selection = Selection.between(anchor, frameAt(x));
        }
    }

    /**
     * Handles a mouse release; ignored if no press is in progress. A gesture that never moved 3 pixels is a
     * click: the selection is the empty cursor at the anchor.
     *
     * @param x pointer position in pixels from the left edge
     */
    public void release(double x) {
        if (!pressed) {
            return;
        }
        drag(x);
        if (!dragging) {
            selection = Selection.cursor(anchor);
        }
        pressed = false;
        dragging = false;
    }

    private long frameAt(double x) {
        return Math.min(Math.max(0, axis.frameAtX(x)), projectFrames);
    }

    /** @return the current selection (empty = the cursor) */
    public Selection selection() {
        return selection;
    }

    /** @return the frame where the last non-shift press happened, the fixed end of a drag */
    public long anchor() {
        return anchor;
    }

    /** @return true between a press and its release */
    public boolean isPressed() {
        return pressed;
    }
}
