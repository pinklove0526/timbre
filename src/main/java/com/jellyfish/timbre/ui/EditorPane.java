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

import com.jellyfish.timbre.audio.PlaybackEngine;
import com.jellyfish.timbre.audio.PlaybackListener;
import com.jellyfish.timbre.audio.WavFile;
import com.jellyfish.timbre.audio.WavReader;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.FollowPolicy;
import com.jellyfish.timbre.ui.viewmodel.LaneGeometry;
import com.jellyfish.timbre.ui.viewmodel.ScrollbarMapping;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import com.jellyfish.timbre.ui.viewmodel.TransportRules;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
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

    private final PlaybackEngine engine;
    private final EditorModel model = new EditorModel();
    private final EditorActions actions;
    private final Map<Track, TrackRowView> rowsByTrack = new IdentityHashMap<>();
    private final StringProperty titleBase = new SimpleStringProperty("Timbre");
    private final ReadOnlyStringWrapper title = new ReadOnlyStringWrapper("Timbre");

    private final Label stateLabel = new Label("STOPPED");
    private final Label statsLabel = new Label("Suspected underruns: 0");
    private final Button playButton = new Button("Play");
    private final Button pauseButton = new Button("Pause");
    private final Button stopButton = new Button("Stop");
    private final MenuItem clearItem = new MenuItem("Clear tracks");
    private final MenuBar menuBar = new MenuBar();

    private final RulerView ruler = new RulerView(model);
    private final VBox tracksBox = new VBox();
    private final OverlayView overlay = new OverlayView(model);
    private final Pane inputPane = new Pane();
    private final ScrollPane scrollPane = new ScrollPane();
    private final ScrollBar scrollBar = new ScrollBar();
    private final StatusBarView statusBar = new StatusBarView();
    private final List<TrackRowView> rows = new ArrayList<>();

    private boolean updatingBar;
    private int paintedWaveform = -1;
    private int paintedOverlay = -1;
    private double paintedOverlayHeight = -1;
    private double paintedOffsetY = -1;
    private double paintedRowsHeight = -1;
    private boolean gestureActive;
    private Scene keyScene;
    private final javafx.event.EventHandler<KeyEvent> keyFilter = this::onKey;
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pulse();
        }
    };

    /** Creates the pane with the real audio engine and starts its animation timer. */
    public EditorPane() {
        this(new PlaybackEngine());
    }

    /**
     * Creates the pane around a given engine (tests inject one that plays through the fake sink).
     *
     * @param engine the engine; the pane closes it in {@link #close()}
     */
    EditorPane(PlaybackEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.actions = new EditorActions(model, engine);
        MenuItem open = new MenuItem("Open WAV...");
        open.setOnAction(e -> openWav());
        open.disableProperty().bind(actions.importEnabled().not());
        clearItem.setOnAction(e -> clearTracks());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        menuBar.getMenus().addAll(new Menu("File", null, open, clearItem, new SeparatorMenuItem(), exit),
                new Menu("View", null,
                        item("Zoom In", new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN),
                                actions::zoomIn),
                        item("Zoom Out", new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN),
                                actions::zoomOut),
                        item("Fit Project", new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN),
                                actions::fitProject)),
                new Menu("Select", null,
                        item("Select All", new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN),
                                actions::selectAll),
                        item("Clear Selection", new KeyCodeCombination(KeyCode.ESCAPE), actions::clearSelection),
                        item("Cursor to Start", new KeyCodeCombination(KeyCode.HOME), actions::cursorToStart),
                        item("Cursor to End", new KeyCodeCombination(KeyCode.END), actions::cursorToEnd)),
                editMenu(), trackMenu());

        HBox transport = new HBox(8, playButton, pauseButton, stopButton, stateLabel, statsLabel);
        transport.setAlignment(Pos.CENTER_LEFT);
        transport.setPadding(new Insets(4, 8, 4, 8));
        statusBar.messageLabel().textProperty().bind(model.messageProperty());
        title.bind(Bindings.when(model.modifiedProperty()).then(Bindings.concat(titleBase, " *"))
                .otherwise(titleBase));
        model.tracksVersion().addListener((obs, old, now) -> syncRows());
        model.trackSelectionVersion().addListener((obs, old, now) -> refreshHeaders());

        Region rulerSpacer = new Region();
        rulerSpacer.setMinWidth(Theme.HEADER_WIDTH);
        rulerSpacer.setPrefWidth(Theme.HEADER_WIDTH);
        rulerSpacer.setMaxWidth(Theme.HEADER_WIDTH);
        Region rulerPad = new Region();
        HBox rulerRow = new HBox(rulerSpacer, ruler, rulerPad);
        rulerRow.setId("rulerRow");

        scrollPane.setContent(tracksBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        scrollPane.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent;");
        scrollPane.setId("trackScroll");
        rulerPad.prefWidthProperty().bind(scrollPane.widthProperty().subtract(tracksBox.widthProperty()));
        tracksBox.widthProperty().addListener((obs, old, now) ->
                model.setLaneWidth(Math.max(1, now.intValue() - Theme.HEADER_WIDTH)));
        // Ctrl or Shift wheel over the headers must not scroll the track stack
        scrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown() || e.isShiftDown()) {
                e.consume();
            }
        });

        StackPane center = new StackPane(scrollPane, overlay, inputPane);
        StackPane.setAlignment(overlay, Pos.TOP_LEFT);
        StackPane.setAlignment(inputPane, Pos.TOP_LEFT);
        StackPane.setMargin(overlay, new Insets(0, 0, 0, Theme.HEADER_WIDTH));
        StackPane.setMargin(inputPane, new Insets(0, 0, 0, Theme.HEADER_WIDTH));
        inputPane.setId("input");
        installMouseHandlers();

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
        setCenter(center);
        setBottom(new VBox(barRow, statusBar));

        playButton.setOnAction(e -> actions.play());
        pauseButton.setOnAction(e -> actions.togglePause());
        stopButton.setOnAction(e -> actions.stop());
        sceneProperty().addListener((obs, old, now) -> installKeyFilter(now));
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

    /** @return the engine */
    PlaybackEngine engine() {
        return engine;
    }

    /** @return the interaction actions */
    EditorActions actions() {
        return actions;
    }

    /** @return the transparent input pane over the lane area */
    Pane inputPane() {
        return inputPane;
    }

    /**
     * @return the window title: the application name ("Timbre" unless set with {@link #setTitleBase}), plus
     *         " *" while the history is modified; bind the stage title to it
     */
    public ReadOnlyStringProperty titleProperty() {
        return title.getReadOnlyProperty();
    }

    /**
     * Sets the application name the title starts with.
     *
     * @param name the name, for example "Timbre"
     */
    public void setTitleBase(String name) {
        titleBase.set(Objects.requireNonNull(name, "name"));
    }

    /** @return the menu bar (File, View, Select, Edit, Track) */
    MenuBar menuBar() {
        return menuBar;
    }

    /** @return the Play button */
    Button playButton() {
        return playButton;
    }

    /** @return the Pause / Resume button */
    Button pauseButton() {
        return pauseButton;
    }

    /** @return the Stop button */
    Button stopButton() {
        return stopButton;
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
        actions.rebuildPlan();
        syncRows();
        refreshAll();
    }

    /**
     * Makes the track rows match the project's tracks (same order, one row per track, rows of removed tracks
     * dropped, a row kept for a track that stays so its canvases are reused).
     */
    private void syncRows() {
        List<Track> tracks = model.project().tracks();
        rowsByTrack.keySet().removeIf(t -> model.project().indexOf(t) < 0);
        rows.clear();
        for (Track t : tracks) {
            TrackRowView row = rowsByTrack.computeIfAbsent(t, this::newRow);
            rows.add(row);
        }
        tracksBox.getChildren().setAll(rows);
        refreshHeaders();
        model.markWaveformDirty();
    }

    private TrackRowView newRow(Track track) {
        TrackRowView row = new TrackRowView(model, track);
        row.header().setOnMousePressed(e -> {
            int index = model.project().indexOf(track);
            if (index >= 0 && e.getButton() == MouseButton.PRIMARY) {
                actions.selectTrack(index, e.isShortcutDown(), e.isShiftDown());
            }
            e.consume();
        });
        return row;
    }

    private void refreshHeaders() {
        for (TrackRowView row : rows) {
            row.header().setSelected(model.trackSelection().isSelected(row.track()));
        }
    }

    private Menu editMenu() {
        MenuItem undo = item("Undo", new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN), actions::undo);
        undo.textProperty().bind(actions.undoLabel());
        undo.disableProperty().bind(actions.undoEnabled().not());
        MenuItem redo = item("Redo", new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN), actions::redo);
        redo.textProperty().bind(actions.redoLabel());
        redo.disableProperty().bind(actions.redoEnabled().not());
        MenuItem cut = item("Cut", new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN), actions::cut);
        cut.disableProperty().bind(actions.cutEnabled().not());
        MenuItem copy = item("Copy", new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN), actions::copy);
        copy.disableProperty().bind(actions.copyEnabled().not());
        MenuItem paste = item("Paste", new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN),
                actions::paste);
        paste.disableProperty().bind(actions.pasteEnabled().not());
        MenuItem delete = item("Delete", new KeyCodeCombination(KeyCode.DELETE), actions::deleteSelection);
        delete.disableProperty().bind(actions.cutEnabled().not());
        MenuItem silence = item("Silence", new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
                actions::silence);
        silence.disableProperty().bind(actions.cutEnabled().not());
        MenuItem trim = item("Trim", new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN), actions::trim);
        trim.disableProperty().bind(actions.cutEnabled().not());
        MenuItem split = item("Split", new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN),
                actions::split);
        split.disableProperty().bind(actions.splitEnabled().not());
        return new Menu("Edit", null, undo, redo, new SeparatorMenuItem(), cut, copy, paste, delete, silence, trim,
                split);
    }

    private Menu trackMenu() {
        MenuItem remove = new MenuItem("Remove Track");
        remove.setOnAction(e -> actions.removeSelectedTracks());
        remove.disableProperty().bind(actions.removeTrackEnabled().not());
        return new Menu("Track", null, remove);
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
        if (actions.importTrack(Track.of(baseName(fileName), wav.source()))) {
            showMessage("Opened " + fileName);
        }
    }

    private void clearTracks() {
        actions.clearTracks();
        showMessage("");
        refreshAll();
    }

    // ------------------------------------------------------------------ menus, keys, mouse

    private static MenuItem item(String text, KeyCombination accelerator, Runnable action) {
        MenuItem m = new MenuItem(text);
        m.setAccelerator(accelerator);
        m.setOnAction(e -> action.run());
        return m;
    }

    private void installKeyFilter(Scene scene) {
        if (keyScene != null) {
            keyScene.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            keyScene.removeEventFilter(KeyEvent.KEY_RELEASED, keyFilter);
        }
        keyScene = scene;
        if (scene != null) {
            scene.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
            scene.addEventFilter(KeyEvent.KEY_RELEASED, keyFilter); // only to swallow the release of Space
        }
    }

    /**
     * The single key filter. It handles and consumes the transport, selection and view shortcuts itself (the
     * View and Select menu accelerators exist for display and are not reached because the event is consumed
     * here), plus the two edit keys that cannot be accelerators (Backspace for Delete, Ctrl/Cmd+Shift+Z for
     * Redo). The other edit shortcuts are the Edit menu's accelerators, which do not fire while their item is
     * disabled. Space is consumed on press and on release so a focused button never fires.
     */
    private void onKey(KeyEvent e) {
        KeyCode code = e.getCode();
        boolean shortcutOnly = e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown();
        boolean plain = !e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown() && !e.isMetaDown()
                && !e.isControlDown();
        if (e.getEventType() == KeyEvent.KEY_RELEASED) {
            if (code == KeyCode.SPACE && plain) {
                e.consume();
            }
            return;
        }
        Runnable action = null;
        if (plain) {
            switch (code) {
                case SPACE -> action = actions::toggleSpace;
                case P -> action = actions::togglePause;
                case ESCAPE -> action = actions::clearSelection;
                case HOME -> action = actions::cursorToStart;
                case END -> action = actions::cursorToEnd;
                case BACK_SPACE -> action = actions::deleteSelection; // a second key for Delete: no accelerator
                default -> { }
            }
        } else if (e.isShortcutDown() && e.isShiftDown() && !e.isAltDown() && code == KeyCode.Z) {
            action = actions::redo; // a second key for Redo: no accelerator
        } else if (shortcutOnly) {
            switch (code) {
                case A -> action = actions::selectAll;
                case DIGIT1 -> action = actions::zoomIn;
                case DIGIT3 -> action = actions::zoomOut;
                case F -> action = actions::fitProject;
                default -> { }
            }
        }
        if (action != null) {
            e.consume();
            action.run();
        }
    }

    private void installMouseHandlers() {
        inputPane.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getY() + scrollOffsetY() < lanesHeight()) {
                gestureActive = actions.onPress(e.getX(), e.isShiftDown());
            } else {
                gestureActive = false;
            }
            e.consume();
        });
        inputPane.setOnMouseDragged(e -> {
            if (gestureActive) {
                actions.onDrag(e.getX());
            }
            e.consume();
        });
        inputPane.setOnMouseReleased(e -> {
            if (gestureActive) {
                actions.onRelease(e.getX());
                gestureActive = false;
            }
            e.consume();
        });
        inputPane.addEventHandler(ScrollEvent.SCROLL, e -> {
            boolean used = actions.onWheel(e.getX(), e.getDeltaX(), e.getDeltaY(), e.isShortcutDown(),
                    e.isShiftDown());
            if (!used && e.getDeltaY() != 0) {
                scrollVertical(e.getDeltaY());
                used = true;
            }
            if (used) {
                e.consume();
            }
        });
    }

    /** @return the viewport height of the track stack, at least 1 */
    private double viewportHeight() {
        return Math.max(1.0, scrollPane.getViewportBounds().getHeight());
    }

    /** @return how far the track stack is scrolled up, in pixels */
    private double scrollOffsetY() {
        double range = lanesHeight() - viewportHeight();
        return range <= 0 ? 0 : scrollPane.getVvalue() * range;
    }

    private void scrollVertical(double deltaY) {
        double range = lanesHeight() - viewportHeight();
        if (range <= 0) {
            return;
        }
        double offset = Math.min(range, Math.max(0, scrollOffsetY() - deltaY));
        scrollPane.setVvalue(offset / range);
    }

    // ------------------------------------------------------------------ transport

    private void showMessage(String text) {
        model.setMessage(text);
    }

    private void refreshAll() {
        PlaybackEngine.State s = engine.state();
        stateLabel.setText(s.name());
        playButton.setDisable(!TransportRules.playEnabled(s) || actions.plan().isEmpty());
        pauseButton.setText(TransportRules.pauseLabelIsResume(s) ? "Resume" : "Pause");
        pauseButton.setDisable(!TransportRules.pauseEnabled(s));
        stopButton.setDisable(!TransportRules.stopEnabled(s));
        actions.refreshEnabled();
    }

    // ------------------------------------------------------------------ painting

    private void pulse() {
        PlaybackEngine.State s = engine.state();
        if (s == PlaybackEngine.State.STOPPED) {
            model.clearPlayhead();
        } else {
            long pos = engine.positionFrames();
            model.setPlayheadFrame(pos);
            if (s == PlaybackEngine.State.PLAYING) {
                follow(pos);
            }
        }
        statsLabel.setText("Suspected underruns: " + engine.stats().suspectedUnderruns());
        repaintNow();
    }

    /** Pages the view when the playhead leaves the window or passes 95% of its width ({@link FollowPolicy}). */
    private void follow(long playheadFrame) {
        TimeAxis a = model.axis();
        TimeAxis.Limits limits = model.limits();
        long scroll = FollowPolicy.scrollFor(playheadFrame, a, model.laneWidth(), limits);
        if (scroll != a.scrollFrame()) {
            model.setAxis(a.scrolledTo(scroll, limits));
        }
    }

    /**
     * Stops the animation timer so that pulses only happen when {@link #pulseNow()} is called. A test hook
     * for deterministic event-driven tests; {@link #close()} stops the timer anyway.
     */
    void stopAnimationTimer() {
        timer.stop();
    }

    /**
     * Runs one animation pulse now: reads the engine state, moves the playhead, applies the follow step
     * while PLAYING and repaints what is dirty. The animation timer calls the same method; tests call it
     * on the FX thread to avoid waiting for a pulse.
     */
    void pulseNow() {
        pulse();
    }

    /**
     * Repaints whatever is dirty: the waveform layer and the ruler if {@link EditorModel#waveformVersion()}
     * changed; the overlay if its version, its size, the vertical scroll offset or the rows extent changed
     * or the engine is not STOPPED; and updates the scrollbar and the status bar. Called on every animation
     * pulse; tests call it after a layout pass.
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
        double w = model.laneWidth();
        double h = viewportHeight();
        if (overlay.getWidth() != w || overlay.getHeight() != h) {
            overlay.setWidth(w);
            overlay.setHeight(h);
            inputPane.setPrefSize(w, h);
            inputPane.setMinSize(w, h);
            inputPane.setMaxSize(w, h);
            paintedOverlay = -1;
        }
        double offsetY = scrollOffsetY();
        double rowsH = lanesHeight();
        boolean playing = engine.state() != PlaybackEngine.State.STOPPED;
        if (playing || paintedOverlay != model.overlayVersion().get() || paintedOffsetY != offsetY
                || paintedRowsHeight != rowsH) {
            paintedOverlay = model.overlayVersion().get();
            paintedOffsetY = offsetY;
            paintedRowsHeight = rowsH;
            overlay.setViewport(offsetY, rowsH);
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
