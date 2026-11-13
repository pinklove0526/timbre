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
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Track;
import java.io.File;
import java.util.Locale;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * TEMPORARY transport harness for Milestone 2 (replaced by the waveform cursor in Milestone 3): open WAV
 * files as tracks, Play / Pause / Stop, a time readout and a position slider.
 *
 * <p><b>Threading:</b> must be created and used on the JavaFX application thread. Engine callbacks arrive
 * on other threads and are handed over with {@link Platform#runLater(Runnable)}; files are read on a
 * background thread. While STOPPED, dragging the slider sets the frame the next play starts at; while
 * PLAYING or PAUSED the slider is display-only.
 */
public final class TransportHarnessPane extends BorderPane implements AutoCloseable {

    private final PlaybackEngine engine = new PlaybackEngine();
    private Project project = new Project();
    private MixPlan plan = MixPlan.empty();
    private long startCursor;
    private boolean updatingSlider;

    private final Label tracksLabel = new Label("No tracks. File > Open WAV...");
    private final Label timeLabel = new Label("00:00.000 / 00:00.000");
    private final Label stateLabel = new Label("STOPPED");
    private final Label statsLabel = new Label("Suspected underruns: 0");
    private final Label messageLabel = new Label();
    private final Slider slider = new Slider(0, 1, 0);
    private final Button playButton = new Button("Play");
    private final Button pauseButton = new Button("Pause");
    private final Button stopButton = new Button("Stop");
    private final MenuItem clearItem = new MenuItem("Clear tracks");
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            refreshPosition();
        }
    };

    /** Creates the pane and starts its animation timer. */
    public TransportHarnessPane() {
        MenuItem open = new MenuItem("Open WAV...");
        open.setOnAction(e -> openWav());
        clearItem.setOnAction(e -> clearTracks());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        setTop(new MenuBar(new Menu("File", null, open, clearItem, new javafx.scene.control.SeparatorMenuItem(),
                exit)));

        HBox buttons = new HBox(8, playButton, pauseButton, stopButton);
        messageLabel.setWrapText(true);
        VBox center = new VBox(10, tracksLabel, timeLabel, slider, buttons, stateLabel, statsLabel, messageLabel);
        center.setPadding(new Insets(16));
        setCenter(center);

        playButton.setOnAction(e -> play());
        pauseButton.setOnAction(e -> togglePause());
        stopButton.setOnAction(e -> engine.stop());
        slider.valueProperty().addListener((obs, old, now) -> {
            if (!updatingSlider && engine.state() == PlaybackEngine.State.STOPPED) {
                startCursor = (long) now.doubleValue();
                refreshPosition();
            }
        });
        engine.addListener(new PlaybackListener() {
            @Override
            public void stateChanged(PlaybackEngine.State oldState, PlaybackEngine.State newState) {
                Platform.runLater(TransportHarnessPane.this::refreshAll);
            }

            @Override
            public void failed(Throwable error) {
                Platform.runLater(() -> showMessage("Playback failed: " + error.getMessage()));
            }
        });
        refreshAll();
        timer.start();
    }

    private void play() {
        try {
            if (!engine.play(startCursor)) {
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
                Platform.runLater(() -> addTrack(file.getName(), wav));
            } catch (Exception e) {
                Platform.runLater(() -> showMessage("Cannot open " + file.getName() + ": " + e.getMessage()));
            }
        }, "timbre-open-wav");
        reader.setDaemon(true);
        reader.start();
    }

    private void addTrack(String name, WavFile wav) {
        try {
            project.addTrack(Track.of(name, wav.source()));
            plan = MixPlan.of(project);
            engine.setPlan(plan);
            showMessage("Opened " + name);
        } catch (IllegalArgumentException e) { // includes SampleRateMismatchException
            showMessage(e.getMessage());
        }
        refreshAll();
    }

    private void clearTracks() {
        if (engine.state() != PlaybackEngine.State.STOPPED) {
            return;
        }
        project = new Project();
        plan = MixPlan.empty();
        engine.setPlan(plan);
        startCursor = 0;
        showMessage("");
        refreshAll();
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
    }

    private void refreshAll() {
        PlaybackEngine.State s = engine.state();
        tracksLabel.setText(project.tracks().isEmpty() ? "No tracks. File > Open WAV..."
                : "Tracks: " + project.tracks().stream().map(Track::name).toList());
        stateLabel.setText(s.name());
        playButton.setDisable(plan.isEmpty());
        pauseButton.setText(s == PlaybackEngine.State.PAUSED ? "Resume" : "Pause");
        pauseButton.setDisable(s == PlaybackEngine.State.STOPPED);
        stopButton.setDisable(s == PlaybackEngine.State.STOPPED);
        clearItem.setDisable(s != PlaybackEngine.State.STOPPED);
        slider.setDisable(s != PlaybackEngine.State.STOPPED || plan.isEmpty());
        updatingSlider = true;
        slider.setMax(Math.max(1, plan.length()));
        updatingSlider = false;
        refreshPosition();
    }

    private void refreshPosition() {
        PlaybackEngine.State s = engine.state();
        long pos = s == PlaybackEngine.State.STOPPED ? startCursor : engine.positionFrames();
        updatingSlider = true;
        slider.setValue(pos);
        updatingSlider = false;
        timeLabel.setText(format(pos, plan.sampleRate()) + " / " + format(plan.length(), plan.sampleRate()));
        statsLabel.setText("Suspected underruns: " + engine.stats().suspectedUnderruns());
    }

    private static String format(long frames, int rate) {
        long ms = rate <= 0 ? 0 : frames * 1000 / rate;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000);
    }

    /** Stops the timer and closes the engine; call from {@code Application.stop()}. */
    @Override
    public void close() {
        timer.stop();
        engine.close();
    }
}
