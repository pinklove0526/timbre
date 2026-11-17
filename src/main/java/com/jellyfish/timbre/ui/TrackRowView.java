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

import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.LaneGeometry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * One track: its 120 px header on the left and one {@link LaneView} per channel stacked on the right.
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class TrackRowView extends HBox {

    private final Track track;
    private final TrackHeaderView header;
    private final List<LaneView> lanes = new ArrayList<>();

    /**
     * Creates the row.
     *
     * @param model the editor model
     * @param track the track
     */
    public TrackRowView(EditorModel model, Track track) {
        this.track = Objects.requireNonNull(track, "track");
        this.header = new TrackHeaderView(track);
        VBox laneStack = new VBox();
        for (int c = 0; c < track.channelCount(); c++) {
            LaneView lane = new LaneView(model, track, c);
            lanes.add(lane);
            laneStack.getChildren().add(lane);
        }
        getChildren().addAll(header, laneStack);
        setMinHeight((double) LaneGeometry.ROW_STRIDE * track.channelCount());
        setId("trackRow");
    }

    /** @return the track shown */
    public Track track() {
        return track;
    }

    /** @return the header */
    public TrackHeaderView header() {
        return header;
    }

    /** @return the lanes, one per channel, top to bottom (unmodifiable) */
    public List<LaneView> lanes() {
        return List.copyOf(lanes);
    }

    /** Repaints every lane. */
    public void repaintLanes() {
        for (LaneView lane : lanes) {
            lane.repaint();
        }
    }
}
