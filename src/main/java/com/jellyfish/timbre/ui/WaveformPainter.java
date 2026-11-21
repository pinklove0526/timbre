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

import com.jellyfish.timbre.ui.viewmodel.LaneColumns;
import com.jellyfish.timbre.ui.viewmodel.LaneGeometry;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.util.Objects;
import javafx.scene.canvas.GraphicsContext;

/**
 * Pure drawing of one lane: paints a {@link LaneColumns} result onto a {@link GraphicsContext} at a given
 * origin. It keeps no state. Rows follow {@link LaneGeometry}; everything is drawn on whole pixels, so the
 * result has no anti-aliasing except the SAMPLE_LINES polyline.
 *
 * <p>Order: background (rows 0 to 89), centre line, then per column the peak band and the RMS band on top
 * of it (PEAKS and DIRECT; a silent column, min and max both 0, draws no band), or the polyline and dots (SAMPLE_LINES), nothing more for PLACEHOLDER; the
 * separator row (row 90) is drawn last.
 *
 * <p><b>Threading:</b> JavaFX application thread only (it draws on a canvas).
 */
public final class WaveformPainter {

    private WaveformPainter() {
    }

    /**
     * Paints one lane, including its separator row.
     *
     * @param g       the graphics context of the lane canvas
     * @param columns the sampled columns of the lane
     * @param axis    the axis the columns were sampled with
     * @param originY the canvas row of the lane's row 0
     * @param width   the number of columns to paint, at most {@code columns.width()}
     * @throws IllegalArgumentException if {@code width} is out of range
     */
    public static void paint(GraphicsContext g, LaneColumns columns, TimeAxis axis, double originY, int width) {
        paint(g, columns, axis, originY, width, new int[0]);
    }

    /**
     * Paints one lane with clip borders, including its separator row.
     *
     * @param g             the graphics context of the lane canvas
     * @param columns       the sampled columns of the lane
     * @param axis          the axis the columns were sampled with
     * @param originY       the canvas row of the lane's row 0
     * @param width         the number of columns to paint, at most {@code columns.width()}
     * @param borderColumns the clip border columns ({@link com.jellyfish.timbre.ui.viewmodel.ClipBoundaries});
     *                      columns outside {@code [0, width)} are ignored
     * @throws IllegalArgumentException if {@code width} is out of range
     */
    public static void paint(GraphicsContext g, LaneColumns columns, TimeAxis axis, double originY, int width,
            int[] borderColumns) {
        Objects.requireNonNull(g, "g");
        Objects.requireNonNull(borderColumns, "borderColumns");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(axis, "axis");
        if (width < 0 || width > columns.width()) {
            throw new IllegalArgumentException("width " + width + " not in [0, " + columns.width() + "]");
        }
        g.setFill(Theme.LANE_BACKGROUND);
        g.fillRect(0, originY, width, LaneGeometry.LANE_HEIGHT);
        g.setFill(Theme.CENTER_LINE);
        g.fillRect(0, originY + LaneGeometry.CENTER_ROW, width, 1);
        switch (columns.mode()) {
            case PEAKS, DIRECT -> paintBands(g, columns, originY, width);
            case SAMPLE_LINES -> paintSamples(g, columns, axis, originY);
            case PLACEHOLDER -> {
                // background and centre line only
            }
        }
        g.setFill(Theme.CLIP_BORDER);
        for (int x : borderColumns) {
            if (x >= 0 && x < width) {
                g.fillRect(x, originY, 1, LaneGeometry.LANE_HEIGHT);
            }
        }
        g.setFill(Theme.SEPARATOR);
        g.fillRect(0, originY + LaneGeometry.SEPARATOR_ROW, width, LaneGeometry.SEPARATOR_HEIGHT);
    }

    private static void paintBands(GraphicsContext g, LaneColumns columns, double originY, int width) {
        for (int x = 0; x < width; x++) {
            if (LaneGeometry.isSilent(columns.min(x), columns.max(x))) {
                continue; // silence and gaps: centre line only
            }
            LaneGeometry.Band peak = LaneGeometry.peakBand(columns.min(x), columns.max(x));
            g.setFill(Theme.PEAK);
            g.fillRect(x, originY + peak.top(), 1, peak.height());
            LaneGeometry.Band rms = LaneGeometry.rmsBand(peak, columns.rms(x));
            g.setFill(Theme.RMS);
            g.fillRect(x, originY + rms.top(), 1, rms.height());
        }
    }

    private static void paintSamples(GraphicsContext g, LaneColumns columns, TimeAxis axis, double originY) {
        int n = columns.spanCount();
        long start = columns.spanStart();
        g.setStroke(Theme.SAMPLE_LINE);
        g.setLineWidth(1.0);
        g.beginPath();
        for (int i = 0; i < n; i++) {
            double x = axis.xAtFrame(start + i);
            double y = originY + LaneGeometry.yOf(columns.sample(i)) + 0.5;
            if (i == 0) {
                g.moveTo(x, y);
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
        if (LaneGeometry.showDots(axis.framesPerPixel())) {
            g.setFill(Theme.SAMPLE_LINE);
            int half = LaneGeometry.DOT_SIZE / 2;
            for (int i = 0; i < n; i++) {
                long cx = Math.round(axis.xAtFrame(start + i));
                int row = LaneGeometry.yOf(columns.sample(i));
                g.fillRect(cx - half, originY + row - half, LaneGeometry.DOT_SIZE, LaneGeometry.DOT_SIZE);
            }
        }
    }
}
