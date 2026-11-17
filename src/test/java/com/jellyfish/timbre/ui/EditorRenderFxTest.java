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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.RulerTicks;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in render test ({@code -Dtimbre.fx=true}): builds the editor views on the JavaFX thread, snapshots them
 * offscreen (no Stage is shown, nothing is played) and asserts pixels against {@link Theme} constants and
 * hand-computed rows (row = round(45 - 44 v), clamped to [1, 89]). Text pixels are never asserted. PNGs are
 * written to {@code build-logs/m3b1/png} (override with {@code -Dtimbre.fx.png=<dir>}).
 */
@EnabledIfSystemProperty(named = "timbre.fx", matches = "true")
class EditorRenderFxTest {

    private static final int RATE = 44100;
    private static final Path PNG_DIR = Path.of(System.getProperty("timbre.fx.png", "build-logs/m3b1/png"));
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

    // ------------------------------------------------------------------ helpers

    private static <T> T fx(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        FutureTask<T> f = new FutureTask<>(task);
        Platform.runLater(f);
        return f.get(30, TimeUnit.SECONDS);
    }

    private static BufferedImage snap(Node node, String name, boolean transparent) throws Exception {
        return fx(() -> {
            SnapshotParameters p = new SnapshotParameters();
            p.setFill(transparent ? Color.TRANSPARENT : Color.WHITE);
            WritableImage img = node.snapshot(p, null);
            return toBuffered(img, name);
        });
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
        ImageIO.write(out, "png", PNG_DIR.resolve(name + ".png").toFile());
        return out;
    }

    private static int argb(Color c) {
        return (0xFF << 24) | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }

