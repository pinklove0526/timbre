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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.FakeEngineRig;
import com.jellyfish.timbre.audio.PlaybackEngine;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.ScrollbarMapping;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.MenuItem;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in interaction test ({@code -Dtimbre.fx=true}): drives an {@link EditorPane} in an unshown 1000 x 400
 * Scene with synthetic mouse, scroll and key events. The engine plays through the fake sink in manual-advance
 * mode (nothing is ever played), the animation timer is stopped and every pulse is explicit, so nothing here
 * sleeps on the FX thread or depends on frame timing. Expected values are hand-computed: the project is 10 s
 * at 44100 Hz (441000 frames), the axis is fixed at 100 frames per pixel from scroll 0, so lane-area column
 * {@code x} is frame {@code 100 x}; {@code W} is the lane width the layout produced (read at run time and
 * only used in formulas such as {@code visibleFrames / 10 = 10 W}). PNGs go to {@code build-logs/m3b2/png}.
 */
@EnabledIfSystemProperty(named = "timbre.fx", matches = "true")
class EditorInteractionFxTest {

    private static final int RATE = 44100;
    private static final int TEN_SECONDS = 441_000;
    private static final Path PNG_DIR = Path.of(System.getProperty("timbre.fx.png.b2", "build-logs/m3b2/png"));
    private static final int TOL = 3;

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

    // ------------------------------------------------------------------ fixture and helpers

    /** One pane on a fake-sink engine. */
    private static final class Fx implements AutoCloseable {
        final FakeEngineRig rig = new FakeEngineRig();
        EditorPane pane;
        Scene scene;
        EditorModel model;
        int lane; // W
        final AtomicInteger axisChanges = new AtomicInteger();
        final AtomicInteger ancestorWheels = new AtomicInteger();

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

    private static void drain() throws Exception {
        fx(() -> null);
    }

