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

import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.util.Objects;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

/**
 * A transparent canvas over all lanes that draws the selection fill and the playhead or cursor line.
 *
 * <p>Columns: the selection covers the columns {@code [floor(xAtFrame(start)), floor(xAtFrame(end)))} (at
 * least one column when the selection is not empty), clipped to the canvas; the line is the single column
 * {@code floor(xAtFrame(frame))} over the full height, drawn on top of the fill and only if that column is
 * inside the canvas. The line is at the playhead while the model has one, otherwise at the selection start.
 * Vertically both are limited to the track rows ({@link #setViewport}).
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class OverlayView extends Canvas {

    private final EditorModel model;
    private double scrollOffsetY;
    private double rowsHeight = Double.POSITIVE_INFINITY;

    /**
     * Creates the overlay.
     *
     * @param model the editor model
     */
    public OverlayView(EditorModel model) {
        super(model.laneWidth(), 1);
        this.model = Objects.requireNonNull(model, "model");
        setMouseTransparent(true);
        setId("overlay");
    }

    /**
     * Sets the vertical placement of the track rows inside the canvas. The canvas covers the visible part
     * of the lane area; the track rows start {@code -scrollOffsetY} pixels from its top and are
     * {@code rowsHeight} pixels tall. Only the rows between the top of the first track and the bottom of the
     * last one are drawn on. The default (offset 0, infinite height) draws over the whole canvas.
     *
     * @param scrollOffsetY how far the track stack is scrolled up, at least 0
     * @param rowsHeight    the height of all track rows, at least 0 ({@code Double.POSITIVE_INFINITY} for
     *                      "everything")
     * @throws IllegalArgumentException if an argument is negative or NaN
     */
    public void setViewport(double scrollOffsetY, double rowsHeight) {
        if (!(scrollOffsetY >= 0) || !(rowsHeight >= 0)) {
            throw new IllegalArgumentException("offset and height must be >= 0: " + scrollOffsetY + ", "
                    + rowsHeight);
        }
        this.scrollOffsetY = scrollOffsetY;
        this.rowsHeight = rowsHeight;
    }

    /** Clears the canvas and draws the selection and the line from the model's current state. */
    public void repaint() {
        double w = getWidth();
        double h = getHeight();
        GraphicsContext g = getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        double yTop = Math.max(0, -scrollOffsetY);
        double yBottom = Math.min(h, rowsHeight - scrollOffsetY);
        if (yBottom <= yTop) {
            return;
        }
        double drawHeight = yBottom - yTop;
        TimeAxis axis = model.axis();
        Selection sel = model.selection();
        if (!sel.isEmpty()) {
            long x0 = (long) Math.floor(axis.xAtFrame(sel.startFrame()));
            long x1 = (long) Math.floor(axis.xAtFrame(sel.endFrame()));
            x1 = Math.max(x1, x0 + 1);
            x0 = Math.max(0, x0);
            x1 = Math.min((long) w, x1);
            if (x1 > x0) {
                g.setFill(Theme.SELECTION_FILL);
                g.fillRect(x0, yTop, x1 - x0, drawHeight);
            }
        }
        long frame = model.hasPlayhead() ? model.playheadFrame() : sel.startFrame();
        long x = (long) Math.floor(axis.xAtFrame(frame));
        if (x >= 0 && x < (long) w) {
            g.setFill(Theme.PLAYHEAD);
            g.fillRect(x, yTop, 1, drawHeight);
        }
    }
}
