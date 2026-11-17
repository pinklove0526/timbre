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
package com.jellyfish.timbre.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PeakPyramidTest {

    private static MemorySampleSource mono(float[] data) {
        return MemorySampleSource.adopt(44100, new float[][] {data});
    }

    // ---------------------------------------------------------------- (a) known answer

    @Test
    void knownAnswerSixHundredFrames() {
        float[] d = new float[600];
        Arrays.fill(d, 0, 256, 0.5f);
        Arrays.fill(d, 256, 512, -0.5f);
        Arrays.fill(d, 512, 600, 0.25f);
        d[10] = -0.25f;
        d[200] = 0.75f;
        d[300] = 0.125f;
        d[400] = -0.875f;
        d[599] = -0.5f;
        PeakPyramid p = PeakPyramid.build(mono(d));

        assertEquals(2, p.levelCount());
        assertEquals(1, p.channelCount());
        assertEquals(600, p.frameCount());
        assertEquals(3, p.blockCount(0));
        assertEquals(1, p.blockCount(1));
        assertEquals(256, p.blockFrames(0));
        assertEquals(1024, p.blockFrames(1));
        assertEquals(256, p.framesInBlock(0, 0));
        assertEquals(256, p.framesInBlock(0, 1));
        assertEquals(88, p.framesInBlock(0, 2));
        assertEquals(600, p.framesInBlock(1, 0));

        // literal values: 254 * 0.25 + 0.0625 + 0.5625 = 64.125 ; 254 * 0.25 + 0.015625 + 0.765625 = 64.28125
        // 87 * 0.0625 + 0.25 = 5.6875
        assertEquals(-0.25f, p.blockMin(0, 0, 0));
        assertEquals(0.75f, p.blockMax(0, 0, 0));
        assertEquals(64.125, p.blockSumSquares(0, 0, 0));
        assertEquals(-0.875f, p.blockMin(0, 0, 1));
        assertEquals(0.125f, p.blockMax(0, 0, 1));
        assertEquals(64.28125, p.blockSumSquares(0, 0, 1));
        assertEquals(-0.5f, p.blockMin(0, 0, 2));
        assertEquals(0.25f, p.blockMax(0, 0, 2));
        assertEquals(5.6875, p.blockSumSquares(0, 0, 2));

        assertEquals(-0.875f, p.blockMin(0, 1, 0));
        assertEquals(0.75f, p.blockMax(0, 1, 0));
        assertEquals(134.09375, p.blockSumSquares(0, 1, 0));

        float[] out = new float[3];
        p.summarize(0, 1, 0, 1, out);
        assertEquals(-0.875f, out[0]);
        assertEquals(0.75f, out[1]);
        assertEquals((float) Math.sqrt(134.09375 / 600), out[2]);
        // blocks 1 and 2 of level 0: 256 + 88 = 344 frames
        p.summarize(0, 0, 1, 3, out);
        assertEquals(-0.875f, out[0]);
        assertEquals(0.25f, out[1]);
        assertEquals((float) Math.sqrt((64.28125 + 5.6875) / 344), out[2]);
    }

    @Test
    void emptyAndTinySources() {
        PeakPyramid empty = PeakPyramid.build(mono(new float[0]));
        assertEquals(0, empty.levelCount());
        assertEquals(0, empty.frameCount());
        assertEquals(-1, empty.levelFor(1e9));
        // no levels: level 0 does not exist
        assertThrows(IndexOutOfBoundsException.class, () -> empty.summarize(0, 0, 0, 1, new float[3]));
        PeakPyramid one = PeakPyramid.build(mono(new float[] {-0.5f}));
        assertEquals(1, one.levelCount());
        assertEquals(1, one.blockCount(0));
        assertEquals(1, one.framesInBlock(0, 0));
        assertEquals(-0.5f, one.blockMin(0, 0, 0));
        assertEquals(-0.5f, one.blockMax(0, 0, 0));
        assertEquals(0.25, one.blockSumSquares(0, 0, 0));
    }

    // ---------------------------------------------------------------- (b) brute force

    private static final int[] LENGTHS = {0, 1, 2, 255, 256, 257, 511, 512, 513, 1023, 1024, 1025, 1026, 4095, 4097,
        5000, 16385, 20000};

    private static float[][] randomData(Random r, int channels, int frames) {
        float[][] d = new float[channels][frames];
        for (int c = 0; c < channels; c++) {
            double scale = 0.1 + r.nextDouble();
            for (int i = 0; i < frames; i++) {
                d[c][i] = (float) ((r.nextDouble() * 2 - 1) * scale);
            }
        }
        return d;
    }

    /** Independent reference: aggregates the frames of the block range directly from the samples. */
    private static double[] reference(float[] data, int level, long firstBlock, long endBlock) {
        long bf = 256;
        for (int i = 0; i < level; i++) {
            bf *= 4;
        }
        long blocks = (data.length + bf - 1) / bf;
        long first = Math.max(0, firstBlock);
        long end = Math.min(blocks, endBlock);
        if (first >= end) {
            return new double[] {0, 0, 0, 0};
        }
        long from = first * bf;
        long to = Math.min(end * bf, data.length);
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (long i = from; i < to; i++) {
            lo = Math.min(lo, data[(int) i]);
            hi = Math.max(hi, data[(int) i]);
            sum += (double) data[(int) i] * data[(int) i];
        }
        return new double[] {lo, hi, sum, to - from};
    }

    private static int expectedLevels(int frames) {
        if (frames == 0) {
            return 0;
        }
        int levels = 0;
        long bf = 256;
        while (true) {
            levels++;
            if ((frames + bf - 1) / bf == 1) {
                return levels;
            }
            bf *= 4;
        }
    }

    private static void assertClose(double expected, double actual, String what) {
        double tol = 1e-9 * Math.max(1.0, Math.abs(expected));
        assertEquals(expected, actual, tol, what);
    }

    @Test
    void everyLevelAndBlockMatchesBruteForce() {
        Random r = new Random(12345);
        for (int channels : new int[] {1, 2, 6}) {
            for (int frames : LENGTHS) {
                float[][] data = randomData(r, channels, frames);
                PeakPyramid p = PeakPyramid.build(MemorySampleSource.copyOf(48000, data));
                String id = channels + "ch/" + frames;
                assertEquals(expectedLevels(frames), p.levelCount(), id);
                assertEquals(channels, p.channelCount(), id);
                for (int level = 0; level < p.levelCount(); level++) {
                    long bf = 256L << (2 * level);
                    assertEquals(bf, p.blockFrames(level), id);
                    assertEquals((frames + bf - 1) / bf, p.blockCount(level), id);
                    for (int c = 0; c < channels; c++) {
                        for (int b = 0; b < p.blockCount(level); b++) {
                            double[] ref = reference(data[c], level, b, b + 1);
                            String w = id + " c" + c + " L" + level + " b" + b;
                            assertEquals((float) ref[0], p.blockMin(c, level, b), w);
                            assertEquals((float) ref[1], p.blockMax(c, level, b), w);
                            assertClose(ref[2], p.blockSumSquares(c, level, b), w);
                            assertEquals((long) ref[3], p.framesInBlock(level, b), w);
                        }
                    }
                }
            }
        }
    }

    @Test
    void randomBlockRangesMatchBruteForce() {
        Random r = new Random(777);
        float[] out = new float[3];
        for (int channels : new int[] {1, 2, 6}) {
            for (int frames : new int[] {1, 257, 1025, 4097, 20000, 70000}) {
                float[][] data = randomData(r, channels, frames);
                PeakPyramid p = PeakPyramid.build(MemorySampleSource.copyOf(48000, data));
                for (int iter = 0; iter < 200; iter++) {
                    int c = r.nextInt(channels);
                    int level = r.nextInt(p.levelCount());
                    int count = p.blockCount(level);
                    long a = r.nextInt(count + 6) - 3;
                    long b = r.nextInt(count + 6) - 3;
                    long first = Math.min(a, b);
                    long end = Math.max(a, b);
                    String w = channels + "ch/" + frames + " c" + c + " L" + level + " [" + first + "," + end + ")";
                    double[] ref = reference(data[c], level, first, end);
                    p.summarize(c, level, first, end, out);
                    if (ref[3] == 0) {
                        assertEquals(0f, out[0], w);
                        assertEquals(0f, out[1], w);
                        assertEquals(0f, out[2], w);
                    } else {
                        assertEquals((float) ref[0], out[0], w);
                        assertEquals((float) ref[1], out[1], w);
                        assertEquals((float) Math.sqrt(ref[2] / ref[3]), out[2], 2e-7f * out[2], w); // float output
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- (c) levelFor

    @Test
    void levelForThresholds() {
        PeakPyramid p = PeakPyramid.build(mono(new float[100_000]));
        assertEquals(6, p.levelCount());
        assertEquals(-1, p.levelFor(0.5));
        assertEquals(-1, p.levelFor(511.99));
        assertEquals(0, p.levelFor(512));
        assertEquals(0, p.levelFor(2047.99));
        assertEquals(1, p.levelFor(2048));
        assertEquals(1, p.levelFor(8191.99));
        assertEquals(2, p.levelFor(8192));
        assertEquals(3, p.levelFor(32768));
        assertEquals(5, p.levelFor(1e12), "past the top is clamped to the top");
        PeakPyramid shortOne = PeakPyramid.build(mono(new float[100]));
        assertEquals(1, shortOne.levelCount());
        assertEquals(0, shortOne.levelFor(1e9));
        assertEquals(-1, shortOne.levelFor(100));
        assertThrows(IllegalArgumentException.class, () -> p.levelFor(0));
        assertThrows(IllegalArgumentException.class, () -> p.levelFor(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> p.levelFor(-3));
    }

    // ---------------------------------------------------------------- (d) sanitising

    @Test
    void nonFiniteSamplesAreSanitised() {
        float[] d = new float[512];
        d[0] = Float.NaN;
        d[1] = 0.5f;
        d[2] = Float.POSITIVE_INFINITY;
        d[3] = Float.NEGATIVE_INFINITY;
        d[4] = 0.25f;
        Arrays.fill(d, 256, 512, Float.NaN);
        PeakPyramid p = PeakPyramid.build(mono(d));
        assertEquals(-Float.MAX_VALUE, p.blockMin(0, 0, 0));
        assertEquals(Float.MAX_VALUE, p.blockMax(0, 0, 0));
        double big = (double) Float.MAX_VALUE * Float.MAX_VALUE;
        double expected = 2 * big + 0.25 + 0.0625;
        assertTrue(Double.isFinite(p.blockSumSquares(0, 0, 0)));
        assertEquals(expected, p.blockSumSquares(0, 0, 0), expected * 1e-12);
        assertEquals(0f, p.blockMin(0, 0, 1));
        assertEquals(0f, p.blockMax(0, 0, 1));
        assertEquals(0.0, p.blockSumSquares(0, 0, 1));
        assertEquals(Float.MAX_VALUE, p.blockMax(0, 1, 0));
        assertEquals(-Float.MAX_VALUE, p.blockMin(0, 1, 0));
    }

    // ---------------------------------------------------------------- (e) impulses

    @Test
    void aFullScaleImpulseIsNeverLost() {
        Random r = new Random(99);
        for (int iter = 0; iter < 60; iter++) {
            int frames = 1 + r.nextInt(300_000);
            int pos = r.nextInt(frames);
            boolean negative = r.nextBoolean();
            float[] d = new float[frames];
            d[pos] = negative ? -1f : 1f;
            PeakPyramid p = PeakPyramid.build(mono(d));
            float[] out = new float[3];
            for (int level = 0; level < p.levelCount(); level++) {
                int block = (int) (pos / p.blockFrames(level));
                String w = "frames=" + frames + " pos=" + pos + " L" + level;
                if (negative) {
                    assertEquals(-1f, p.blockMin(0, level, block), w);
                } else {
                    assertEquals(1f, p.blockMax(0, level, block), w);
                }
                p.summarize(0, level, block, block + 1, out);
                assertEquals(negative ? -1f : 0f, out[0], w);
                assertEquals(negative ? 0f : 1f, out[1], w);
                for (int b = 0; b < p.blockCount(level); b++) {
                    if (b != block) {
                        assertEquals(0f, p.blockMax(0, level, b), w);
                        assertEquals(0f, p.blockMin(0, level, b), w);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- misc

    @Test
    void argumentChecks() {
        PeakPyramid p = PeakPyramid.build(mono(new float[600]));
        assertThrows(NullPointerException.class, () -> PeakPyramid.build(null));
        assertThrows(IndexOutOfBoundsException.class, () -> p.blockCount(2));
        assertThrows(IndexOutOfBoundsException.class, () -> p.blockCount(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> p.blockMin(1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> p.blockMin(0, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> p.summarize(0, 0, 0, 1, new float[2]));
    }
}
