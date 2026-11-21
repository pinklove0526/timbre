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

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.ClipBoundaries;
import com.jellyfish.timbre.ui.viewmodel.LaneColumns;
import com.jellyfish.timbre.ui.viewmodel.LaneGeometry;
import com.jellyfish.timbre.ui.viewmodel.LaneSampler;
import java.util.List;
import java.util.Objects;
import javafx.scene.canvas.Canvas;

/**
 * One channel lane of one track: a canvas of {@code laneWidth x 91} pixels (90 rows of waveform and the
 * separator row). It owns a reusable {@link LaneColumns} buffer and asks the {@link LaneSampler} on every
 * repaint; the pyramid lookup is the model's cache. Clip borders come from {@link ClipBoundaries}.
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class LaneView extends Canvas {

    private final EditorModel model;
    private final Track track;
    private final int channel;
    private final LaneSampler sampler = new LaneSampler();
    private final LaneColumns columns = new LaneColumns();

    /**
     * Creates a lane.
     *
     * @param model   the editor model (axis, lane width, pyramid cache)
     * @param track   the track
     * @param channel the channel of this lane, {@code 0 <= channel < track.channelCount()}
     * @throws IllegalArgumentException if the channel is out of range
     */
    public LaneView(EditorModel model, Track track, int channel) {
        super(model.laneWidth(), LaneGeometry.ROW_STRIDE);
        this.model = Objects.requireNonNull(model, "model");
        this.track = Objects.requireNonNull(track, "track");
        if (channel < 0 || channel >= track.channelCount()) {
            throw new IllegalArgumentException("channel " + channel + " not in [0, " + track.channelCount() + ")");
        }
        this.channel = channel;
        setId("lane-" + channel);
    }

    /** @return the channel of this lane */
    public int channel() {
        return channel;
    }

    /** @return the columns sampled by the last repaint (valid on the FX thread only) */
    public LaneColumns columns() {
        return columns;
    }

    /** Resizes the canvas to the model's lane width and redraws the lane. */
    public void repaint() {
        int width = model.laneWidth();
        if (getWidth() != width) {
            setWidth(width);
        }
        sampler.sample(track.clips(), channel, src -> model.cache().getIfReady(src).orElse(null), model.axis(),
                width, columns);
        List<Clip> clips = track.clips();
        WaveformPainter.paint(getGraphicsContext2D(), columns, model.axis(), 0, width,
                ClipBoundaries.columns(clips, model.axis(), width));
    }
}
