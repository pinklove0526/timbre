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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.FakeEngineRig;
import com.jellyfish.timbre.audio.PlaybackEngine;
import com.jellyfish.timbre.audio.WavEncoding;
import com.jellyfish.timbre.audio.WavWriter;
import com.jellyfish.timbre.dsp.PeakPyramid;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.ClipBoundaries;
import com.jellyfish.timbre.ui.viewmodel.ScrollbarMapping;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in editing test ({@code -Dtimbre.fx=true}): an {@link EditorPane} on a fake-sink engine (nothing is ever
 * played) in an unshown 1000 x 600 Scene, driven by synthetic key and mouse events and menu actions; the
 * animation timer is stopped and every pulse is explicit. Sample rate 8000 Hz; the axis is set to 10 frames
 * per pixel from scroll 0 unless stated, so lane column {@code x} covers frames {@code [10 x, 10 x + 10)}.
 * Source samples are unique per source and index and asymmetric: {@code v = sign * (id / 100 + i / 10000)}
 * with the sign + for even and -1/2 for odd indices (channel 1: {@code -0.7 v}). Expected values are
 * hand-computed or come from the brute-force renderer below; PNGs go to {@code build-logs/m4b/png}.
 */
@EnabledIfSystemProperty(named = "timbre.fx", matches = "true")
class EditorEditingFxTest {

    private static final int RATE = 8000;
    private static final Path PNG_DIR = Path.of(System.getProperty("timbre.fx.png.m4b", "build-logs/m4b/png"));
    private static final int TOL = 3;

    @TempDir
    Path tmp;