    /** Builds the pane with {@code tracks} tracks of {@code frames} frames and {@code channels} channels. */
    private static Fx open(int tracks, int channels, int frames) throws Exception {
        Fx f = new Fx();
        fx(() -> {
            f.pane = new EditorPane(f.rig.engine());
            f.pane.stopAnimationTimer();
            f.scene = new Scene(f.pane, 1000, 400);
            f.model = f.pane.model();
            f.pane.applyCss();
            f.pane.layout();
            float[][] data = new float[channels][frames];
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < frames; i++) {
                    data[c][i] = (float) ((c == 0 ? 0.8 : 0.3) * Math.sin(i * (0.01 + 0.007 * c)));
                }
            }
            MemorySampleSource src = MemorySampleSource.copyOf(RATE, data);
            for (int t = 0; t < tracks; t++) {
                f.pane.addTrack(Track.of("track" + t, src));
            }
            f.pane.applyCss();
            f.pane.layout();
            f.lane = f.model.laneWidth();
            if (tracks > 0) {
                f.model.setAxis(new TimeAxis(RATE, 100.0, 0));
            }
            f.pane.layout();
            f.pane.repaintNow();
            f.model.axisProperty().addListener((o, a, b) -> f.axisChanges.incrementAndGet());
            f.pane.addEventHandler(ScrollEvent.SCROLL, e -> f.ancestorWheels.incrementAndGet());
            return null;
        });
        return f;
    }

    private static BufferedImage toBuffered(WritableImage img, String name) throws IOException {
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
    }

    private static BufferedImage snapOverlay(Fx f, String name) throws Exception {
        return fx(() -> {
            f.pane.repaintNow();
            SnapshotParameters p = new SnapshotParameters();
            p.setFill(Color.TRANSPARENT);
            return toBuffered(f.pane.overlay().snapshot(p, null), name);
        });
    }

    private static BufferedImage snapScene(Fx f, String name) throws Exception {
        return fx(() -> {
            f.pane.layout();
            f.pane.repaintNow();
            return toBuffered(f.scene.snapshot(null), name);
        });
    }

    private static int argb(Color c) {
        return (0xFF << 24) | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }

    private static boolean near(int actual, int expected) {
        for (int shift = 0; shift <= 24; shift += 8) {
            if (Math.abs(((actual >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) > TOL) {
                return false;
            }
        }
        return true;
    }

    private static void assertLine(BufferedImage img, int x, int y, String what) {
        assertTrue(near(img.getRGB(x, y), argb(Theme.PLAYHEAD)), what + " at (" + x + "," + y + ") was "
                + Integer.toHexString(img.getRGB(x, y)));
    }

    private static void assertClear(BufferedImage img, int x, int y, String what) {
        assertEquals(0, img.getRGB(x, y) >>> 24, what + " at (" + x + "," + y + ") should be transparent");
    }

    // synthetic events -----------------------------------------------------------------------

    private static void mouse(Fx f, javafx.event.EventType<MouseEvent> type, double x, double y, boolean shift)
            throws Exception {
        fx(() -> {
            Node target = f.pane.inputPane();
            Event.fireEvent(target, new MouseEvent(target, target, type, x, y, x, y, MouseButton.PRIMARY, 1,
                    shift, false, false, false, type != MouseEvent.MOUSE_RELEASED, false, false, false, false,
                    false, null));
            return null;
        });
    }

    private static void press(Fx f, double x, double y, boolean shift) throws Exception {
        mouse(f, MouseEvent.MOUSE_PRESSED, x, y, shift);
    }

    private static void drag(Fx f, double x) throws Exception {
        mouse(f, MouseEvent.MOUSE_DRAGGED, x, 40, false);
    }

    private static void release(Fx f, double x) throws Exception {
        mouse(f, MouseEvent.MOUSE_RELEASED, x, 40, false);
    }

    private static void click(Fx f, double x, double y) throws Exception {
        press(f, x, y, false);
        release(f, x);
    }

    private static void wheel(Fx f, double x, double dx, double dy, boolean ctrl, boolean shift) throws Exception {
        fx(() -> {
            Node target = f.pane.inputPane();
            Event.fireEvent(target, new ScrollEvent(target, target, ScrollEvent.SCROLL, x, 40, x, 40, shift, ctrl,
                    false, false, false, false, dx, dy, dx, dy, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                    ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
            return null;
        });
    }

    private static void key(Fx f, KeyCode code, boolean ctrl) throws Exception {
        keyOn(f, f.scene, code, ctrl);
    }

    private static void keyOn(Fx f, javafx.event.EventTarget target, KeyCode code, boolean ctrl) throws Exception {
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(target, target, KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "",
                    code, false, ctrl, false, false));
            return null;
        });
        drain(); // like a real key: the release comes later, after the buttons were refreshed
        fx(() -> {
            Event.fireEvent(target, new KeyEvent(target, target, KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "",
                    code, false, ctrl, false, false));
            return null;
        });
        drain();
    }

    private static Selection selection(Fx f) throws Exception {
        return fx(() -> f.model.selection());
    }

    private static TimeAxis axis(Fx f) throws Exception {
        return fx(() -> f.model.axis());
    }

    private static void setSelection(Fx f, Selection s) throws Exception {
        fx(() -> {
            f.model.setSelection(s);
            return null;
        });
    }

    private static void setAxis(Fx f, double fpp, long scroll) throws Exception {
        fx(() -> {
            f.model.setAxis(new TimeAxis(RATE, fpp, scroll));
            return null;
        });
    }

    private static PlaybackEngine.State state(Fx f) {
        return f.rig.engine().state();
    }

    /** Presses Space (through the scene filter) and waits until the engine reports the expected state. */
    private static void space(Fx f) throws Exception {
        key(f, KeyCode.SPACE, false);
    }

    private static void awaitState(Fx f, PlaybackEngine.State expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (state(f) != expected) {
            assertTrue(System.nanoTime() < deadline, "engine state stayed " + state(f) + ", wanted " + expected);
            Thread.sleep(1);
        }
        drain();
    }

    // ------------------------------------------------------------------ (a) click

    @Test
    void aClickSetsTheCursorAndTheOverlayLineStopsAtTheLastTrack() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            double vh = fx(() -> f.pane.scrollPane().getViewportBounds().getHeight());
            assertTrue(vh > 200, "viewport too short for the test: " + vh);
            click(f, 347.0, 40);
            // column 347 = frame 34700; 34700 / 44100 = 0.7868 s
            assertEquals(Selection.cursor(34_700), selection(f));
            fx(() -> {
                f.pane.repaintNow();
                return null;
            });
            assertEquals("Cursor: 0:00:00.786", fx(() -> f.pane.statusBar().cursorLabel().getText()));
            BufferedImage img = snapOverlay(f, "a-click-overlay");
            for (int y = 0; y < 182; y++) { // one stereo track = 182 rows
                assertLine(img, 347, y, "cursor line");
            }
            for (int y = 182; y < img.getHeight(); y += 7) {
                assertClear(img, 347, y, "below the last track");
            }
            assertClear(img, 346, 50, "left of the line");
            assertClear(img, 348, 50, "right of the line");
            // a press below the last track row (y = 200 >= 182) does nothing
            click(f, 100, 200);
            assertEquals(Selection.cursor(34_700), selection(f));
        }
    }

    // ------------------------------------------------------------------ (b) drag, extend, clamp

    @Test
    void bDragBackwardDragShiftExtendClampAndTheThreePixelRule() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            press(f, 200, 40, false);
            drag(f, 500);
            release(f, 500);
            assertEquals(new Selection(20_000, 50_000), selection(f));
            BufferedImage img = snapOverlay(f, "b1-selection");
            assertLine(img, 200, 100, "cursor line at the selection start");
            for (int x = 201; x < 500; x += 13) {
                int a = img.getRGB(x, 100) >>> 24;
                assertTrue(Math.abs(a - 77) <= 2, "fill alpha at " + x + ": " + a);
            }
            assertClear(img, 199, 100, "left of the selection");
            assertClear(img, 500, 100, "right of the selection");
            assertClear(img, 300, 190, "no fill below the last track");

            press(f, 600, 40, false);
            drag(f, 250);
            release(f, 250);
            assertEquals(new Selection(25_000, 60_000), selection(f), "backward drag is ordered");

            press(f, 700, 40, true); // extends from the anchor 60000
            release(f, 700);
            assertEquals(new Selection(60_000, 70_000), selection(f), "shift-press extends from the anchor");

            press(f, 300, 40, false);
            drag(f, -50);
            release(f, -50);
            assertEquals(new Selection(0, 30_000), selection(f), "clamped at 0");

            press(f, 300, 40, false);
            drag(f, 5000);
            release(f, 5000);
            assertEquals(new Selection(30_000, TEN_SECONDS), selection(f), "clamped at the project end");

            press(f, 400, 40, false);
            drag(f, 402.9);
            release(f, 402.9);
            assertEquals(Selection.cursor(40_000), selection(f), "under 3 px is a click");
            press(f, 400, 40, false);
            drag(f, 403.0);
            release(f, 403.0);
            assertEquals(new Selection(40_000, 40_300), selection(f), "3 px is a drag");
            snapScene(f, "b2-pane-selection");
        }
    }

    // ------------------------------------------------------------------ (c) mouse lock

    @Test
    void cTheMouseIsLockedWhilePlayingOrPausedButZoomAndScrollWork() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            setSelection(f, Selection.cursor(5_000));
            space(f);
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            press(f, 500, 40, false);
            drag(f, 700);
            release(f, 700);
            assertEquals(Selection.cursor(5_000), selection(f), "no selection change while PLAYING");
            // zoom and scroll stay active: read inside one FX task so no follow step can interfere
            wheel(f, 400.0, 0, 40, true, false);
            assertEquals(50.0, axis(f).framesPerPixel(), 0.0);
            setAxis(f, 100.0, 50_000);
            wheel(f, 400.0, 0, 40, false, true);
            assertEquals(50_000 - 10L * f.lane, axis(f).scrollFrame());
            key(f, KeyCode.P, false);
            assertEquals(PlaybackEngine.State.PAUSED, state(f));
            press(f, 500, 40, false);
            drag(f, 700);
            release(f, 700);
            assertEquals(Selection.cursor(5_000), selection(f), "no selection change while PAUSED");
            wheel(f, 400.0, 0, -40, false, true);
            assertEquals(50_000L, axis(f).scrollFrame(), "scroll works while PAUSED");
            space(f); // stop
            assertEquals(PlaybackEngine.State.STOPPED, state(f));
            setAxis(f, 100.0, 0);
            click(f, 123.0, 40);
            assertEquals(Selection.cursor(12_300), selection(f), "the mouse works again after Stop");
        }
    }

    // ------------------------------------------------------------------ (d) wheel

    @Test
    void dCtrlWheelZoomsAboutThePointerAndShiftWheelScrolls() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            wheel(f, 333.0, 0, 80, true, false); // 2^2 = 4x at x = 333: frame 33300 stays put
            TimeAxis a = axis(f);
            assertEquals(25.0, a.framesPerPixel(), 0.0);
            assertEquals(24_975, a.scrollFrame());
            assertEquals(33_300, a.frameAtX(333.0));
            wheel(f, 333.0, 0, -40, true, false); // 0.5x
            a = axis(f);
            assertEquals(50.0, a.framesPerPixel(), 0.0);
            assertEquals(16_650, a.scrollFrame());
            assertEquals(33_300, a.frameAtX(333.0));
            wheel(f, 100.0, 0, 800, true, false); // 2^20 from 50 frames per pixel: clamped at 32 pixels per frame
            assertEquals(1.0 / 32.0, axis(f).framesPerPixel(), 0.0);
            wheel(f, 100.0, 0, -4000, true, false); // 2^-100: clamped at the widest zoom
            assertEquals(TEN_SECONDS / (double) f.lane * 1.25, axis(f).framesPerPixel(), 1e-9);

            int before = f.ancestorWheels.get();
            setAxis(f, 100.0, 50_000);
            wheel(f, 400.0, 0, 40, false, true);
            long s1 = 50_000 - 10L * f.lane;
            assertEquals(s1, axis(f).scrollFrame(), "shift+wheel up scrolls toward earlier time by visible/10");
            wheel(f, 400.0, 40, 0, false, true); // the delta arrives in deltaX
            assertEquals(s1 - 10L * f.lane, axis(f).scrollFrame());
            wheel(f, 400.0, 0, -40, false, true);
            assertEquals(s1, axis(f).scrollFrame());
            wheel(f, 400.0, 20, 0, false, true); // half a notch scrolls half the distance
            assertEquals(s1 - 5L * f.lane, axis(f).scrollFrame());
            setAxis(f, 100.0, 50_000);
            wheel(f, 400.0, 40, 0, false, false); // plain horizontal
            assertEquals(50_000 - 10L * f.lane, axis(f).scrollFrame());
            TimeAxis plain = axis(f);
            wheel(f, 400.0, 0, 40, false, false); // plain vertical wheel: the axis does not change
            assertEquals(plain, axis(f));
            assertEquals(before, f.ancestorWheels.get(), "no wheel event leaked past the input pane");
        }
    }

    // ------------------------------------------------------------------ (e) shortcuts and menus

    @Test
    void eShortcutsAndMenusDoTheSameThing() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            long W = f.lane;
            key(f, KeyCode.A, true);
            assertEquals(new Selection(0, TEN_SECONDS), selection(f));
            key(f, KeyCode.ESCAPE, false);
            assertEquals(Selection.cursor(0), selection(f));
            setSelection(f, new Selection(20_000, 50_000));
            key(f, KeyCode.ESCAPE, false);
            assertEquals(Selection.cursor(20_000), selection(f), "Esc keeps the selection start as the cursor");

            key(f, KeyCode.END, false);
            assertEquals(Selection.cursor(TEN_SECONDS), selection(f));
            // minimum scroll: the end frame lands on the last column x = W - 1
            assertEquals(TEN_SECONDS - 100L * (W - 1), axis(f).scrollFrame());
            key(f, KeyCode.HOME, false);
            assertEquals(Selection.cursor(0), selection(f));
            assertEquals(0, axis(f).scrollFrame());

            setSelection(f, Selection.cursor(34_700));
            setAxis(f, 100.0, 0);
            key(f, KeyCode.DIGIT1, true); // anchor at the cursor column 347
            assertEquals(50.0, axis(f).framesPerPixel(), 0.0, "exactly one zoom step (no double firing)");
            assertEquals(17_350, axis(f).scrollFrame());
            assertEquals(34_700, axis(f).frameAtX(347.0));
            setAxis(f, 100.0, 200_000); // the cursor is left of the view: the anchor is the middle
            long frameAtMiddle = axis(f).frameAtX(W / 2.0);
            key(f, KeyCode.DIGIT3, true);
            assertEquals(200.0, axis(f).framesPerPixel(), 0.0);
            assertEquals(W / 2.0, axis(f).xAtFrame(frameAtMiddle), 1.0);
            key(f, KeyCode.F, true);
            assertEquals(new TimeAxis(RATE, TEN_SECONDS / (double) W, 0), axis(f));

            // the same through the menus (View: Zoom In, Zoom Out, Fit; Select: All, Clear, Start, End)
            var view = f.pane.menuBar().getMenus().get(1).getItems();
            var select = f.pane.menuBar().getMenus().get(2).getItems();
            setAxis(f, 100.0, 0);
            setSelection(f, Selection.cursor(34_700));
            fx(() -> {
                view.get(0).fire();
                return null;
            });
            assertEquals(50.0, axis(f).framesPerPixel(), 0.0);
            fx(() -> {
                view.get(1).fire();
                return null;
            });
            assertEquals(100.0, axis(f).framesPerPixel(), 0.0);
            fx(() -> {
                view.get(2).fire();
                return null;
            });
            assertEquals(TEN_SECONDS / (double) W, axis(f).framesPerPixel(), 0.0);
            fx(() -> {
                select.get(0).fire();
                return null;
            });
            assertEquals(new Selection(0, TEN_SECONDS), selection(f));
            fx(() -> {
                select.get(1).fire();
                return null;
            });
            assertEquals(Selection.cursor(0), selection(f));
            fx(() -> {
                select.get(3).fire();
                return null;
            });
            assertEquals(Selection.cursor(TEN_SECONDS), selection(f));
            fx(() -> {
                select.get(2).fire();
                return null;
            });
            assertEquals(Selection.cursor(0), selection(f));
            for (MenuItem m : view) {
                assertTrue(m.getAccelerator() != null, m.getText() + " has an accelerator");
            }

            // all selection-changing keys are ignored while PLAYING; zoom still works
            setSelection(f, new Selection(20_000, 50_000));
            setAxis(f, 100.0, 0);
            space(f);
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            key(f, KeyCode.A, true);
            key(f, KeyCode.ESCAPE, false);
            key(f, KeyCode.END, false);
            key(f, KeyCode.HOME, false);
            assertEquals(new Selection(20_000, 50_000), selection(f));
            assertEquals(0, axis(f).scrollFrame());
            key(f, KeyCode.DIGIT1, true);
            assertEquals(50.0, axis(f).framesPerPixel(), 0.0, "zoom works while PLAYING");
            space(f);
        }
    }

    // ------------------------------------------------------------------ (f) Space and the buttons

    @Test
    void fSpaceFiresOnceNeverPressesAFocusedButtonAndTheButtonRulesHold() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            fx(() -> {
                f.pane.playButton().requestFocus();
                return null;
            });
            assertEquals(0, f.rig.sinkCount());
            assertFalse(fx(() -> f.pane.playButton().isDisabled()));
            assertTrue(fx(() -> f.pane.pauseButton().isDisabled()));
            assertTrue(fx(() -> f.pane.stopButton().isDisabled()), "Stop is disabled when STOPPED");
            keyOn(f, f.pane.playButton(), KeyCode.SPACE, false); // the focused button is the event target
            assertEquals(1, f.rig.sinkCount(), "Space plays exactly once (the button did not also fire)");
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            assertFalse(fx(() -> f.pane.playButton().isArmed()));
            assertTrue(fx(() -> f.pane.playButton().isDisabled()), "Play is disabled while PLAYING");
            assertFalse(fx(() -> f.pane.pauseButton().isDisabled()));
            assertFalse(fx(() -> f.pane.stopButton().isDisabled()));
            assertEquals("Pause", fx(() -> f.pane.pauseButton().getText()));

            key(f, KeyCode.P, false);
            assertEquals(PlaybackEngine.State.PAUSED, state(f));
            assertEquals("Resume", fx(() -> f.pane.pauseButton().getText()));
            assertTrue(fx(() -> f.pane.playButton().isDisabled()), "Play is disabled while PAUSED");
            key(f, KeyCode.P, false);
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            assertEquals("Pause", fx(() -> f.pane.pauseButton().getText()));

            keyOn(f, f.pane.playButton(), KeyCode.SPACE, false); // Space stops, the focused Play button must stay quiet
            assertEquals(PlaybackEngine.State.STOPPED, state(f));
            assertEquals(1, f.rig.sinkCount(), "the released Space did not press the (now enabled) Play button");
            assertTrue(fx(() -> f.pane.stopButton().isDisabled()));
            assertFalse(fx(() -> f.pane.playButton().isDisabled()));
            key(f, KeyCode.P, false); // P does nothing when STOPPED
            assertEquals(PlaybackEngine.State.STOPPED, state(f));
            space(f);
            assertEquals(2, f.rig.sinkCount(), "Space after Stop starts a new run");
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            space(f);
        }
    }

    // ------------------------------------------------------------------ (g) play range

    @Test
    void gPlayUsesTheSelectionTheCursorOrTheWholeProject() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            PlaybackEngine e = f.rig.engine();
            setSelection(f, new Selection(20_000, 90_000));
            space(f);
            assertEquals(20_000, e.startFrame());
            assertEquals(90_000, e.endFrame());
            space(f);
            setSelection(f, new Selection(400_000, 500_000)); // end beyond the project
            space(f);
            assertEquals(400_000, e.startFrame());
            assertEquals(TEN_SECONDS, e.endFrame());
            space(f);
            setSelection(f, Selection.cursor(100_000));
            space(f);
            assertEquals(100_000, e.startFrame());
            assertEquals(TEN_SECONDS, e.endFrame());
            space(f);
            setSelection(f, Selection.cursor(TEN_SECONDS)); // at the end: from 0
            space(f);
            assertEquals(0, e.startFrame());
            assertEquals(TEN_SECONDS, e.endFrame());
            space(f);
            assertEquals(4, f.rig.sinkCount());
        }
        // an empty project plays nothing
        try (Fx empty = open(0, 2, 0)) {
            space(empty);
            assertEquals(0, empty.rig.sinkCount());
            assertEquals(PlaybackEngine.State.STOPPED, state(empty));
            assertTrue(fx(() -> empty.pane.playButton().isDisabled()));
            key(empty, KeyCode.A, true);
            key(empty, KeyCode.END, false);
            assertEquals(Selection.cursor(0), selection(empty));
        }
        // the natural end: the cursor returns to the start frame and the selection stays
        try (Fx f = open(1, 1, 8_820)) {
            setSelection(f, new Selection(2_000, 6_000));
            setAxis(f, 10.0, 0);
            space(f);
            assertEquals(PlaybackEngine.State.PLAYING, state(f));
            assertTrue(f.rig.advanceUntil(() -> f.rig.finishedCount() == 1, 5_000_000L, 20_000),
                    "the run did not end");
            awaitState(f, PlaybackEngine.State.STOPPED);
            fx(() -> {
                f.pane.pulseNow();
                return null;
            });
            assertEquals(2_000, f.rig.engine().positionFrames());
            assertEquals(new Selection(2_000, 6_000), selection(f));
            assertEquals(2_000, f.rig.engine().startFrame());
            BufferedImage img = snapOverlay(f, "g-after-natural-end");
            assertLine(img, 200, 10, "cursor line at the selection start (frame 2000 / 10)");
            assertClear(img, 199, 10, "no second line");
            assertFalse(fx(() -> f.model.hasPlayhead()));
        }
    }

    // ------------------------------------------------------------------ (h) follow

    @Test
    void hThePlayheadIsFollowedWhilePlayingOnlyAndPagesAtNinetyFivePercent() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            long W = f.lane;
            space(f);
            PlaybackEngine e = f.rig.engine();
            // half way across the window: no follow yet
            assertTrue(f.rig.advanceUntil(() -> e.positionFrames() >= 50L * W, 20_000_000L, 30_000));
            fx(() -> {
                f.pane.pulseNow();
                return null;
            });
            assertEquals(0, axis(f).scrollFrame(), "the playhead is inside the window: no scroll");
            // past 95 % of the width (0.95 * 100 W frames): the view pages, the playhead lands at 10 %
            assertTrue(f.rig.advanceUntil(() -> e.positionFrames() >= 96L * W, 20_000_000L, 30_000));
            fx(() -> {
                f.pane.pulseNow();
                return null;
            });
            long play = fx(() -> f.model.playheadFrame());
            assertTrue(play >= 96L * W, "the playhead is past 95 %: " + play);
            assertEquals(play - 10L * W, axis(f).scrollFrame(), "page scroll: the playhead is 10 % from the left");
            snapScene(f, "h-follow-paged");
            // PAUSED: no follow, even with the playhead out of view
            key(f, KeyCode.P, false);
            assertEquals(PlaybackEngine.State.PAUSED, state(f));
            setAxis(f, 100.0, 300_000);
            fx(() -> {
                f.pane.pulseNow();
                return null;
            });
            assertEquals(300_000, axis(f).scrollFrame(), "no follow while PAUSED");
            // resuming brings it back (documented: follow wins over a manual scroll)
            key(f, KeyCode.P, false);
            fx(() -> {
                f.pane.pulseNow();
                return null;
            });
            long play2 = fx(() -> f.model.playheadFrame());
            assertEquals(play2 - 10L * W, axis(f).scrollFrame());
            space(f);
        }
    }

    // ------------------------------------------------------------------ (i) viewport overlay

    @Test
    void iTheOverlayIsViewportSizedAndDrawsOnlyOverTheTrackRows() throws Exception {
        try (Fx f = open(60, 2, 22_050)) {
            assertEquals(60 * 182.0, f.pane.lanesHeight(), 0.0);
            double vh = fx(() -> f.pane.scrollPane().getViewportBounds().getHeight());
            assertEquals(vh, fx(() -> f.pane.overlay().getHeight()), 0.0, "overlay is viewport-sized");
            assertTrue(vh <= 400 && fx(() -> f.pane.overlay().getHeight()) < 10_920 / 10.0);
            setSelection(f, new Selection(20_000, 50_000));
            for (double v : new double[] {0.0, 0.5, 1.0}) {
                fx(() -> {
                    f.pane.scrollPane().setVvalue(v);
                    return null;
                });
                BufferedImage img = snapOverlay(f, v == 0.5 ? "i-many-tracks-overlay" : null);
                int h = img.getHeight();
                assertLine(img, 200, 0, "line at the top, vvalue " + v);
                assertLine(img, 200, h - 1, "line at the bottom, vvalue " + v);
                assertClear(img, 199, h / 2, "left of the selection");
                assertTrue(img.getRGB(300, h / 2) >>> 24 > 70, "fill inside, vvalue " + v);
            }
            snapScene(f, "i-many-tracks-pane");
            // the cursor outside the view draws nothing
            setSelection(f, Selection.cursor(300_000)); // column 3000 is beyond the lane area
            BufferedImage none = snapOverlay(f, null);
            for (int y = 0; y < none.getHeight(); y += 5) {
                for (int x = 0; x < none.getWidth(); x += 5) {
                    assertEquals(0, none.getRGB(x, y) >>> 24, "nothing at " + x + "," + y);
                }
            }
            // the plain wheel scrolls the track stack; Ctrl and Shift wheels do not
            fx(() -> {
                f.pane.scrollPane().setVvalue(0.0);
                return null;
            });
            wheel(f, 100.0, 0, -80, false, false);
            double range = 10_920 - vh;
            assertEquals(80.0, fx(() -> f.pane.scrollPane().getVvalue()) * range, 1e-6);
            wheel(f, 100.0, 0, -80, true, false);
            wheel(f, 100.0, 0, -80, false, true);
            assertEquals(80.0, fx(() -> f.pane.scrollPane().getVvalue()) * range, 1e-6);
            // a press inside the scrolled rows still selects
            setAxis(f, 100.0, 0); // the Ctrl+wheel above changed the zoom
            fx(() -> {
                f.pane.scrollPane().setVvalue(1.0);
                return null;
            });
            click(f, 100.0, 5);
            assertEquals(Selection.cursor(10_000), selection(f));
        }
        // the extent logic on its own: viewport 200 px tall, rows 250 px tall scrolled up by 100 px
        Fx f = open(0, 2, 0);
        try {
            BufferedImage[] shots = new BufferedImage[3];
            fx(() -> {
                OverlayView ov = new OverlayView(f.model);
                f.model.setSelection(Selection.cursor(0));
                f.model.setLaneWidth(f.model.laneWidth());
                ov.setHeight(200);
                SnapshotParameters p = new SnapshotParameters();
                p.setFill(Color.TRANSPARENT);
                ov.setViewport(100, 250); // rows end at y = 150
                ov.repaint();
                shots[0] = toBuffered(ov.snapshot(p, null), "i-extent-scrolled");
                ov.setViewport(0, 91); // one mono track, not scrolled: rows end at y = 91
                ov.repaint();
                shots[1] = toBuffered(ov.snapshot(p, null), "i-extent-one-track");
                ov.setViewport(500, 300); // scrolled entirely past the rows
                ov.repaint();
                shots[2] = toBuffered(ov.snapshot(p, null), null);
                return null;
            });
            assertLine(shots[0], 0, 0, "top of the visible rows");
            assertLine(shots[0], 0, 149, "last visible row");
            assertClear(shots[0], 0, 150, "below the last track");
            assertClear(shots[0], 0, 199, "below the last track");
            assertLine(shots[1], 0, 90, "last row of a 91 px track");
            assertClear(shots[1], 0, 91, "below the track");
            for (int y = 0; y < 200; y += 9) {
                assertClear(shots[2], 0, y, "scrolled past everything");
            }
        } finally {
            f.close();
        }
    }

    // ------------------------------------------------------------------ (j) scrollbar sync

    @Test
    void jTheScrollbarStaysInSyncWithoutFeedbackLoops() throws Exception {
        try (Fx f = open(1, 2, TEN_SECONDS)) {
            long W = f.lane;
            // zoom: exactly one axis update, scrollbar equals the mapping and the hand-computed literals
            f.axisChanges.set(0);
            key(f, KeyCode.DIGIT1, true); // fpp 50, anchor at the middle (cursor 0 is at x 0 -> visible, anchor 0)
            assertEquals(1, f.axisChanges.get(), "one axis update per zoom");
            fx(() -> {
                f.pane.repaintNow();
                return null;
            });
            assertBarMatches(f);
            assertEquals(TEN_SECONDS - 50L * W / 10.0, fx(() -> f.pane.scrollBar().getMax()), 0.0);
            assertEquals(50.0 * W, fx(() -> f.pane.scrollBar().getVisibleAmount()), 0.0);
            // wheel scroll
            f.axisChanges.set(0);
            wheel(f, 100.0, 0, -40, false, true);
            assertEquals(1, f.axisChanges.get());
            fx(() -> {
                f.pane.repaintNow();
                return null;
            });
            assertBarMatches(f);
            assertEquals(5L * W, fx(() -> (long) f.pane.scrollBar().getValue()), "scrolled by visible / 10 = 5 W");
            // dragging the scrollbar: one axis update, no ping-pong
            f.axisChanges.set(0);
            fx(() -> {
                f.pane.scrollBar().setValue(12_345);
                return null;
            });
            assertEquals(1, f.axisChanges.get(), "one axis update per scrollbar drag");
            assertEquals(12_345, axis(f).scrollFrame());
            fx(() -> {
                f.pane.repaintNow();
                f.pane.repaintNow();
                return null;
            });
            assertEquals(1, f.axisChanges.get(), "repainting must not move the axis again");
            assertEquals(12_345.0, fx(() -> f.pane.scrollBar().getValue()), 0.0);
            // follow: one update per page
            setAxis(f, 100.0, 0);
            space(f);
            f.axisChanges.set(0);
            assertTrue(f.rig.advanceUntil(() -> f.rig.engine().positionFrames() >= 96L * W, 20_000_000L, 30_000));
            fx(() -> {
                f.pane.pulseNow();
                f.pane.pulseNow();
                return null;
            });
            assertEquals(1, f.axisChanges.get(), "one follow page, the second pulse changes nothing");
            assertBarMatches(f);
            space(f);
        }
    }

    private static void assertBarMatches(Fx f) throws Exception {
        ScrollbarMapping m = fx(() -> ScrollbarMapping.of(f.model.axis(), f.model.limits()));
        assertEquals(m.max(), fx(() -> f.pane.scrollBar().getMax()), 0.0);
        assertEquals(m.visibleAmount(), fx(() -> f.pane.scrollBar().getVisibleAmount()), 0.0);
        assertEquals(m.value(), fx(() -> f.pane.scrollBar().getValue()), 0.0);
    }
}
