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
import com.jellyfish.timbre.ui.viewmodel.TimeFormat;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/**
 * The status bar: the cursor time and the selection start, end and length, all in the fixed format
 * {@code h:mm:ss.mmm} so the text never changes width.
 *
 * <p><b>Threading:</b> JavaFX application thread only.
 */
public final class StatusBarView extends HBox {

    private final Label cursorLabel = new Label();
    private final Label selectionLabel = new Label();

    /** Creates the bar showing the zero state. */
    public StatusBarView() {
        cursorLabel.setId("statusCursor");
        selectionLabel.setId("statusSelection");
        String mono = "-fx-font-family: 'monospaced';";
        cursorLabel.setStyle(mono);
        selectionLabel.setStyle(mono);
        getChildren().addAll(cursorLabel, selectionLabel);
        setSpacing(24);
        setPadding(new Insets(3, 8, 3, 8));
        setId("statusBar");
        update(Selection.cursor(0), -1, 44100);
    }

    /**
     * Formats the cursor text.
     *
     * @param cursorFrame the cursor frame, at least 0
     * @param sampleRate  the sample rate
     * @return {@code Cursor: h:mm:ss.mmm}
     */
    public static String cursorText(long cursorFrame, int sampleRate) {
        return "Cursor: " + TimeFormat.clock(cursorFrame, sampleRate);
    }

    /**
     * Formats the selection text.
     *
     * @param selection  the selection
     * @param sampleRate the sample rate
     * @return {@code Selection: start - end (length)}, each in {@code h:mm:ss.mmm}
     */
    public static String selectionText(Selection selection, int sampleRate) {
        return "Selection: " + TimeFormat.clock(selection.startFrame(), sampleRate) + " - "
                + TimeFormat.clock(selection.endFrame(), sampleRate) + " ("
                + TimeFormat.clock(selection.length(), sampleRate) + ")";
    }

    /**
     * Shows a state.
     *
     * @param selection     the selection (the cursor is its start unless a playhead is given)
     * @param playheadFrame the playhead frame, or a negative value for none
     * @param sampleRate    the sample rate in Hz, greater than zero
     */
    public void update(Selection selection, long playheadFrame, int sampleRate) {
        long cursor = playheadFrame >= 0 ? playheadFrame : selection.startFrame();
        cursorLabel.setText(cursorText(cursor, sampleRate));
        selectionLabel.setText(selectionText(selection, sampleRate));
    }

    /** @return the cursor label (id {@code statusCursor}) */
    public Label cursorLabel() {
        return cursorLabel;
    }

    /** @return the selection label (id {@code statusSelection}) */
    public Label selectionLabel() {
        return selectionLabel;
    }
}