    @BeforeAll
    static void startToolkit() throws IOException {
        Files.createDirectories(PNG_DIR);
        try {
            CountDownLatch up = new CountDownLatch(1);
            Platform.startup(up::countDown);
            assertTrue(up.await(20, TimeUnit.SECONDS), "toolkit did not start");
        } catch (IllegalStateException alreadyRunning) {
            // started by an earlier test class
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        Platform.setImplicitExit(false);
    }

    // ------------------------------------------------------------------ data

    static float value(int id, int i) {
        float v = id / 100f + i / 10000f;
        return i % 2 == 0 ? v : -0.5f * v;
    }

    static MemorySampleSource src(int id, int channels, int frames, int rate) {
        float[][] d = new float[channels][frames];
        for (int i = 0; i < frames; i++) {
            d[0][i] = value(id, i);
            if (channels > 1) {
                d[1][i] = -0.7f * value(id, i);
            }
        }
        return MemorySampleSource.copyOf(rate, d);
    }

    static Track whole(String name, MemorySampleSource s) {
        Track t = new Track(name, s.sampleRate(), s.channelCount());
        t.addClip(new Clip(s, 0, s.frameCount(), 0));
        return t;
    }

    /** Brute-force rendering of channel 0 (independent of ClipOps, MixPlan and the painter). */
    static float[] render(List<Clip> clips) {
        long end = 0;
        for (Clip c : clips) {
            end = Math.max(end, c.timelineStart() + c.frameCount());
        }
        float[] out = new float[(int) end];
        for (Clip c : clips) {
            float[][] buf = new float[c.source().channelCount()][(int) c.frameCount()];
            c.source().read(c.sourceStart(), (int) c.frameCount(), buf, 0);
            System.arraycopy(buf[0], 0, out, (int) c.timelineStart(), (int) c.frameCount());
        }
        return out;
    }

    static float[] range(int id, int from, int to) {
        float[] out = new float[to - from];
        for (int i = from; i < to; i++) {
            out[i - from] = value(id, i);
        }
        return out;
    }

    static float[] concat(float[]... parts) {
        int n = 0;
        for (float[] p : parts) {
            n += p.length;
        }
        float[] out = new float[n];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    // ------------------------------------------------------------------ fixture

    private static final class Fx implements AutoCloseable {
        final FakeEngineRig rig = new FakeEngineRig();
        EditorPane pane;
        Scene scene;
        EditorModel model;
        EditorActions actions;
        int lane;

        @Override
        public void close() {
            try {
                fx(() -> {
                    pane.close();
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static <T> T fx(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        FutureTask<T> f = new FutureTask<>(task);
        Platform.runLater(f);
        return f.get(30, TimeUnit.SECONDS);
    }

    private static void run(Fx f, Runnable r) throws Exception {
        fx(() -> {
            r.run();
            f.pane.applyCss();
            f.pane.layout();
            f.pane.repaintNow();
            return null;
        });
    }

    /** A pane with the given tracks added directly (setup only, not undoable) and the axis at 10 fpp. */
    private static Fx open(Track... tracks) throws Exception {
        Fx f = new Fx();
        fx(() -> {
            f.pane = new EditorPane(f.rig.engine());
            f.pane.stopAnimationTimer();
            f.scene = new Scene(f.pane, 1000, 600);
            f.model = f.pane.model();
            f.actions = f.pane.actions();
            f.pane.applyCss();
            f.pane.layout();
            for (Track t : tracks) {
                f.pane.addTrack(t);
            }
            f.pane.applyCss();
            f.pane.layout();
            f.lane = f.model.laneWidth();
            if (tracks.length > 0) {
                f.model.setAxis(new TimeAxis(tracks[0].sampleRate(), 10.0, 0));
            }
            f.pane.layout();
            f.pane.repaintNow();
            return null;
        });
        return f;
    }

    private static void key(Fx f, KeyCode code, boolean shortcut, boolean shift) throws Exception {
        keyOn(f, f.scene, code, shortcut, shift);
    }

    private static void keyOn(Fx f, EventTarget target, KeyCode code, boolean shortcut, boolean shift)
            throws Exception {
        run(f, () -> Event.fireEvent(target, new KeyEvent(target, target, KeyEvent.KEY_PRESSED,
                KeyEvent.CHAR_UNDEFINED, "", code, shift, shortcut, false, false)));
        run(f, () -> Event.fireEvent(target, new KeyEvent(target, target, KeyEvent.KEY_RELEASED,
                KeyEvent.CHAR_UNDEFINED, "", code, shift, shortcut, false, false)));
    }

    private static void headerClick(Fx f, int index, boolean shortcut, boolean shift) throws Exception {
        run(f, () -> {
            Node h = f.pane.rows().get(index).header();
            Event.fireEvent(h, new MouseEvent(h, h, MouseEvent.MOUSE_PRESSED, 60, 20, 60, 20, MouseButton.PRIMARY,
                    1, shift, shortcut, false, false, true, false, false, false, false, false, null));
        });
    }

    private static void select(Fx f, Selection s) throws Exception {
        run(f, () -> f.model.setSelection(s));
    }

    private static Menu menu(Fx f, String name) throws Exception {
        return fx(() -> f.pane.menuBar().getMenus().stream().filter(m -> m.getText().equals(name)).findFirst()
                .orElseThrow());
    }

    /** Edit menu: 0 Undo, 1 Redo, 2 separator, 3 Cut, 4 Copy, 5 Paste, 6 Delete, 7 Silence, 8 Trim, 9 Split. */
    private static MenuItem edit(Fx f, int index) throws Exception {
        return menu(f, "Edit").getItems().get(index);
    }

    private static void fire(Fx f, MenuItem item) throws Exception {
        run(f, item::fire);
    }

    private static Track track(Fx f, int index) throws Exception {
        return fx(() -> f.model.project().tracks().get(index));
    }

    private static List<String> names(Fx f) throws Exception {
        return fx(() -> f.model.project().tracks().stream().map(Track::name).toList());
    }

    private static Selection selection(Fx f) throws Exception {
        return fx(() -> f.model.selection());
    }

    private static long frames(Fx f) throws Exception {
        return fx(() -> f.model.projectFrames());
    }

    private static List<Clip> clips(Fx f, int index) throws Exception {
        return fx(() -> f.model.project().tracks().get(index).clips());
    }

    private static boolean disabled(Fx f, MenuItem item) throws Exception {
        return fx(item::isDisable);
    }

    private static void play(Fx f) throws Exception {
        key(f, KeyCode.SPACE, false, false);
        awaitState(f, PlaybackEngine.State.PLAYING);
    }

    private static void awaitState(Fx f, PlaybackEngine.State expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (f.rig.engine().state() != expected) {
            assertTrue(System.nanoTime() < deadline, "engine stayed " + f.rig.engine().state());
            Thread.sleep(1);
        }
        run(f, () -> { });
    }

    // pixels -------------------------------------------------------------------------------

    private static BufferedImage snap(Node node, String name) throws Exception {
        return fx(() -> {
            SnapshotParameters p = new SnapshotParameters();
            p.setFill(Color.WHITE);
            WritableImage img = node.snapshot(p, null);
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            PixelReader r = img.getPixelReader();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setRGB(x, y, r.getArgb(x, y));
                }
            }
            if (name != null) {
                ImageIO.write(out, "png", PNG_DIR.resolve(name + ".png").toFile());
            }
            return out;
        });
    }

    private static BufferedImage lane(Fx f, int track, int channel, String name) throws Exception {
        return snap(fx(() -> f.pane.rows().get(track).lanes().get(channel)), name);
    }

    private static int argb(Color c) {
        return (0xFF << 24) | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }

    private static boolean near(int a, int b) {
        for (int s = 0; s <= 24; s += 8) {
            if (Math.abs(((a >> s) & 0xFF) - ((b >> s) & 0xFF)) > TOL) {
                return false;
            }
        }
        return true;
    }

    private static void assertPixel(BufferedImage img, int x, int y, Color c, String what) {
        assertTrue(near(img.getRGB(x, y), argb(c)), what + " at (" + x + "," + y + "): expected "
                + Integer.toHexString(argb(c)) + " but was " + Integer.toHexString(img.getRGB(x, y)));
    }

    private static boolean isColor(BufferedImage img, int x, int y, Color c) {
        return near(img.getRGB(x, y), argb(c));
    }

    // ------------------------------------------------------------------ (a) track selection

    @Test
    void aHeaderClicksSelectTracksAndEditsTargetThemOrAll() throws Exception {
        try (Fx f = open(whole("m0", src(1, 1, 4000, RATE)), whole("m1", src(2, 1, 3000, RATE)),
                whole("s2", src(3, 2, 2000, RATE)))) {
            headerClick(f, 1, false, false);
            assertEquals(List.of(false, true, false), selectedFlags(f));
            BufferedImage h1 = snap(fx(() -> f.pane.rows().get(1).header()), "a-header-selected");
            assertPixel(h1, 115, h1.getHeight() - 3, Theme.TRACK_SELECTED_BACKGROUND, "selected header");
            BufferedImage h0 = snap(fx(() -> f.pane.rows().get(0).header()), null);
            assertPixel(h0, 115, h0.getHeight() - 3, Theme.TRACK_HEADER_BACKGROUND, "normal header");
            headerClick(f, 2, true, false); // toggle t2 on, anchor t2
            assertEquals(List.of(false, true, true), selectedFlags(f));
            headerClick(f, 0, false, true); // range from the anchor t2 down to t0
            assertEquals(List.of(true, true, true), selectedFlags(f));
            headerClick(f, 0, false, false); // plain: only t0, anchor t0
            headerClick(f, 1, true, true); // ctrl+shift: add the range t0..t1
            assertEquals(List.of(true, true, false), selectedFlags(f));

            headerClick(f, 1, false, false); // only m1
            select(f, new Selection(100, 300));
            key(f, KeyCode.DELETE, false, false);
            assertEquals(4000, clips(f, 0).get(0).frameCount(), "m0 untouched");
            assertEquals(2800, render(clips(f, 1)).length, "m1 lost 200 frames");
            assertEquals(2000, clips(f, 2).get(0).frameCount(), "s2 untouched");
            headerClick(f, 1, true, false); // toggle off: nothing selected -> all tracks
            assertEquals(List.of(false, false, false), selectedFlags(f));
            select(f, new Selection(0, 100));
            key(f, KeyCode.DELETE, false, false);
            assertEquals(3900, render(clips(f, 0)).length);
            assertEquals(2700, render(clips(f, 1)).length);
            assertEquals(1900, render(clips(f, 2)).length);
            assertTrue(fx(() -> f.pane.rows().get(0).header().getStyleClass().contains("selected")) == false);
        }
    }

    private static List<Boolean> selectedFlags(Fx f) throws Exception {
        return fx(() -> {
            List<Boolean> out = new ArrayList<>();
            for (TrackRowView r : f.pane.rows()) {
                out.add(r.header().getStyleClass().contains("selected")
                        && f.model.trackSelection().isSelected(r.track()));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ (b) cut, undo, redo

    @Test
    void bCutUndoRedoRestoreAudioPlanAndSelection() throws Exception {
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)))) {
            select(f, new Selection(1000, 1500));
            assertTrue(disabled(f, edit(f, 5)), "Paste disabled with an empty clipboard");
            key(f, KeyCode.X, true, false);
            assertEquals(3500, frames(f));
            assertEquals(Selection.cursor(1000), selection(f));
            assertEquals("Cursor: 0:00:00.125", fx(() -> f.pane.statusBar().cursorLabel().getText()));
            assertEquals("Selection: 0:00:00.125 - 0:00:00.125 (0:00:00.000)",
                    fx(() -> f.pane.statusBar().selectionLabel().getText()));
            assertEquals(3500, fx(() -> f.actions.plan().length()), "the plan was rebuilt");
            assertEquals(3500, fx(() -> f.actions.plan().sources().get(0).frameCount()));
            assertFalse(disabled(f, edit(f, 5)), "Paste enabled after a cut");
            assertEquals("Undo Cut", fx(() -> edit(f, 0).getText()));
            assertArrayEquals(concat(range(1, 0, 1000), range(1, 1500, 4000)), render(clips(f, 0)));
            snap(f.pane, "b-after-cut");

            key(f, KeyCode.Z, true, false);
            assertArrayEquals(range(1, 0, 4000), render(clips(f, 0)), "undo restores the audio");
            assertEquals(new Selection(1000, 1500), selection(f), "and the time selection from before");
            assertEquals(4000, fx(() -> f.actions.plan().length()));
            assertEquals("Redo Cut", fx(() -> edit(f, 1).getText()));
            assertEquals("Undo", fx(() -> edit(f, 0).getText()));
            assertTrue(disabled(f, edit(f, 0)));

            key(f, KeyCode.Y, true, false);
            assertEquals(Selection.cursor(1000), selection(f), "redo gives the outcome selection again");
            assertEquals(3500, frames(f));
            key(f, KeyCode.Z, true, false);
            key(f, KeyCode.Z, true, true); // Ctrl+Shift+Z is redo too
            assertEquals(3500, frames(f));
            assertEquals(Selection.cursor(1000), selection(f));
        }
    }

    // ------------------------------------------------------------------ (c) copy and paste

    @Test
    void cCopyThenPasteAtTheCursorWithBorders() throws Exception {
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)))) {
            select(f, new Selection(200, 600));
            key(f, KeyCode.C, true, false);
            assertEquals(1, fx(() -> f.model.clipboard().get().size()));
            assertEquals("Undo", fx(() -> edit(f, 0).getText()), "copy records nothing");
            select(f, Selection.cursor(3000));
            key(f, KeyCode.V, true, false);
            assertEquals(new Selection(3000, 3400), selection(f));
            assertEquals(4400, frames(f));
            assertArrayEquals(concat(range(1, 0, 3000), range(1, 200, 600), range(1, 3000, 4000)),
                    render(clips(f, 0)));
            // clips [0,3000) [3000,3400) [3400,4400) at 10 fpp: 0, 299 | 300, 339 | 340, 439
            int[] expected = {0, 299, 300, 339, 340, 439};
            assertArrayEquals(expected, fx(() -> ClipBoundaries.columns(f.model.project().tracks().get(0).clips(),
                    f.model.axis(), f.model.laneWidth())));
            BufferedImage img = lane(f, 0, 0, "c-after-paste");
            for (int x : expected) {
                assertPixel(img, x, 10, Theme.CLIP_BORDER, "border");
                assertPixel(img, x, 80, Theme.CLIP_BORDER, "border");
            }
            for (int x : new int[] {1, 298, 341, 438}) {
                assertFalse(isColor(img, x, 10, Theme.CLIP_BORDER), "no border at " + x);
            }
            key(f, KeyCode.Z, true, false);
            assertEquals(4000, frames(f));
            assertEquals(Selection.cursor(3000), selection(f));
        }
    }

    // ------------------------------------------------------------------ (d) delete, silence, trim, split

    @Test
    void dDeleteBackspaceSilenceTrimSplit() throws Exception {
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)))) {
            select(f, new Selection(100, 200));
            key(f, KeyCode.DELETE, false, false);
            assertEquals(3900, frames(f));
            key(f, KeyCode.Z, true, false);
            assertEquals(new Selection(100, 200), selection(f));
            key(f, KeyCode.BACK_SPACE, false, false);
            assertEquals(3900, frames(f), "Backspace deletes too");
            assertArrayEquals(concat(range(1, 0, 100), range(1, 200, 4000)), render(clips(f, 0)));
            key(f, KeyCode.Z, true, false);

            select(f, new Selection(1000, 2000));
            key(f, KeyCode.L, true, false);
            assertEquals(new Selection(1000, 2000), selection(f));
            assertArrayEquals(concat(range(1, 0, 1000), new float[1000], range(1, 2000, 4000)), render(clips(f, 0)));
            BufferedImage img = lane(f, 0, 0, "d-silence");
            assertPixel(img, 99, 10, Theme.CLIP_BORDER, "end of [0,1000)");
            assertPixel(img, 200, 10, Theme.CLIP_BORDER, "start of [2000,4000)");
            for (int x = 100; x < 200; x++) {
                assertPixel(img, x, 45, Theme.CENTER_LINE, "silence: centre line");
                assertPixel(img, x, 10, Theme.LANE_BACKGROUND, "silence: no band");
            }
            key(f, KeyCode.Z, true, false);

            select(f, new Selection(1000, 1500));
            key(f, KeyCode.T, true, false);
            assertEquals(500, frames(f));
            assertEquals(new Selection(0, 500), selection(f));
            assertArrayEquals(range(1, 1000, 1500), render(clips(f, 0)));
            key(f, KeyCode.Z, true, false);
            assertEquals(4000, frames(f));

            select(f, Selection.cursor(2500));
            key(f, KeyCode.I, true, false);
            assertEquals(2, clips(f, 0).size());
            assertArrayEquals(range(1, 0, 4000), render(clips(f, 0)), "split changes no audio");
            BufferedImage split = lane(f, 0, 0, "d-split");
            assertPixel(split, 249, 10, Theme.CLIP_BORDER, "end of [0,2500)");
            assertPixel(split, 250, 10, Theme.CLIP_BORDER, "start of [2500,4000)");
            key(f, KeyCode.Z, true, false);
            assertEquals(1, clips(f, 0).size());
        }
    }

    // ------------------------------------------------------------------ (e) locking

    @Test
    void eEditsAreLockedWhilePlayingOrPausedButCopyAndHeaderSelectionWork() throws Exception {
        try (Fx f = open(whole("m0", src(1, 1, 4000, RATE)), whole("m1", src(2, 1, 4000, RATE)))) {
            select(f, new Selection(300, 400));
            key(f, KeyCode.DELETE, false, false); // one edit so that undo has something to do
            select(f, new Selection(500, 1500));
            List<Clip> c0 = clips(f, 0);
            List<Clip> c1 = clips(f, 1);
            play(f);
            for (PlaybackEngine.State st : new PlaybackEngine.State[] {PlaybackEngine.State.PLAYING,
                PlaybackEngine.State.PAUSED}) {
                if (st == PlaybackEngine.State.PAUSED) {
                    key(f, KeyCode.P, false, false);
                    awaitState(f, PlaybackEngine.State.PAUSED);
                }
                key(f, KeyCode.X, true, false);
                key(f, KeyCode.V, true, false);
                key(f, KeyCode.DELETE, false, false);
                key(f, KeyCode.BACK_SPACE, false, false);
                key(f, KeyCode.L, true, false);
                key(f, KeyCode.T, true, false);
                key(f, KeyCode.I, true, false);
                key(f, KeyCode.Z, true, false);
                key(f, KeyCode.Y, true, false);
                key(f, KeyCode.Z, true, true);
                for (int i : new int[] {0, 1, 3, 5, 6, 7, 8, 9}) {
                    MenuItem m = edit(f, i);
                    assertTrue(disabled(f, m), st + ": " + m.getText() + " disabled");
                    fire(f, m); // even a forced fire does nothing
                }
                headerClick(f, 0, false, false);
                fire(f, menu(f, "Track").getItems().get(0));
                assertEquals(c0, clips(f, 0), st + ": track 0 unchanged");
                assertEquals(c1, clips(f, 1), st + ": track 1 unchanged");
                assertEquals(List.of("m0", "m1"), names(f));
                assertEquals("Undo Delete", fx(() -> f.model.history().undoName().map(n -> "Undo " + n).orElse("")));
                assertEquals(new Selection(500, 1500), selection(f));
                assertFalse(disabled(f, edit(f, 4)), st + ": Copy stays enabled");
                key(f, KeyCode.C, true, false);
                assertEquals(1, fx(() -> f.model.clipboard().get().size()), st + ": copy works (one target)");
                assertTrue(fx(() -> f.model.trackSelection().isSelected(f.model.project().tracks().get(0))),
                        st + ": header selection works");
                run(f, () -> f.model.clipboard().clear());
                headerClick(f, 0, true, false); // deselect again
            }
            key(f, KeyCode.SPACE, false, false);
            awaitState(f, PlaybackEngine.State.STOPPED);
            key(f, KeyCode.X, true, false);
            assertEquals(Selection.cursor(500), selection(f), "after Stop the cut works");
            assertEquals(2900, render(clips(f, 0)).length);
        }
    }

    // ------------------------------------------------------------------ (f) enabling table

    @Test
    void fTheEnablingTable() throws Exception {
        try (Fx f = open()) {
            MenuItem open = menu(f, "File").getItems().get(0);
            MenuItem remove = menu(f, "Track").getItems().get(0);
            // no tracks
            assertEquals(List.of(true, true, true, true, true, true, true, true, true), states(f));
            assertTrue(disabled(f, remove));
            assertFalse(disabled(f, open));
            run(f, () -> f.pane.addTrack(whole("m", src(1, 1, 4000, RATE))));
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 10.0, 0)));
            // tracks, cursor only: Undo, Redo, Cut, Copy, Paste, Delete, Silence, Trim disabled; Split enabled
            select(f, Selection.cursor(100));
            assertEquals(List.of(true, true, true, true, true, true, true, true, false), states(f));
            assertTrue(disabled(f, remove), "no explicit track selection");
            key(f, KeyCode.BACK_SPACE, false, false);
            key(f, KeyCode.DELETE, false, false);
            assertEquals(4000, frames(f), "Backspace and Delete do nothing while disabled");
            // a selection
            select(f, new Selection(100, 200));
            assertEquals(List.of(true, true, false, false, true, false, false, false, false), states(f));
            key(f, KeyCode.C, true, false);
            assertEquals(List.of(true, true, false, false, false, false, false, false, false), states(f),
                    "Paste enabled with a filled clipboard");
            headerClick(f, 0, false, false);
            assertFalse(disabled(f, remove));
            key(f, KeyCode.DELETE, false, false);
            assertEquals(List.of(false, true, true, true, false, true, true, true, false), states(f),
                    "after the delete the selection is a cursor (Copy disabled too); Undo enabled");
            select(f, new Selection(100, 200));
            play(f);
            assertEquals(List.of(true, true, true, false, true, true, true, true, true), states(f),
                    "playing: only Copy");
            assertTrue(disabled(f, remove));
            assertTrue(disabled(f, open));
            key(f, KeyCode.SPACE, false, false);
            awaitState(f, PlaybackEngine.State.STOPPED);
            assertFalse(disabled(f, open));
        }
    }

    /** Disabled flags of Undo, Redo, Cut, Copy, Paste, Delete, Silence, Trim, Split. */
    private static List<Boolean> states(Fx f) throws Exception {
        List<Boolean> out = new ArrayList<>();
        for (int i : new int[] {0, 1, 3, 4, 5, 6, 7, 8, 9}) {
            out.add(disabled(f, edit(f, i)));
        }
        return out;
    }

    // ------------------------------------------------------------------ (g) import

    @Test
    void gImportIsUndoableAndRedoRestoresTheSameTrack() throws Exception {
        Path wav = tmp.resolve("beep_mono.wav");
        WavWriter.write(wav, src(7, 1, 3000, RATE), WavEncoding.FLOAT_32);
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)))) {
            assertTrue(fx(() -> f.actions.importFile(wav)));
            assertEquals(List.of("m", "beep_mono"), names(f));
            assertEquals("Undo Import", fx(() -> edit(f, 0).getText()));
            Track imported = track(f, 1);
            SampleSource s = imported.clips().get(0).source();
            CountDownLatch ready = new CountDownLatch(1);
            run(f, () -> f.model.cache().request(s, ready::countDown));
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            PeakPyramid built = fx(() -> f.model.cache().getIfReady(s).orElseThrow());
            assertEquals(2, fx(() -> f.pane.rows().size()));
            key(f, KeyCode.Z, true, false);
            assertEquals(List.of("m"), names(f));
            assertEquals(1, fx(() -> f.pane.rows().size()));
            key(f, KeyCode.Y, true, false);
            assertSame(imported, track(f, 1), "redo restores the same Track object");
            assertSame(built, fx(() -> f.model.cache().getIfReady(s).orElseThrow()), "the pyramid was not rebuilt");
            assertEquals(2, fx(() -> f.pane.rows().size()));
            assertArrayEquals(range(7, 0, 3000), render(track(f, 1).clips()));
            select(f, new Selection(0, 100));
            play(f);
            assertFalse(fx(() -> f.actions.importFile(wav)), "import is locked while playing");
            assertTrue(disabled(f, menu(f, "File").getItems().get(0)));
            assertEquals(2, names(f).size());
            key(f, KeyCode.SPACE, false, false);
            awaitState(f, PlaybackEngine.State.STOPPED);
        }
    }

    // ------------------------------------------------------------------ (h) remove track

    @Test
    void hRemoveSelectedTracksAndImportAnotherRateIntoAnEmptyProject() throws Exception {
        Path wav48 = tmp.resolve("other_rate.wav");
        WavWriter.write(wav48, src(9, 2, 4800, 48000), WavEncoding.FLOAT_32);
        Track t0 = whole("t0", src(1, 1, 400, RATE));
        Track t1 = whole("t1", src(2, 1, 500, RATE));
        Track t2 = whole("t2", src(3, 2, 600, RATE));
        try (Fx f = open(t0, t1, t2)) {
            MenuItem remove = menu(f, "Track").getItems().get(0);
            assertTrue(disabled(f, remove), "no explicit selection");
            headerClick(f, 0, false, false);
            headerClick(f, 2, true, false);
            fire(f, remove);
            assertEquals(List.of("t1"), names(f));
            assertEquals("Undo Remove Track", fx(() -> edit(f, 0).getText()));
            key(f, KeyCode.Z, true, false);
            assertEquals(List.of("t0", "t1", "t2"), names(f));
            assertSame(t0, track(f, 0));
            assertSame(t2, track(f, 2));
            assertEquals(3, fx(() -> f.pane.rows().size()));
            assertTrue(disabled(f, remove), "removed tracks dropped out of the selection; undo restores none");

            headerClick(f, 0, false, false);
            headerClick(f, 2, false, true);
            fire(f, remove);
            assertEquals(List.of(), names(f));
            assertTrue(fx(() -> f.actions.importFile(wav48)), "an empty project accepts another sample rate");
            assertEquals(48000, fx(() -> f.model.project().sampleRate().getAsInt()));
            assertEquals(48000, fx(() -> f.model.axis().sampleRate()));
            key(f, KeyCode.Z, true, false);
            key(f, KeyCode.Z, true, false);
            assertEquals(List.of("t0", "t1", "t2"), names(f));
            assertEquals(RATE, fx(() -> f.model.project().sampleRate().getAsInt()));
            assertEquals(RATE, fx(() -> f.model.axis().sampleRate()));
        }
    }

    // ------------------------------------------------------------------ (i) clear tracks

    @Test
    void iClearTracksWipesEverythingAndIsNotUndoable() throws Exception {
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)), whole("n", src(2, 1, 4000, RATE)))) {
            select(f, new Selection(100, 200));
            key(f, KeyCode.X, true, false);
            headerClick(f, 1, false, false);
            select(f, new Selection(300, 900));
            play(f);
            assertEquals("Timbre *", fx(() -> f.pane.titleProperty().get()));
            fire(f, menu(f, "File").getItems().get(1));
            awaitState(f, PlaybackEngine.State.STOPPED);
            assertEquals(List.of(), names(f));
            assertFalse(fx(() -> f.model.history().canUndo()));
            assertTrue(disabled(f, edit(f, 0)));
            assertTrue(fx(() -> f.model.clipboard().isEmpty()));
            assertEquals(0, fx(() -> f.model.trail().undoDepth()));
            assertTrue(fx(() -> f.model.trackSelection().isEmpty()));
            assertEquals("Timbre", fx(() -> f.pane.titleProperty().get()));
            assertEquals(Selection.cursor(0), selection(f));
            assertEquals(TimeAxis.fit(EditorModel.EMPTY_PROJECT_SAMPLE_RATE, 0, f.lane), fx(() -> f.model.axis()));
            assertEquals(0, fx(() -> f.pane.rows().size()));
        }
    }

    // ------------------------------------------------------------------ (j) modified marker

    @Test
    void jTheModifiedMarker() throws Exception {
        try (Fx f = open(whole("m", src(1, 1, 4000, RATE)))) {
            assertEquals("Timbre", fx(() -> f.pane.titleProperty().get()));
            assertFalse(fx(() -> f.model.modifiedProperty().get()));
            select(f, new Selection(100, 200));
            key(f, KeyCode.X, true, false);
            assertTrue(fx(() -> f.model.modifiedProperty().get()));
            assertEquals("Timbre *", fx(() -> f.pane.titleProperty().get()));
            key(f, KeyCode.Z, true, false);
            assertEquals("Timbre", fx(() -> f.pane.titleProperty().get()), "back at the save point");
            key(f, KeyCode.Y, true, false);
            assertEquals("Timbre *", fx(() -> f.pane.titleProperty().get()));
            fire(f, menu(f, "File").getItems().get(1));
            assertEquals("Timbre", fx(() -> f.pane.titleProperty().get()), "after Clear tracks");
        }
    }

    // ------------------------------------------------------------------ (k) the after-edit pipeline

    @Test
    void kThePipelineReclampsScrollsAndRepaints() throws Exception {
        float[] data = new float[4000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (float) (0.6 * Math.sin(i * 0.013) + 0.25 * Math.cos(i * 0.071));
        }
        MemorySampleSource wave = MemorySampleSource.copyOf(RATE, new float[][] {data});
        try (Fx f = open(whole("w", wave))) {
            long w = f.lane;
            // shrinking re-clamps: 1 frame per pixel, scroll 2990, cut [3000, 4000): new limit 3000 - W / 10
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 1.0, 2990)));
            select(f, new Selection(3000, 4000));
            key(f, KeyCode.X, true, false);
            assertEquals(3000 - w / 10, fx(() -> f.model.axis().scrollFrame()), "re-clamped to the new limit");
            assertEquals(Selection.cursor(3000), selection(f));
            assertBarMatches(f);
            key(f, KeyCode.Z, true, false);
            // a paste whose start is right of the view scrolls it in by the minimum amount
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 1.0, 0)));
            select(f, new Selection(0, 50));
            key(f, KeyCode.C, true, false);
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 1.0, 0)));
            select(f, Selection.cursor(3000)); // x = 3000 > W: outside
            key(f, KeyCode.V, true, false);
            assertEquals(3000 - (w - 1), fx(() -> f.model.axis().scrollFrame()), "the start lands on the last column");
            assertBarMatches(f);
            key(f, KeyCode.Z, true, false);

            // waveform after a cut at 10 fpp equals the brute-force bands of the oracle samples
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 10.0, 0)));
            select(f, new Selection(1000, 1500));
            key(f, KeyCode.X, true, false);
            run(f, () -> f.model.setAxis(new TimeAxis(RATE, 10.0, 0)));
            float[] oracle = new float[3500];
            System.arraycopy(data, 0, oracle, 0, 1000);
            System.arraycopy(data, 1500, oracle, 1000, 2500);
            BufferedImage img = lane(f, 0, 0, "k-after-cut");
            for (int x = 0; x < 350; x++) {
                if (x == 0 || x == 99 || x == 100 || x == 349) { // borders of [0,1000) and [1000,3500)
                    assertPixel(img, x, 20, Theme.CLIP_BORDER, "border");
                    continue;
                }
                float lo = Float.POSITIVE_INFINITY;
                float hi = Float.NEGATIVE_INFINITY;
                double sq = 0;
                for (int i = 10 * x; i < 10 * x + 10; i++) {
                    lo = Math.min(lo, oracle[i]);
                    hi = Math.max(hi, oracle[i]);
                    sq += (double) oracle[i] * oracle[i];
                }
                float rms = (float) Math.sqrt(sq / 10);
                int pt = row(hi);
                int pb = row(lo);
                int rt = Math.max(pt, row(rms));
                int rb = Math.min(pb, row(-rms));
                for (int r = 0; r < 90; r++) {
                    Color c = r >= rt && r <= rb ? Theme.RMS : r >= pt && r <= pb ? Theme.PEAK
                            : r == 45 ? Theme.CENTER_LINE : Theme.LANE_BACKGROUND;
                    assertPixel(img, x, r, c, "column " + x + " row " + r);
                }
            }
        }
    }

    /** The row of an amplitude, written out independently: round(45 - 44 v) clamped to [1, 89]. */
    private static int row(double v) {
        double r = 45.0 - 44.0 * v;
        return r <= 1 ? 1 : r >= 89 ? 89 : (int) Math.round(r);
    }

    private static void assertBarMatches(Fx f) throws Exception {
        ScrollbarMapping m = fx(() -> ScrollbarMapping.of(f.model.axis(), f.model.limits()));
        assertEquals(m.max(), fx(() -> f.pane.scrollBar().getMax()), 0.0);
        assertEquals(m.visibleAmount(), fx(() -> f.pane.scrollBar().getVisibleAmount()), 0.0);
        assertEquals(m.value(), fx(() -> f.pane.scrollBar().getValue()), 0.0);
    }

    // ------------------------------------------------------------------ (l) refusal message

    @Test
    void lARefusedPasteShowsItsReason() throws Exception {
        try (Fx f = open(whole("mono", src(1, 1, 4000, RATE)), whole("stereo", src(3, 2, 4000, RATE)))) {
            headerClick(f, 1, false, false);
            select(f, new Selection(0, 100));
            key(f, KeyCode.C, true, false); // a stereo fragment
            headerClick(f, 0, false, false);
            select(f, Selection.cursor(50));
            List<Clip> before = clips(f, 0);
            key(f, KeyCode.V, true, false);
            String msg = fx(() -> f.pane.statusBar().messageLabel().getText());
            assertTrue(msg.contains("channels"), "the Editor's reason is shown: " + msg);
            assertEquals(fx(() -> f.model.editor().lastRefusal().orElse("")), msg);
            assertEquals(before, clips(f, 0));
            assertEquals("Undo", fx(() -> edit(f, 0).getText()), "no history entry");
            snap(f.pane, "l-refusal");
            run(f, () -> { });
            assertTrue(fx(() -> f.pane.statusBar().cursorLabel().getWidth()
                    >= f.pane.statusBar().cursorLabel().prefWidth(-1) - 0.5),
                    "a long message never squeezes the cursor readout");
            assertTrue(fx(() -> f.pane.statusBar().selectionLabel().getWidth()
                    >= f.pane.statusBar().selectionLabel().prefWidth(-1) - 0.5));
            select(f, new Selection(0, 100));
            key(f, KeyCode.C, true, false);
            assertEquals("", fx(() -> f.pane.statusBar().messageLabel().getText()), "the next action clears it");
        }
    }

    // ------------------------------------------------------------------ (m) clip borders on real lanes

    @Test
    void mClipBordersOnTheRealLanes() throws Exception {
        MemorySampleSource a = src(5, 1, 2000, RATE);
        Track t = new Track("borders", RATE, 1);
        t.addClip(new Clip(a, 0, 500, 0)); // [0, 500)
        t.addClip(new Clip(a, 500, 500, 800)); // [800, 1300): a gap before it
        t.addClip(new Clip(a, 1000, 600, 1300)); // [1300, 1900): adjacent to the previous clip
        try (Fx f = open(t)) {
            int[] expected = {0, 49, 80, 129, 130, 189};
            assertArrayEquals(expected, fx(() -> ClipBoundaries.columns(t.clips(), f.model.axis(), f.lane)));
            BufferedImage img = lane(f, 0, 0, "m-borders");
            for (int x : expected) {
                for (int r = 0; r < 90; r += 11) {
                    assertPixel(img, x, r, Theme.CLIP_BORDER, "border row " + r);
                }
                assertPixel(img, x, 90, Theme.SEPARATOR, "the separator row stays");
            }
            for (int x : new int[] {1, 48, 50, 79, 81, 128, 131, 188, 190}) {
                for (int r = 0; r < 90; r += 11) {
                    assertFalse(isColor(img, x, r, Theme.CLIP_BORDER), "no border at " + x + "," + r);
                }
            }
        }
    }
}
