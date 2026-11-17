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
package com.jellyfish.timbre.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.dsp.PeakPyramid;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.ui.viewmodel.LaneColumns.Mode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LaneSamplerTest {

    private static final int RATE = 44100;

    private static MemorySampleSource random(Random r, int channels, int frames) {
        float[][] d = new float[channels][frames];
        for (float[] ch : d) {
            for (int i = 0; i < frames; i++) {
                ch[i] = (float) (r.nextDouble() * 2 - 1);
            }
        }
        return MemorySampleSource.adopt(RATE, d);
    }

    private static Function<SampleSource, PeakPyramid> pyramidsFor(SampleSource... sources) {
        Map<SampleSource, PeakPyramid> m = new IdentityHashMap<>();
        for (SampleSource s : sources) {
            m.put(s, PeakPyramid.build(s));
        }
        return m::get;
    }

    private static final Function<SampleSource, PeakPyramid> NOT_READY = s -> null;

    // ------------------------------------------------------------ brute-force references

    /** The value of the lane at a timeline frame, or 0 in a gap. */
    private static float valueAt(List<Clip> clips, int channel, long t) {
        for (Clip c : clips) {
            if (t >= c.timelineStart() && t < c.timelineEnd()) {
                float[][] one = new float[c.source().channelCount()][1];
                c.source().read(c.sourceStart() + (t - c.timelineStart()), 1, one, 0);
                return one[channel][0];
            }
        }
        return 0f;
    }

    private static long boundary(TimeAxis a, int x) {
        return a.scrollFrame() + (long) Math.floor(x * a.framesPerPixel());
    }

    /** Exact min, max and rms per column, frame by frame. */
    private static void assertDirect(List<Clip> clips, int channel, TimeAxis axis, int width, LaneColumns got) {
        assertEquals(Mode.DIRECT, got.mode());
        for (int x = 0; x < width; x++) {
            long a = boundary(axis, x);
            long b = boundary(axis, x + 1);
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            double ss = 0;
            for (long t = a; t < b; t++) {
                float v = valueAt(clips, channel, t);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
                ss += (double) v * v;
            }
            String w = "column " + x + " [" + a + "," + b + ")";
            assertEquals(lo, got.min(x), w);
            assertEquals(hi, got.max(x), w);
            assertEquals((float) Math.sqrt(ss / (b - a)), got.rms(x), 1e-6f, w);
        }
    }

    private static int levelFor(double fpp, long sourceFrames) {
        // the largest L with 256 * 4^L <= fpp / 2, clamped to the top level of this source
        int top = 0;
        long bf = 256;
        while ((sourceFrames + bf - 1) / bf > 1) {
            bf *= 4;
            top++;
        }
        int level = -1;
        for (int l = 0; l <= top; l++) {
            if ((256L << (2 * l)) <= fpp / 2) {
                level = l;
            }
        }
        return level;
    }

    /** The documented superset: whole covering blocks per clip piece, gaps as zeros. */
    private static void assertPeaksSuperset(List<Clip> clips, int channel, TimeAxis axis, int width,
            LaneColumns got) {
        assertEquals(Mode.PEAKS, got.mode());
        int worstBlocks = 0;
        for (int x = 0; x < width; x++) {
            long a = boundary(axis, x);
            long b = boundary(axis, x + 1);
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            double ss = 0;
            long frames = 0;
            long pieces = 0;
            int blocks = 0;
            for (Clip c : clips) {
                long from = Math.max(a, c.timelineStart());
                long to = Math.min(b, c.timelineEnd());
                if (from >= to) {
                    continue;
                }
                pieces += to - from;
                long s0 = c.sourceStart() + (from - c.timelineStart());
                long s1 = s0 + (to - from);
                long n = c.source().frameCount();
                long bf = 256L << (2 * levelFor(axis.framesPerPixel(), n));
                long fromFrame = s0 / bf * bf;
                long toFrame = Math.min((s1 + bf - 1) / bf * bf, n);
                blocks += (int) ((s1 + bf - 1) / bf - s0 / bf);
                float[][] buf = new float[c.source().channelCount()][(int) (toFrame - fromFrame)];
                c.source().read(fromFrame, (int) (toFrame - fromFrame), buf, 0);
                for (float v : buf[channel]) {
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                    ss += (double) v * v;
                }
                frames += toFrame - fromFrame;
            }
            long gap = (b - a) - pieces;
            if (gap > 0) {
                lo = Math.min(lo, 0f);
                hi = Math.max(hi, 0f);
                frames += gap;
            }
            String w = "column " + x + " [" + a + "," + b + ") fpp " + axis.framesPerPixel();
            if (frames == 0) {
                assertEquals(0f, got.min(x), w);
                assertEquals(0f, got.max(x), w);
                assertEquals(0f, got.rms(x), w);
            } else {
                assertEquals(lo, got.min(x), w);
                assertEquals(hi, got.max(x), w);
                assertEquals((float) Math.sqrt(ss / frames), got.rms(x), 2e-6f, w);
            }
            worstBlocks = Math.max(worstBlocks, blocks);
        }
        assertEquals(worstBlocks, got.maxBlocksPerColumn(), "block diagnostic");
    }

    // ------------------------------------------------------------ DIRECT

    @Test
    void directIsExactWithOffsetsAndAGap() {
        Random r = new Random(1);
        MemorySampleSource a = random(r, 2, 3000);
        MemorySampleSource b = random(r, 2, 2000);
        List<Clip> clips = List.of(new Clip(a, 100, 1500, 50), new Clip(b, 7, 1000, 2000));
        LaneSampler sampler = new LaneSampler();
        for (int channel = 0; channel < 2; channel++) {
            for (double fpp : new double[] {1.0, 1.5, 7.3, 20, 100.25, 511.5}) {
                for (long scroll : new long[] {0, 20, 1999, 2500}) {
                    TimeAxis axis = new TimeAxis(RATE, fpp, scroll);
                    LaneColumns got = sampler.sample(clips, channel, NOT_READY, axis, 120);
                    assertDirect(clips, channel, axis, 120, got);
                }
            }
        }
    }

    @Test
    void columnsBeforeTheFirstAndAfterTheLastClipAreSilence() {
        MemorySampleSource s = MemorySampleSource.adopt(RATE, new float[][] {{0.5f, 0.5f, 0.5f, 0.5f}});
        List<Clip> clips = List.of(new Clip(s, 0, 4, 10));
        TimeAxis axis = new TimeAxis(RATE, 4, 0);
        // columns: [0,4) gap, [4,8) gap, [8,12) 2 gap + 2 audio, [12,16) 2 audio + 2 gap, [16,20) gap
        LaneColumns got = new LaneSampler().sample(clips, 0, NOT_READY, axis, 5);
        assertEquals(Mode.DIRECT, got.mode());
        float[] expectedMin = {0, 0, 0, 0, 0};
        float[] expectedMax = {0, 0, 0.5f, 0.5f, 0};
        float[] expectedRms = {0, 0, (float) Math.sqrt(0.5 / 4), (float) Math.sqrt(0.5 / 4), 0};
        for (int x = 0; x < 5; x++) {
            assertEquals(expectedMin[x], got.min(x), "min " + x);
            assertEquals(expectedMax[x], got.max(x), "max " + x);
            assertEquals(expectedRms[x], got.rms(x), 1e-7f, "rms " + x);
        }
    }

    @Test
    void aGapWidensANegativeOnlyColumnToZero() {
        MemorySampleSource s = MemorySampleSource.adopt(RATE, new float[][] {{-0.5f, -0.25f}});
        List<Clip> clips = List.of(new Clip(s, 0, 2, 2));
        LaneColumns got = new LaneSampler().sample(clips, 0, NOT_READY, new TimeAxis(RATE, 4, 0), 1);
        assertEquals(-0.5f, got.min(0));
        assertEquals(0f, got.max(0), "the two gap frames are zeros");
        assertEquals((float) Math.sqrt((0.25 + 0.0625) / 4), got.rms(0), 1e-7f);
    }

    @Test
    void directSanitisesNonFiniteSamples() {
        MemorySampleSource s = MemorySampleSource.adopt(RATE,
                new float[][] {{Float.NaN, 0.5f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}});
        LaneColumns got = new LaneSampler().sample(List.of(new Clip(s, 0, 4, 0)), 0, NOT_READY,
                new TimeAxis(RATE, 4, 0), 1);
        assertEquals(-Float.MAX_VALUE, got.min(0));
        assertEquals(Float.MAX_VALUE, got.max(0));
        assertTrue(Float.isFinite(got.rms(0)) || got.rms(0) == Float.POSITIVE_INFINITY);
    }

    // ------------------------------------------------------------ SAMPLE_LINES

    @Test
    void sampleLinesOnAnEightSampleSource() {
        float[] data = {0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.7f, -0.8f};
        MemorySampleSource s = MemorySampleSource.adopt(RATE, new float[][] {data});
        // source frames 2..6 (0.3, -0.4, 0.5, -0.6, 0.7) sit at timeline frames 3..7
        List<Clip> clips = List.of(new Clip(s, 2, 5, 3));
        TimeAxis axis = new TimeAxis(RATE, 0.5, 1);
        LaneColumns got = new LaneSampler().sample(clips, 0, NOT_READY, axis, 10);
        assertEquals(Mode.SAMPLE_LINES, got.mode());
        // visible frames 1 .. frameAtX(10) = 6, plus one: frames 1..6
        assertEquals(1, got.spanStart());
        assertEquals(6, got.spanCount());
        float[] expected = {0f, 0f, 0.3f, -0.4f, 0.5f, -0.6f};
        for (int i = 0; i < 6; i++) {
            assertEquals(expected[i], got.sample(i), "sample " + i);
        }
        // columns: frameAtX(x) = 1 + floor(x / 2)
        float[] perColumn = {0f, 0f, 0f, 0f, 0.3f, 0.3f, -0.4f, -0.4f, 0.5f, 0.5f};
        for (int x = 0; x < 10; x++) {
            assertEquals(perColumn[x], got.min(x), "min " + x);
            assertEquals(perColumn[x], got.max(x), "max " + x);
            assertEquals(Math.abs(perColumn[x]), got.rms(x), "rms " + x);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> got.sample(6));
        assertEquals(0, got.maxBlocksPerColumn());
    }

    @Test
    void sampleLinesAtHighZoomReadFromTheRightSourceFrames() {
        Random r = new Random(5);
        MemorySampleSource s = random(r, 3, 500);
        List<Clip> clips = List.of(new Clip(s, 40, 300, 100));
        for (double fpp : new double[] {0.999, 0.5, 0.1, 1.0 / 32}) {
            TimeAxis axis = new TimeAxis(RATE, fpp, 90);
            LaneColumns got = new LaneSampler().sample(clips, 2, NOT_READY, axis, 200);
            assertEquals(Mode.SAMPLE_LINES, got.mode());
            assertEquals(90, got.spanStart());
            assertEquals(axis.frameAtX(200) - 90 + 1, got.spanCount());
            float[][] one = new float[3][1];
            for (int i = 0; i < got.spanCount(); i++) {
                long t = 90 + i;
                float expected = 0f;
                if (t >= 100 && t < 400) {
                    s.read(40 + (t - 100), 1, one, 0);
                    expected = one[2][0];
                }
                assertEquals(expected, got.sample(i), "fpp " + fpp + " frame " + t);
            }
        }
    }

    // ------------------------------------------------------------ mode boundaries

    @Test
    void modeBoundaries() {
        MemorySampleSource s = random(new Random(3), 1, 3_000_000);
        List<Clip> clips = List.of(new Clip(s, 0, 3_000_000, 0));
        LaneSampler sampler = new LaneSampler();
        Function<SampleSource, PeakPyramid> ready = pyramidsFor(s);
        assertEquals(Mode.SAMPLE_LINES, sampler.sample(clips, 0, ready, new TimeAxis(RATE, 0.999, 0), 50).mode());
        assertEquals(Mode.DIRECT, sampler.sample(clips, 0, ready, new TimeAxis(RATE, 1.0, 0), 50).mode());
        assertEquals(Mode.DIRECT, sampler.sample(clips, 0, ready, new TimeAxis(RATE, 511.99, 0), 50).mode());
        assertEquals(Mode.PEAKS, sampler.sample(clips, 0, ready, new TimeAxis(RATE, 512.0, 0), 50).mode());
        assertEquals(Mode.PEAKS, sampler.sample(clips, 0, ready, new TimeAxis(RATE, 17640, 0), 50).mode());
        // pyramid not ready
        assertEquals(Mode.SAMPLE_LINES, sampler.sample(clips, 0, NOT_READY, new TimeAxis(RATE, 0.5, 0), 50).mode());
        assertEquals(Mode.DIRECT, sampler.sample(clips, 0, NOT_READY, new TimeAxis(RATE, 511.99, 0), 50).mode());
        LaneColumns ph = sampler.sample(clips, 0, NOT_READY, new TimeAxis(RATE, 512.0, 0), 50);
        assertEquals(Mode.PLACEHOLDER, ph.mode());
        assertTrue(ph.isPlaceholder());
        for (int x = 0; x < 50; x++) {
            assertEquals(0f, ph.min(x));
            assertEquals(0f, ph.max(x));
            assertEquals(0f, ph.rms(x));
        }
        assertFalse(sampler.sample(clips, 0, ready, new TimeAxis(RATE, 512.0, 0), 50).isPlaceholder());
    }

    @Test
    void onlyVisibleClipsNeedAPyramid() {
        Random r = new Random(8);
        MemorySampleSource near = random(r, 1, 100_000);
        MemorySampleSource far = random(r, 1, 100_000);
        List<Clip> clips = List.of(new Clip(near, 0, 100_000, 0), new Clip(far, 0, 100_000, 5_000_000));
        Function<SampleSource, PeakPyramid> onlyNear = pyramidsFor(near);
        LaneColumns got = new LaneSampler().sample(clips, 0, onlyNear, new TimeAxis(RATE, 1000, 0), 100);
        assertEquals(Mode.PEAKS, got.mode(), "the clip far to the right is not visible");
        LaneColumns wide = new LaneSampler().sample(clips, 0, onlyNear, new TimeAxis(RATE, 1000, 4_950_000), 100);
        assertEquals(Mode.PLACEHOLDER, wide.mode(), "now the far clip is visible and not ready");
    }

    @Test
    void emptyClipListGivesZeros() {
        LaneColumns d = new LaneSampler().sample(List.of(), 0, NOT_READY, new TimeAxis(RATE, 10, 0), 20);
        assertEquals(Mode.DIRECT, d.mode());
        LaneColumns p = new LaneSampler().sample(List.of(), 0, NOT_READY, new TimeAxis(RATE, 5000, 0), 20);
        assertEquals(Mode.PEAKS, p.mode());
        for (int x = 0; x < 20; x++) {
            assertEquals(0f, d.max(x));
            assertEquals(0f, p.min(x));
            assertEquals(0f, p.rms(x));
        }
    }

    // ------------------------------------------------------------ PEAKS

    @Test
    void peaksMatchTheBlockSupersetReference() {
        Random r = new Random(11);
        for (int iter = 0; iter < 40; iter++) {
            int channels = 1 + r.nextInt(3);
            MemorySampleSource a = random(r, channels, 20_000 + r.nextInt(300_000));
            MemorySampleSource b = random(r, channels, 5_000 + r.nextInt(100_000));
            long aStart = r.nextInt(2000);
            long aLen = 1 + r.nextInt((int) (a.frameCount() - aStart));
            long aTimeline = r.nextInt(5000);
            long bStart = r.nextInt(1000);
            long bLen = 1 + r.nextInt((int) (b.frameCount() - bStart));
            long bTimeline = aTimeline + aLen + r.nextInt(30_000); // gap of 0 to 30000 frames
            List<Clip> clips = List.of(new Clip(a, aStart, aLen, aTimeline), new Clip(b, bStart, bLen, bTimeline));
            Function<SampleSource, PeakPyramid> ready = pyramidsFor(a, b);
            double fpp = 512 + r.nextDouble() * 30_000;
            long scroll = r.nextInt(100_000);
            TimeAxis axis = new TimeAxis(RATE, fpp, scroll);
            int channel = r.nextInt(channels);
            LaneColumns got = new LaneSampler().sample(clips, channel, ready, axis, 150);
            assertPeaksSuperset(clips, channel, axis, 150, got);
        }
    }

    @Test
    void peaksAreAtLeastAsWideAsTheExactValues() {
        Random r = new Random(12);
        MemorySampleSource s = random(r, 1, 400_000);
        List<Clip> clips = List.of(new Clip(s, 321, 350_000, 777));
        Function<SampleSource, PeakPyramid> ready = pyramidsFor(s);
        LaneSampler sampler = new LaneSampler();
        for (double fpp : new double[] {512, 700, 2047, 2048, 5000, 8191, 8192, 30000}) {
            TimeAxis axis = new TimeAxis(RATE, fpp, 0);
            LaneColumns peaks = sampler.sample(clips, 0, ready, axis, 100);
            for (int x = 0; x < 100; x++) {
                long a = boundary(axis, x);
                long b = boundary(axis, x + 1);
                float lo = Float.POSITIVE_INFINITY;
                float hi = Float.NEGATIVE_INFINITY;
                for (long t = a; t < b; t++) {
                    float v = valueAt(clips, 0, t);
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                }
                assertTrue(peaks.min(x) <= lo, "fpp " + fpp + " column " + x);
                assertTrue(peaks.max(x) >= hi, "fpp " + fpp + " column " + x);
            }
        }
    }

    @Test
    void neverMoreThanNineBlocksPerColumn() {
        Random r = new Random(13);
        MemorySampleSource s = random(r, 1, 600_000);
        List<Clip> single = List.of(new Clip(s, 1234, 500_000, 4321));
        Function<SampleSource, PeakPyramid> ready = pyramidsFor(s);
        LaneSampler sampler = new LaneSampler();
        int worst = 0;
        // dense sweep, plus the values just below and at every level switch (2, 8 times the block length)
        double[] special = {512, 2047.999, 2048, 8191.999, 8192, 32767.999, 32768, 131071.999, 131072, 1e6};
        for (int i = 0; i < 4000; i++) {
            double fpp = i < special.length ? special[i] : Math.exp(Math.log(512) + r.nextDouble() * 8);
            for (long scroll : new long[] {0, 1, 255, 256, 257, 12345, 99_999}) {
                LaneColumns got = sampler.sample(single, 0, ready, new TimeAxis(RATE, fpp, scroll), 60);
                assertEquals(Mode.PEAKS, got.mode());
                worst = Math.max(worst, got.maxBlocksPerColumn());
                assertTrue(got.maxBlocksPerColumn() <= 9,
                        "fpp " + fpp + " scroll " + scroll + ": " + got.maxBlocksPerColumn() + " blocks");
            }
        }
        assertTrue(worst >= 5, "the sweep should come close to the limit, worst was " + worst);
    }

    @Test
    void aColumnAcrossAClipBoundaryAggregatesAtMostElevenBlocks() {
        Random r = new Random(14);
        MemorySampleSource a = random(r, 1, 300_000);
        MemorySampleSource b = random(r, 1, 300_000);
        List<Clip> clips = List.of(new Clip(a, 17, 200_001, 0), new Clip(b, 999, 100_003, 200_001));
        Function<SampleSource, PeakPyramid> ready = pyramidsFor(a, b);
        LaneSampler sampler = new LaneSampler();
        int worst = 0;
        for (int i = 0; i < 3000; i++) {
            double fpp = Math.exp(Math.log(512) + r.nextDouble() * 6);
            LaneColumns got = sampler.sample(clips, 0, ready, new TimeAxis(RATE, fpp, r.nextInt(250_000)), 80);
            worst = Math.max(worst, got.maxBlocksPerColumn());
        }
        // each clip piece touches at most 9 blocks, but two pieces of one column together may need up to
        // ceil(len1 / bf) + ceil(len2 / bf) + 2 <= 11 (documented deviation from the 9-block design invariant)
        assertTrue(worst <= 11, "worst across a clip boundary: " + worst);
        assertTrue(worst >= 10, "expected the sweep to show the boundary excess, worst was " + worst);
    }

    // ------------------------------------------------------------ impulses stay visible

    @Test
    void anImpulseStaysVisibleAtEveryZoom() {
        Random r = new Random(15);
        for (int iter = 0; iter < 150; iter++) {
            int n = 50_000 + r.nextInt(400_000);
            float[] d = new float[n];
            int pos = r.nextInt(n);
            boolean negative = iter % 2 == 1;
            d[pos] = negative ? -1f : 1f;
            MemorySampleSource s = MemorySampleSource.adopt(RATE, new float[][] {d});
            long timeline = r.nextInt(20_000);
            List<Clip> clips = List.of(new Clip(s, 0, n, timeline));
            double fpp = Math.exp(Math.log(1.0 / 32) + r.nextDouble() * Math.log(40_000 * 32.0));
            long impulseFrame = timeline + pos;
            long scroll = Math.max(0, impulseFrame - (long) (r.nextDouble() * 150 * Math.max(fpp, 1)));
            TimeAxis axis = new TimeAxis(RATE, fpp, scroll);
            LaneColumns got = new LaneSampler().sample(clips, 0, pyramidsFor(s), axis, 160);
            boolean seen = false;
            if (got.mode() == Mode.SAMPLE_LINES) {
                long idx = impulseFrame - got.spanStart();
                if (idx >= 0 && idx < got.spanCount()) {
                    seen = true;
                    assertEquals(negative ? -1f : 1f, got.sample((int) idx));
                }
            } else {
                for (int x = 0; x < 160; x++) {
                    if (boundary(axis, x) <= impulseFrame && impulseFrame < boundary(axis, x + 1)) {
                        seen = true;
                        if (negative) {
                            assertEquals(-1f, got.min(x), "fpp " + fpp + " mode " + got.mode());
                        } else {
                            assertEquals(1f, got.max(x), "fpp " + fpp + " mode " + got.mode());
                        }
                    }
                }
            }
            assertTrue(seen || impulseFrame < scroll || impulseFrame >= axis.frameAtX(160),
                    "impulse should be inside the view");
        }
    }

    // ------------------------------------------------------------ arguments and reuse

    @Test
    void reusedResultIsFullyOverwritten() {
        Random r = new Random(21);
        MemorySampleSource s = random(r, 1, 10_000);
        List<Clip> clips = List.of(new Clip(s, 0, 10_000, 0));
        LaneSampler sampler = new LaneSampler();
        LaneColumns reused = new LaneColumns();
        sampler.sample(clips, 0, NOT_READY, new TimeAxis(RATE, 20, 0), 400, reused);
        TimeAxis axis = new TimeAxis(RATE, 30, 500);
        sampler.sample(clips, 0, NOT_READY, axis, 200, reused);
        LaneColumns fresh = sampler.sample(clips, 0, NOT_READY, axis, 200);
        assertEquals(200, reused.width());
        for (int x = 0; x < 200; x++) {
            assertEquals(fresh.min(x), reused.min(x));
            assertEquals(fresh.max(x), reused.max(x));
            assertEquals(fresh.rms(x), reused.rms(x));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> reused.min(200));
    }

    @Test
    void argumentChecks() {
        MemorySampleSource mono = MemorySampleSource.adopt(RATE, new float[][] {new float[100]});
        List<Clip> clips = List.of(new Clip(mono, 0, 100, 0));
        LaneSampler sampler = new LaneSampler();
        TimeAxis axis = new TimeAxis(RATE, 10, 0);
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(clips, 1, NOT_READY, axis, 10));
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(clips, -1, NOT_READY, axis, 10));
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(clips, 0, NOT_READY, axis, 0));
        assertThrows(NullPointerException.class, () -> sampler.sample(null, 0, NOT_READY, axis, 10));
        assertThrows(NullPointerException.class, () -> sampler.sample(clips, 0, null, axis, 10));
        assertThrows(NullPointerException.class, () -> sampler.sample(clips, 0, NOT_READY, null, 10));
    }
}
