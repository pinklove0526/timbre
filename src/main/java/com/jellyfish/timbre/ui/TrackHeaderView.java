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
import java.util.Objects;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * The 120 px header of one track: its name and a format line such as {@code Stereo, 44100 Hz, 32-bit float}
 * (the in-memory sample format is always 32-bit float). No mute or solo before Milestone 7. The header is as
 * tall as the track's lanes (91 px per channel).
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class TrackHeaderView extends VBox {

    private final Label nameLabel = new Label();
    private final Label formatLabel = new Label();

    /**
     * Creates the header.
     *
     * @param track the track
     */
    public TrackHeaderView(Track track) {
        Objects.requireNonNull(track, "track");
        nameLabel.setId("trackName");
        formatLabel.setId("trackFormat");
        nameLabel.setText(track.name());
        formatLabel.setText(formatLine(track.channelCount(), track.sampleRate()));
        nameLabel.setStyle("-fx-font-weight: bold;");
        nameLabel.setMaxWidth(Theme.HEADER_WIDTH - 12);
        formatLabel.setMaxWidth(Theme.HEADER_WIDTH - 12);
        formatLabel.setWrapText(true);
        formatLabel.setStyle("-fx-font-size: 10px;");
        getChildren().addAll(nameLabel, formatLabel);
        setSpacing(4);
        setPadding(new Insets(6));
        setStyle("-fx-background-color: " + Theme.HEADER_BACKGROUND_CSS + ";");
        double height = (double) LaneGeometry.ROW_STRIDE * track.channelCount();
        setMinSize(Theme.HEADER_WIDTH, height);
        setPrefSize(Theme.HEADER_WIDTH, height);
        setMaxSize(Theme.HEADER_WIDTH, height);
        setId("trackHeader");
    }

    /**
     * Builds the format line.
     *
     * @param channels   channel count, at least 1
     * @param sampleRate sample rate in Hz
     * @return for example {@code Stereo, 44100 Hz, 32-bit float}; {@code Mono} for one channel and
     *         {@code N channels} above two
     */
    public static String formatLine(int channels, int sampleRate) {
        String layout = channels == 1 ? "Mono" : channels == 2 ? "Stereo" : channels + " channels";
        return layout + ", " + sampleRate + " Hz, 32-bit float";
    }

    /** @return the name label (id {@code trackName}) */
    public Label nameLabel() {
        return nameLabel;
    }

    /** @return the format label (id {@code trackFormat}) */
    public Label formatLabel() {
        return formatLabel;
    }
}