    private static boolean near(int actual, int expected, int tol) {
        for (int shift = 0; shift <= 24; shift += 8) {
            if (Math.abs(((actual >> shift) & 0xFF) - ((expected >> shift) & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    private static void assertPixel(BufferedImage img, int x, int y, Color expected, String what) {
        int actual = img.getRGB(x, y);
        assertTrue(near(actual, argb(expected), TOL), what + " at (" + x + "," + y + "): expected "
                + Integer.toHexString(argb(expected)) + " but was " + Integer.toHexString(actual));
    }

    /** The colour a lane row must have for a given set of band rows: independent of the painter. */
    private static Color expectedLaneColor(int row, int peakTop, int peakBottom, int rmsTop, int rmsBottom) {
        if (row == 90) {
            return Theme.SEPARATOR;
        }
        if (peakTop >= 0 && row >= rmsTop && row <= rmsBottom) {
            return Theme.RMS;
        }
        if (peakTop >= 0 && row >= peakTop && row <= peakBottom) {
            return Theme.PEAK;
        }
        return row == 45 ? Theme.CENTER_LINE : Theme.LANE_BACKGROUND;
    }

    /** Asserts every row of one lane column (rows 0..90) of a lane whose top row is {@code y0}. */
    private static void assertLaneColumn(BufferedImage img, int x, int y0, int peakTop, int peakBottom,
            int rmsTop, int rmsBottom) {
        for (int row = 0; row <= 90; row++) {
            assertPixel(img, x, y0 + row, expectedLaneColor(row, peakTop, peakBottom, rmsTop, rmsBottom),
                    "lane row " + row);
        }
    }

    private static void assertSilentColumn(BufferedImage img, int x, int y0) {
        assertLaneColumn(img, x, y0, -1, -1, -1, -1);
    }

    private static MemorySampleSource mono(float[] data) {
        return MemorySampleSource.copyOf(RATE, new float[][] {data});
    }

    private static float[] twoLevel(int frames, int firstHalfEnd, float first, float second) {
        float[] d = new float[frames];
        for (int i = 0; i < frames; i++) {
            d[i] = i < firstHalfEnd ? first : second;
        }
        return d;
    }

    /** A model that already knows the lane width, with the callbacks run directly on the calling thread. */
    private static EditorModel model(int width) throws Exception {
        return fx(() -> {
            EditorModel m = new EditorModel(Runnable::run);
            m.setLaneWidth(width);
            return m;
        });
    }

    private static void awaitPyramid(EditorModel m, SampleSource s) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        m.cache().request(s, ready::countDown);
        assertTrue(ready.await(20, TimeUnit.SECONDS), "pyramid not ready");
    }

    // ------------------------------------------------------------------ (a) mono, constant +0.75 then -0.25

    @Test
    void aConstantLevelsAreOneRowAtTheExpectedRowInDirectAndPeaksMode() throws Exception {
        // DIRECT: 8000 frames at 8 frames per pixel over 1000 columns; the level changes at frame 4000 = column 500.
        EditorModel m = model(1000);
        MemorySampleSource direct = mono(twoLevel(8000, 4000, 0.75f, -0.25f));
        fx(() -> {
            m.addTrack(Track.of("direct", direct));
            m.setAxis(new TimeAxis(RATE, 8.0, 0));
            return null;
        });
        LaneView lane = fx(() -> new LaneView(m, m.project().tracks().get(0), 0));
        fx(() -> {
            lane.repaint();
            return null;
        });
        assertEquals(LaneColumnsMode.DIRECT, LaneColumnsMode.of(lane));
        BufferedImage img = snap(lane, "a1-direct", false);
        assertEquals(1000, img.getWidth());
        assertEquals(91, img.getHeight());
        for (int x = 0; x < 1000; x++) {
            int row = x < 500 ? 12 : 56; // yOf(0.75) = 45 - 33 ; yOf(-0.25) = 45 + 11
            assertLaneColumn(img, x, 0, row, row, row, row);
        }

        // PEAKS: 1,024,000 frames at 1024 frames per pixel; the change at frame 512,000 is a multiple of the
        // 256-frame base block, so the block-granular peaks are exact.
        EditorModel m2 = model(1000);
        MemorySampleSource big = mono(twoLevel(1_024_000, 512_000, 0.75f, -0.25f));
        fx(() -> {
            m2.addTrack(Track.of("peaks", big));
            return null;
        });
        awaitPyramid(m2, big);
        LaneView lane2 = fx(() -> new LaneView(m2, m2.project().tracks().get(0), 0));
        fx(() -> {
            lane2.repaint();
            return null;
        });
        assertEquals(1024.0, m2.axis().framesPerPixel(), 0.0);
        assertEquals(LaneColumnsMode.PEAKS, LaneColumnsMode.of(lane2));
        BufferedImage img2 = snap(lane2, "a2-peaks", false);
        for (int x = 0; x < 1000; x++) {
            int row = x < 500 ? 12 : 56;
            assertLaneColumn(img2, x, 0, row, row, row, row);
        }
    }

    // ------------------------------------------------------------------ (b) silence

    @Test
    void bSilenceAndAPlaceholderLaneShowOnlyTheCentreLine() throws Exception {
        EditorModel m = model(1000);
        MemorySampleSource silent = mono(new float[1_024_000]);
        GatedSource gated = new GatedSource(silent);
        fx(() -> {
            m.addTrack(Track.of("silence", gated));
            return null;
        });
        LaneView lane = fx(() -> new LaneView(m, m.project().tracks().get(0), 0));
        fx(() -> {
            lane.repaint();
            return null;
        });
        assertEquals(LaneColumnsMode.PLACEHOLDER, LaneColumnsMode.of(lane));
        BufferedImage placeholder = snap(lane, "b1-placeholder", false);
        for (int x = 0; x < 1000; x++) {
            assertSilentColumn(placeholder, x, 0);
        }
        gated.open();
        awaitPyramid(m, gated);
        fx(() -> {
            lane.repaint();
            return null;
        });
        assertEquals(LaneColumnsMode.PEAKS, LaneColumnsMode.of(lane));
        BufferedImage ready = snap(lane, "b2-silence-peaks", false);
        for (int x = 0; x < 1000; x++) {
            assertSilentColumn(ready, x, 0);
        }
    }

    // ------------------------------------------------------------------ (c) stereo, L loud and R quiet

    @Test
    void cStereoLanesHaveTheirOwnBandHeightsAndSeparators() throws Exception {
        // Per 4-frame column: L = [0.8, 0, 0, -0.4], R = [0.2, 0, 0, -0.1].
        // L: peak rows yOf(0.8)=10 .. yOf(-0.4)=63; rms = sqrt(0.8/4) = 0.4472 -> rows 25 .. 65, clamped to 63.
        // R: peak rows yOf(0.2)=36 .. yOf(-0.1)=49; rms = sqrt(0.05/4) = 0.1118 -> rows 40 .. 50, clamped to 49.
        int frames = 4000;
        float[] left = new float[frames];
        float[] right = new float[frames];
        for (int i = 0; i < frames; i += 4) {
            left[i] = 0.8f;
            left[i + 3] = -0.4f;
            right[i] = 0.2f;
            right[i + 3] = -0.1f;
        }
        EditorModel m = model(1000);
        MemorySampleSource stereo = MemorySampleSource.copyOf(RATE, new float[][] {left, right});
        fx(() -> {
            m.addTrack(Track.of("stereo", stereo));
            m.setAxis(new TimeAxis(RATE, 4.0, 0));
            return null;
        });
        TrackRowView row = fx(() -> new TrackRowView(m, m.project().tracks().get(0)));
        Scene scene = fx(() -> new Scene(row));
        assertEquals(2, row.lanes().size());
        fx(() -> {
            row.repaintLanes();
            return null;
        });
        BufferedImage img = snap(row, "c-stereo-row", false);
        assertEquals(Theme.HEADER_WIDTH + 1000, img.getWidth());
        assertEquals(182, img.getHeight());
        for (int x : new int[] {0, 1, 333, 999}) {
            assertLaneColumn(img, Theme.HEADER_WIDTH + x, 0, 10, 63, 25, 63);
            assertLaneColumn(img, Theme.HEADER_WIDTH + x, 91, 36, 49, 40, 49);
        }
        assertTrue(scene.getRoot() == row);
        // header column: no waveform colour inside the header
        assertTrue(img.getRGB(10, 10) != argb(Theme.PEAK));
    }

    // ------------------------------------------------------------------ (d) two clips with a gap

    @Test
    void dGapsShowOnlyTheCentreLineAndClipsStartWhereTheTimelineSaysSo() throws Exception {
        // source: [0,500) = -0.75, [500,1500) = +0.5, [1500,4000) = -0.25
        float[] data = new float[4000];
        for (int i = 0; i < 4000; i++) {
            data[i] = i < 500 ? -0.75f : (i < 1500 ? 0.5f : -0.25f);
        }
        MemorySampleSource src = mono(data);
        // clip 1: source 500..2500 at timeline 1000..3000; clip 2: source 1000..2000 at timeline 5000..6000
        com.jellyfish.timbre.model.Track track = new Track("gap", RATE, 1);
        track.addClip(new com.jellyfish.timbre.model.Clip(src, 500, 2000, 1000));
        track.addClip(new com.jellyfish.timbre.model.Clip(src, 1000, 1000, 5000));
        EditorModel m = model(1000);
        fx(() -> {
            m.addTrack(track);
            m.setAxis(new TimeAxis(RATE, 10.0, 0));
            return null;
        });
        LaneView lane = fx(() -> new LaneView(m, track, 0));
        fx(() -> {
            lane.repaint();
            return null;
        });
        BufferedImage img = snap(lane, "d-gap", false);
        // 10 frames per column. expected: 0..99 gap; 100..199 +0.5 (row 23); 200..299 -0.25 (row 56);
        // 300..499 gap; 500..549 +0.5 (23); 550..599 -0.25 (56); 600.. gap
        for (int x = 0; x < 1000; x++) {
            if ((x >= 100 && x < 200) || (x >= 500 && x < 550)) {
                assertLaneColumn(img, x, 0, 23, 23, 23, 23);
            } else if ((x >= 200 && x < 300) || (x >= 550 && x < 600)) {
                assertLaneColumn(img, x, 0, 56, 56, 56, 56);
            } else {
                assertSilentColumn(img, x, 0);
            }
        }
    }

    // ------------------------------------------------------------------ (e) SAMPLE_LINES

    @Test
    void eSampleLinesDrawDotsAtTheSamplePositions() throws Exception {
        float[] ramp = {-0.5f, -0.25f, 0f, 0.2f, 0.4f, 0.6f, 0.8f, 1.0f};
        int[] rows = {67, 56, 45, 36, 27, 19, 10, 1};
        float[] data = new float[16];
        System.arraycopy(ramp, 0, data, 0, 8);
        EditorModel m = model(128);
        MemorySampleSource src = mono(data);
        fx(() -> {
            m.addTrack(Track.of("ramp", src));
            m.setAxis(new TimeAxis(RATE, 1.0 / 16.0, 0)); // 16 px per frame: 8 frames in 128 px
            return null;
        });
        LaneView lane = fx(() -> new LaneView(m, m.project().tracks().get(0), 0));
        fx(() -> {
            lane.repaint();
            return null;
        });
        assertEquals(LaneColumnsMode.SAMPLE_LINES, LaneColumnsMode.of(lane));
        BufferedImage img = snap(lane, "e-sample-lines", false);
        for (int k = 0; k < 8; k++) {
            int cx = 16 * k;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = cx + dx;
                    int y = rows[k] + dy;
                    if (x >= 0 && x < 128 && y >= 0 && y <= 89) {
                        assertPixel(img, x, y, Theme.SAMPLE_LINE, "dot " + k);
                    }
                }
            }
        }
        // between samples 0 and 1 the line is near row 62 at x = 8; far from it the lane is background
        assertPixel(img, 8, 10, Theme.LANE_BACKGROUND, "away from the line");
        assertPixel(img, 8, 45, Theme.CENTER_LINE, "centre line between dots");
        assertPixel(img, 8, 90, Theme.SEPARATOR, "separator");
        // no dot beyond the dot square of sample 3 (x 48, row 36): the polyline leaves it to the right, so the
        // pixels straight above (row 34) and below (row 38) the square are untouched at x = 48
        assertPixel(img, 48, 34, Theme.LANE_BACKGROUND, "above the dot square");
        assertPixel(img, 48, 38, Theme.LANE_BACKGROUND, "below the dot square");
    }

    // ------------------------------------------------------------------ (f) ruler

    @Test
    void fRulerTicksSitAtTheExpectedColumns() throws Exception {
        EditorModel m = model(1000);
        fx(() -> {
            m.setAxis(new TimeAxis(RATE, 441.0, 0)); // 100 px per second: major step 1 s, minor 0.1 s
            return null;
        });
        RulerView ruler = fx(() -> new RulerView(m));
        fx(() -> {
            ruler.repaint();
            return null;
        });
        BufferedImage img = snap(ruler, "f-ruler", false);
        assertEquals(1000, img.getWidth());
        assertEquals(Theme.RULER_HEIGHT, img.getHeight());
        // independent literal: 101 ticks, a major every 100 px with the labels 0..10, minors every 10 px
        List<RulerTicks.Tick> ticks = ruler.ticks();
        assertEquals(101, ticks.size());
        assertEquals(ticks, RulerTicks.generate(m.axis(), 1000));
        for (int i = 0; i < 101; i++) {
            RulerTicks.Tick t = ticks.get(i);
            assertEquals(i * 10.0, t.x(), 1e-6, "tick " + i + " x");
            assertEquals(i % 10 == 0, t.major(), "tick " + i + " major");
            assertEquals(i % 10 == 0 ? Integer.toString(i / 10) : "", t.label(), "tick " + i + " label");
        }
        for (int i = 0; i <= 100; i++) {
            int col = Math.min(i * 10, 999);
            boolean major = i % 10 == 0;
            int top = major ? Theme.RULER_HEIGHT - Theme.MAJOR_TICK_LENGTH : Theme.RULER_HEIGHT - Theme.MINOR_TICK_LENGTH;
            assertPixel(img, col, Theme.RULER_HEIGHT - 1, Theme.RULER_INK, "tick bottom " + i);
            assertPixel(img, col, top, Theme.RULER_INK, "tick top " + i);
            if (!major) {
                // a minor tick is short: the row above it is still background at that column
                assertPixel(img, col, top - 1, Theme.RULER_BACKGROUND, "above minor tick " + i);
            }
        }
        for (int x : new int[] {5, 15, 505, 995}) {
            assertPixel(img, x, Theme.RULER_HEIGHT - 1, Theme.RULER_BACKGROUND, "between ticks at " + x);
        }
    }

    // ------------------------------------------------------------------ (g) overlay

    @Test
    void gOverlayDrawsThePlayheadAndTheSelectionAtTheExpectedColumns() throws Exception {
        EditorModel m = model(1000);
        fx(() -> {
            m.setAxis(new TimeAxis(RATE, 100.0, 0));
            m.setSelection(Selection.between(20_000, 50_000)); // columns 200..499
            m.setPlayheadFrame(12_345); // 123.45 -> column 123
            return null;
        });
        OverlayView ov = fx(() -> new OverlayView(m));
        fx(() -> {
            ov.setHeight(60);
            ov.repaint();
            return null;
        });
        BufferedImage img = snap(ov, "g1-overlay-playhead", true);
        assertEquals(1000, img.getWidth());
        assertEquals(60, img.getHeight());
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 1000; x++) {
                int p = img.getRGB(x, y);
                int alpha = p >>> 24;
                if (x == 123) {
                    assertPixel(img, x, y, Theme.PLAYHEAD, "playhead");
                } else if (x >= 200 && x <= 499) {
                    assertTrue(Math.abs(alpha - 77) <= 2, "selection alpha at " + x + "," + y + ": " + alpha);
                    assertTrue(near(p | (0xFF << 24), argb(Theme.SELECTION_FILL) | (0xFF << 24), 8),
                            "selection colour at " + x + "," + y + ": " + Integer.toHexString(p));
                } else {
                    assertEquals(0, alpha, "nothing outside at " + x + "," + y);
                }
            }
        }
        // without a playhead the line is at the selection start (frame 20000 -> column 200), over the fill
        fx(() -> {
            m.clearPlayhead();
            ov.repaint();
            return null;
        });
        BufferedImage img2 = snap(ov, "g2-overlay-cursor", true);
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 1000; x++) {
                int alpha = img2.getRGB(x, y) >>> 24;
                if (x == 200) {
                    assertPixel(img2, x, y, Theme.PLAYHEAD, "cursor line");
                } else if (x > 200 && x <= 499) {
                    assertTrue(alpha > 70 && alpha < 85, "fill at " + x);
                } else {
                    assertEquals(0, alpha, "nothing outside at " + x + "," + y);
                }
            }
        }
        // a cursor with no selection: only the line at frame 70000 -> column 700
        fx(() -> {
            m.setSelection(Selection.cursor(70_000));
            ov.repaint();
            return null;
        });
        BufferedImage img3 = snap(ov, "g3-overlay-cursor-only", true);
        for (int x = 0; x < 1000; x++) {
            assertEquals(x == 700 ? 255 : 0, img3.getRGB(x, 30) >>> 24, "column " + x);
        }
    }

    // ------------------------------------------------------------------ (h) pyramid ready transition

    @Test
    void hTheWaveformAppearsWhenThePyramidBecomesReady() throws Exception {
        EditorModel m = fx(() -> {
            EditorModel x = new EditorModel(); // real FX-thread hop
            x.setLaneWidth(1000);
            return x;
        });
        MemorySampleSource inner = mono(twoLevel(1_024_000, 512_000, 0.75f, -0.25f));
        GatedSource gated = new GatedSource(inner);
        fx(() -> {
            m.addTrack(Track.of("gated", gated));
            return null;
        });
        LaneView lane = fx(() -> new LaneView(m, m.project().tracks().get(0), 0));
        fx(() -> {
            lane.repaint();
            return null;
        });
        BufferedImage before = snap(lane, "h1-before-ready", false);
        for (int x = 0; x < 1000; x += 37) {
            assertSilentColumn(before, x, 0);
        }
        CountDownLatch bumped = new CountDownLatch(1);
        fx(() -> {
            m.waveformVersion().addListener((obs, o, n) -> bumped.countDown());
            return null;
        });
        gated.open();
        assertTrue(bumped.await(20, TimeUnit.SECONDS), "no pyramid-ready notification");
        fx(() -> {
            lane.repaint();
            return null;
        });
        BufferedImage after = snap(lane, "h2-after-ready", false);
        for (int x = 0; x < 1000; x += 37) {
            int row = x < 500 ? 12 : 56;
            assertLaneColumn(after, x, 0, row, row, row, row);
        }
        fx(() -> {
            m.close();
            return null;
        });
    }

    // ------------------------------------------------------------------ (i) header text and status bar

    @Test
    void iHeaderAndStatusTexts() throws Exception {
        TrackHeaderView mono = fx(() -> new TrackHeaderView(Track.of("tone_mono", mono(new float[100]))));
        TrackHeaderView stereo = fx(() -> new TrackHeaderView(Track.of("beeps_stereo",
                MemorySampleSource.copyOf(48000, new float[][] {new float[10], new float[10]}))));
        assertEquals("tone_mono", ((Label) mono.lookup("#trackName")).getText());
        assertEquals("Mono, 44100 Hz, 32-bit float", ((Label) mono.lookup("#trackFormat")).getText());
        assertEquals("beeps_stereo", ((Label) stereo.lookup("#trackName")).getText());
        assertEquals("Stereo, 48000 Hz, 32-bit float", ((Label) stereo.lookup("#trackFormat")).getText());
        assertEquals(120.0, mono.getPrefWidth(), 0.0);
        assertEquals(91.0, mono.getPrefHeight(), 0.0);
        assertEquals(182.0, stereo.getPrefHeight(), 0.0);

        StatusBarView bar = fx(StatusBarView::new);
        // 48000 Hz: 175,932,000 frames = 3665.25 s = 1:01:05.250; 48,000 = 1 s; 264,000 = 5.5 s; length 4.5 s
        fx(() -> {
            bar.update(new Selection(48_000, 264_000), -1, 48000);
            return null;
        });
        assertEquals("Cursor: 0:00:01.000", bar.cursorLabel().getText());
        assertEquals("Selection: 0:00:01.000 - 0:00:05.500 (0:00:04.500)", bar.selectionLabel().getText());
        fx(() -> {
            bar.update(new Selection(48_000, 264_000), 175_932_000L, 48000);
            return null;
        });
        assertEquals("Cursor: 1:01:05.250", bar.cursorLabel().getText());
        fx(() -> {
            bar.update(Selection.cursor(0), -1, 48000);
            return null;
        });
        assertEquals("Cursor: 0:00:00.000", bar.cursorLabel().getText());
        assertEquals("Selection: 0:00:00.000 - 0:00:00.000 (0:00:00.000)", bar.selectionLabel().getText());
        assertEquals(bar.cursorLabel().getText().length(), "Cursor: 1:01:05.250".length());
        assertTrue(bar.lookup("#statusCursor") == bar.cursorLabel());
    }

    // ------------------------------------------------------------------ the assembled pane

    @Test
    void jTheAssembledPaneAlignsRulerLanesOverlayAndScrollbar() throws Exception {
        EditorPane pane = fx(EditorPane::new);
        Scene scene = fx(() -> {
            Scene s = new Scene(pane, 1000, 400);
            pane.applyCss();
            pane.layout();
            return s;
        });
        assertEquals(1000.0, scene.getWidth(), 0.0);
        float[] l = new float[RATE * 4];
        float[] r = new float[RATE * 4];
        for (int i = 0; i < l.length; i++) {
            l[i] = (float) (0.8 * Math.sin(i * 0.05));
            r[i] = (float) (0.2 * Math.sin(i * 0.031));
        }
        fx(() -> {
            pane.addTrack(Track.of("stereo_demo", MemorySampleSource.copyOf(RATE, new float[][] {l, r})));
            pane.addTrack(Track.of("mono_demo", mono(l)));
            pane.applyCss();
            pane.layout();
            pane.repaintNow();
            return null;
        });
        Thread.sleep(200); // let the pyramids finish and hop to the FX thread (cosmetic: the PNG shows peaks)
        BufferedImage img = fx(() -> {
            pane.layout();
            pane.repaintNow();
            return toBuffered(scene.snapshot(null), "j-pane");
        });
        assertEquals(1000, img.getWidth());
        double rulerX = fx(() -> pane.ruler().localToScene(0, 0).getX());
        double laneX = fx(() -> pane.rows().get(0).lanes().get(0).localToScene(0, 0).getX());
        double overlayX = fx(() -> pane.overlay().localToScene(0, 0).getX());
        double barX = fx(() -> pane.scrollBar().localToScene(0, 0).getX());
        assertEquals(120.0, rulerX, 0.5);
        assertEquals(rulerX, laneX, 0.5);
        assertEquals(rulerX, overlayX, 0.5);
        assertEquals(rulerX, barX, 0.5);
        assertEquals(pane.ruler().getWidth(), pane.rows().get(0).lanes().get(0).getWidth(), 0.0);
        assertEquals(pane.model().laneWidth(), (int) pane.ruler().getWidth());
        assertEquals(3 * 91.0, pane.lanesHeight(), 0.0);
        fx(() -> {
            pane.close();
            return null;
        });
    }

    // ------------------------------------------------------------------ support

    /** Reports the mode of the last repaint of a lane. */
    private enum LaneColumnsMode {
        SAMPLE_LINES, PEAKS, DIRECT, PLACEHOLDER;

        static LaneColumnsMode of(LaneView lane) throws Exception {
            return fx(() -> valueOf(lane.columns().mode().name()));
        }
    }

    /** A source whose reads block until the test opens the gate, so a pyramid cannot finish earlier. */
    private static final class GatedSource implements SampleSource {
        private final SampleSource inner;
        private final CountDownLatch gate = new CountDownLatch(1);

        GatedSource(SampleSource inner) {
            this.inner = inner;
        }

        void open() {
            gate.countDown();
        }

        @Override
        public int sampleRate() {
            return inner.sampleRate();
        }

        @Override
        public int channelCount() {
            return inner.channelCount();
        }

        @Override
        public long frameCount() {
            return inner.frameCount();
        }

        @Override
        public void read(long startFrame, int frames, float[][] dest, int destOffset) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            inner.read(startFrame, frames, dest, destOffset);
        }
    }
}
