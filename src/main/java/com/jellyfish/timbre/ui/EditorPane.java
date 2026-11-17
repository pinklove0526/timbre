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
import com.jellyfish.timbre.audio.PlaybackListener;
import com.jellyfish.timbre.audio.WavFile;
import com.jellyfish.timbre.audio.WavReader;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.LaneGeometry;
import com.jellyfish.timbre.ui.viewmodel.ScrollbarMapping;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * The editor window content of Milestone 3 Phase B1: the read-only display of the project (ruler, track
 * headers, waveform lanes, overlay, horizontal scrollbar, status bar) with the transport buttons and
 * File &gt; Open WAV of the Milestone 2 harness. It replaces {@code TransportHarnessPane}; the position
 * slider and the old time readout are gone (the overlay and the status bar show the position).
 *
 * <p>The only interactive input over the display is the horizontal scrollbar. The cursor and the selection
 * are set from code ({@link EditorModel#setSelection}); Play starts at the selection start.
 *
 * <p>Layers: the waveform layer (every {@link LaneView} and the ruler) repaints only when
 * {@link EditorModel#waveformVersion()} changed; the overlay repaints when its version changed and on every
 * animation pulse while the engine is PLAYING or PAUSED.
 *
 * <p><b>Threading:</b> must be created and used on the JavaFX application thread. Engine callbacks arrive
 * on other threads and are handed over with {@link Platform#runLater(Runnable)}; files are read on a
 * background thread.
 */
public final class EditorPane extends BorderPane implements AutoCloseable {

    private final PlaybackEngine engine = new PlaybackEngine();
    private final EditorModel model = new EditorModel();
    private MixPlan plan = MixPlan.empty();

    private final Label stateLabel = new Label("STOPPED");
    private final Label statsLabel = new Label("Suspected underruns: 0");
    private final Label messageLabel = new Label();
    private final Button playButton = new Button("Play");
    private final Button pauseButton = new Button("Pause");
    private final Button stopButton = new Button("Stop");
    private final MenuItem clearItem = new MenuItem("Clear tracks");

    private final RulerView ruler = new RulerView(model);
    private final VBox tracksBox = new VBox();
    private final OverlayView overlay = new OverlayView(model);
    private final ScrollPane scrollPane = new ScrollPane();
    private final ScrollBar scrollBar = new ScrollBar();
    private final StatusBarView statusBar = new StatusBarView();
    private final List<TrackRowView> rows = new ArrayList<>();

    private boolean updatingBar;
    private int paintedWaveform = -1;
    private int paintedOverlay = -1;
    private double paintedOverlayHeight = -1;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pulse();
        }
    };

    /** Creates the pane and starts its animation timer. */
    public EditorPane() {
        MenuItem open = new MenuItem("Open WAV...");
        open.setOnAction(e -> openWav());
        clearItem.setOnAction(e -> clearTracks());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        MenuBar menuBar = new MenuBar(new Menu("File", null, open, clearItem, new SeparatorMenuItem(), exit));

        HBox transport = new HBox(8, playButton, pauseButton, stopButton, stateLabel, statsLabel, messageLabel);
        transport.setAlignment(Pos.CENTER_LEFT);
        transport.setPadding(new Insets(4, 8, 4, 8));
        messageLabel.setWrapText(false);

        Region rulerSpacer = new Region();
        rulerSpacer.setMinWidth(Theme.HEADER_WIDTH);
        rulerSpacer.setPrefWidth(Theme.HEADER_WIDTH);
        rulerSpacer.setMaxWidth(Theme.HEADER_WIDTH);
        Region rulerPad = new Region();
        HBox rulerRow = new HBox(rulerSpacer, ruler, rulerPad);
        rulerRow.setId("rulerRow");

        StackPane content = new StackPane(tracksBox, overlay);
        StackPane.setAlignment(tracksBox, Pos.TOP_LEFT);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(0, 0, 0, Theme.HEADER_WIDTH));
        scrollPane.setContent(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        scrollPane.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent;");
        scrollPane.setId("trackScroll");
        rulerPad.prefWidthProperty().bind(scrollPane.widthProperty().subtract(content.widthProperty()));
        overlay.heightProperty().bind(tracksBox.heightProperty());
        content.widthProperty().addListener((obs, old, now) ->
                model.setLaneWidth(Math.max(1, now.intValue() - Theme.HEADER_WIDTH)));

        Region barSpacer = new Region();
        barSpacer.setMinWidth(Theme.HEADER_WIDTH);
        barSpacer.setPrefWidth(Theme.HEADER_WIDTH);
        barSpacer.setMaxWidth(Theme.HEADER_WIDTH);
        Region barPad = new Region();
        scrollBar.setOrientation(Orientation.HORIZONTAL);
        scrollBar.setId("hScroll");
        HBox.setHgrow(scrollBar, Priority.NEVER);
        barPad.prefWidthProperty().bind(rulerPad.prefWidthProperty());
        scrollBar.prefWidthProperty().bind(model.laneWidthProperty());
        scrollBar.minWidthProperty().bind(model.laneWidthProperty());
        scrollBar.maxWidthProperty().bind(model.laneWidthProperty());
        HBox barRow = new HBox(barSpacer, scrollBar, barPad);
        scrollBar.valueProperty().addListener((obs, old, now) -> {
            if (!updatingBar) {
                model.setAxis(ScrollbarMapping.apply(now.doubleValue(), model.axis(), model.limits()));
            }
        });

        setTop(new VBox(menuBar, transport, rulerRow));
        setCenter(scrollPane);
        setBottom(new VBox(barRow, statusBar));

        playButton.setOnAction(e -> play());
        pauseButton.setOnAction(e -> togglePause());
        stopButton.setOnAction(e -> engine.stop());
        engine.addListener(new PlaybackListener() {
            @Override
            public void stateChanged(PlaybackEngine.State oldState, PlaybackEngine.State newState) {
                Platform.runLater(EditorPane.this::refreshAll);
            }

            @Override
            public void failed(Throwable error) {
                Platform.runLater(() -> showMessage("Playback failed: " + error.getMessage()));
            }
        });
        refreshAll();
        timer.start();
    }

    // ------------------------------------------------------------------ accessors (also used by tests)

    /** @return the editor model */
    public EditorModel model() {
        return model;
    }

    /** @return the ruler view */
    public RulerView ruler() {
        return ruler;
    }

    /** @return the overlay view */
    public OverlayView overlay() {
        return overlay;
    }

    /** @return the status bar */
    public StatusBarView statusBar() {
        return statusBar;
    }

    /** @return the horizontal scrollbar of the lane area */
    public ScrollBar scrollBar() {
        return scrollBar;
    }

    /** @return the track rows, top to bottom (unmodifiable copy) */
    public List<TrackRowView> rows() {
        return List.copyOf(rows);
    }

    /** @return the vertical scroll pane that holds the track rows */
    public ScrollPane scrollPane() {
        return scrollPane;
    }

    /** @return the engine (state and position only are used in B1) */
    PlaybackEngine engine() {
        return engine;
    }

    // ------------------------------------------------------------------ tracks

    /**
     * Adds a track to the project, the plan and the display.
     *
     * @param track the track
     * @throws IllegalArgumentException if its sample rate differs from the project's
     */
    public void addTrack(Track track) {
        model.addTrack(track);
        TrackRowView row = new TrackRowView(model, track);
        rows.add(row);
        tracksBox.getChildren().add(row);
        plan = MixPlan.of(model.project());
        engine.setPlan(plan);
        refreshAll();
    }

    /**
     * Returns a track name from a file name: the name without its last extension.
     *
     * @param fileName a file name, for example {@code sweep_mono_44100.wav}
     * @return the name without the extension ({@code sweep_mono_44100}); unchanged if there is none
     */
    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private void openWav() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open WAV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("WAV audio", "*.wav", "*.WAV"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        showMessage("Reading " + file.getName() + "...");
        Thread reader = new Thread(() -> {
            try {
                WavFile wav = WavReader.read(file.toPath());
                Platform.runLater(() -> addFromWav(file.getName(), wav));
            } catch (Exception e) {
                Platform.runLater(() -> showMessage("Cannot open " + file.getName() + ": " + e.getMessage()));
            }
        }, "timbre-open-wav");
        reader.setDaemon(true);
        reader.start();
    }

    private void addFromWav(String fileName, WavFile wav) {
        try {
            addTrack(Track.of(baseName(fileName), wav.source()));
            showMessage("Opened " + fileName);
        } catch (IllegalArgumentException e) { // includes SampleRateMismatchException
            showMessage(e.getMessage());
        }
    }

    private void clearTracks() {
        if (engine.state() != PlaybackEngine.State.STOPPED) {
            return;
        }
        model.clear();
        rows.clear();
        tracksBox.getChildren().clear();
        plan = MixPlan.empty();
        engine.setPlan(plan);
        showMessage("");
        refreshAll();
    }

    // ------------------------------------------------------------------ transport

    private void play() {
        try {
            if (!engine.play(model.selection().startFrame())) {
                showMessage("Nothing to play from this position.");
            } else {
                showMessage("");
            }
        } catch (AudioDeviceException e) {
            showMessage(e.getMessage());
        }
    }

    private void togglePause() {
        if (engine.state() == PlaybackEngine.State.PLAYING) {
            engine.pause();
        } else if (engine.state() == PlaybackEngine.State.PAUSED) {
            engine.resume();
        }
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
    }

    private void refreshAll() {
        PlaybackEngine.State s = engine.state();
        stateLabel.setText(s.name());
        playButton.setDisable(plan.isEmpty());
        pauseButton.setText(s == PlaybackEngine.State.PAUSED ? "Resume" : "Pause");
        pauseButton.setDisable(s == PlaybackEngine.State.STOPPED);
        stopButton.setDisable(s == PlaybackEngine.State.STOPPED);
        clearItem.setDisable(s != PlaybackEngine.State.STOPPED);
    }

    // ------------------------------------------------------------------ painting

    private void pulse() {
        PlaybackEngine.State s = engine.state();
        if (s == PlaybackEngine.State.STOPPED) {
            model.clearPlayhead();
        } else {
            model.setPlayheadFrame(engine.positionFrames());
        }
        statsLabel.setText("Suspected underruns: " + engine.stats().suspectedUnderruns());
        repaintNow();
    }

    /**
     * Repaints whatever is dirty: the waveform layer and the ruler if {@link EditorModel#waveformVersion()}
     * changed, the overlay if its version, its height or the playing state says so, and updates the
     * scrollbar and the status bar. Called on every animation pulse; tests call it after a layout pass.
     */
    public void repaintNow() {
        boolean waveformDirty = model.waveformVersion().get() != paintedWaveform;
        if (waveformDirty) {
            paintedWaveform = model.waveformVersion().get();
            syncScrollbar();
            ruler.repaint();
            for (TrackRowView row : rows) {
                row.repaintLanes();
            }
        }
        if (overlay.getWidth() != model.laneWidth()) {
            overlay.setWidth(model.laneWidth());
            paintedOverlay = -1;
        }
        boolean playing = engine.state() != PlaybackEngine.State.STOPPED;
        if (playing || paintedOverlay != model.overlayVersion().get()
                || paintedOverlayHeight != overlay.getHeight()) {
            paintedOverlay = model.overlayVersion().get();
            paintedOverlayHeight = overlay.getHeight();
            overlay.repaint();
            statusBar.update(model.selection(), model.playheadFrame(), model.sampleRate());
        }
    }

    private void syncScrollbar() {
        ScrollbarMapping m = ScrollbarMapping.of(model.axis(), model.limits());
        updatingBar = true;
        try {
            scrollBar.setMin(0);
            scrollBar.setMax(m.max());
            scrollBar.setVisibleAmount(m.visibleAmount());
            scrollBar.setUnitIncrement(m.unitIncrement());
            scrollBar.setBlockIncrement(m.blockIncrement());
            scrollBar.setValue(m.value());
            scrollBar.setDisable(!m.scrollable());
        } finally {
            updatingBar = false;
        }
    }

    /** @return the height in pixels of all lanes of all tracks, 91 per channel */
    public double lanesHeight() {
        double h = 0;
        for (TrackRowView r : rows) {
            h += (double) LaneGeometry.ROW_STRIDE * r.track().channelCount();
        }
        return h;
    }

    /** @return the current axis (shortcut for tests) */
    public TimeAxis axis() {
        return model.axis();
    }

    /** Stops the timer and closes the engine and the model; call from {@code Application.stop()}. */
    @Override
    public void close() {
        timer.stop();
        engine.close();
        model.close();
    }
}
