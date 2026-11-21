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
package com.jellyfish.timbre.audio.probe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Hand-written asymmetric literals. The 6-frame stereo block is L = {32767, -32768, 16384, -8192, 0, 100},
 * R = {-16384, 8192, 0, 0, 32767, -1}; the dB values were computed independently (Python, double precision):
 * L peak 0.0, L RMS -4.14079222441001, R peak -0.00026507636037961915, R RMS -6.600721341448409.
 */
class CaptureMathTest {

    private static final short[] L = {32767, -32768, 16384, -8192, 0, 100};
    private static final short[] R = {-16384, 8192, 0, 0, 32767, -1};

    private static byte[] interleave(short[] l, short[] r) {
        byte[] b = new byte[l.length * 4];
        for (int i = 0; i < l.length; i++) {
            b[4 * i] = (byte) l[i];
            b[4 * i + 1] = (byte) (l[i] >> 8);
            b[4 * i + 2] = (byte) r[i];
            b[4 * i + 3] = (byte) (r[i] >> 8);
        }
        return b;
    }

    @Test
    void pcmToFloatScalesLikeThePlaybackSide() {
        float[][] out = new float[2][6];
        CaptureMath.pcm16LeToFloat(interleave(L, R), 0, 6, 2, out);
        assertArrayEquals(new float[] {32767f / 32768f, -1.0f, 0.5f, -0.25f, 0f, 100f / 32768f}, out[0], 0f);
        assertArrayEquals(new float[] {-0.5f, 0.25f, 0f, 0f, 32767f / 32768f, -1f / 32768f}, out[1], 0f);
    }

    @Test
    void anOffsetAndMonoWork() {
        byte[] b = {9, 9, 0x00, (byte) 0x80, (byte) 0xFF, 0x7F}; // two junk bytes, then -32768 and 32767
        float[][] out = new float[1][2];
        CaptureMath.pcm16LeToFloat(b, 2, 2, 1, out);
        assertArrayEquals(new float[] {-1.0f, 32767f / 32768f}, out[0], 0f);
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.pcm16LeToFloat(b, 2, 3, 1, out));
    }

    @Test
    void peakAndRmsOfTheAsymmetricBlock() {
        float[][] out = new float[2][6];
        CaptureMath.pcm16LeToFloat(interleave(L, R), 0, 6, 2, out);
        assertEquals(0.0, CaptureMath.peakDbfs(out[0], 0, 6), 1e-9, "the -32768 sample is the peak");
        assertEquals(-4.14079222441001, CaptureMath.rmsDbfs(out[0], 0, 6), 1e-6);
        assertEquals(-0.00026507636037961915, CaptureMath.peakDbfs(out[1], 0, 6), 1e-9);
        assertEquals(-6.600721341448409, CaptureMath.rmsDbfs(out[1], 0, 6), 1e-6);
    }

    @Test
    void halfScaleAndDigitalSilence() {
        float[] half = {0.5f, -0.25f};
        assertEquals(-6.020599913279624, CaptureMath.peakDbfs(half, 0, 2), 1e-9);
        assertEquals(-8.061799739838872, CaptureMath.rmsDbfs(half, 0, 2), 1e-9);
        assertEquals(Double.NEGATIVE_INFINITY, CaptureMath.peakDbfs(new float[4], 0, 4));
        assertEquals(Double.NEGATIVE_INFINITY, CaptureMath.rmsDbfs(new float[4], 0, 4));
        assertEquals(Double.NEGATIVE_INFINITY, CaptureMath.rmsDbfs(half, 1, 1), "empty window");
        assertEquals(Double.NEGATIVE_INFINITY, CaptureMath.dbfs(0));
    }

    @Test
    void nearestRankPercentile() {
        double[] ten = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5.0, CaptureMath.percentile(ten, 50), 0.0);
        assertEquals(9.0, CaptureMath.percentile(ten, 90), 0.0);
        assertEquals(10.0, CaptureMath.percentile(ten, 95), 0.0);
        assertEquals(1.0, CaptureMath.percentile(ten, 0), 0.0);
        assertEquals(10.0, CaptureMath.percentile(ten, 100), 0.0);
        assertEquals(23.2, CaptureMath.percentile(new double[] {23.2}, 95), 0.0);
        assertEquals(3.5, CaptureMath.percentile(new double[] {1.5, 3.5, 9.0}, 50), 0.0);
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.percentile(new double[0], 50));
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.percentile(ten, 101));
    }

    // ------------------------------------------------------------------ diag aggregates
    // L = {32767, -32768, 16384, -8192, 0, 100} / 32768; R = {-16384, 8192, 0, 0, 32767, -1} / 32768.
    // DC means (Python, double): L 0.04217020670572917, R 0.1249898274739583.
    // Signs (0 counts as non-negative): L + - + - + + -> 4 crossings; R - + + + + - -> 2 crossings.
    // Full scale (|v| >= 0.999): L 2 of 6 (32767/32768 and -1), R 1 of 6.
    // Histogram buckets floor(|v| * 8), capped at 7: L {0, 0.003} -> 0, 0.25 -> 2, 0.5 -> 4, 0.99997 and 1 -> 7;
    // R {0, 0, 3e-5} -> 0, 0.25 -> 2, 0.5 -> 4, 0.99997 -> 7.

    private static float[][] block() {
        float[][] out = new float[2][6];
        CaptureMath.pcm16LeToFloat(interleave(L, R), 0, 6, 2, out);
        return out;
    }

    @Test
    void dcMeanAndFullScaleShare() {
        float[][] b = block();
        assertEquals(0.04217020670572917, CaptureMath.dcMean(b[0], 0, 6), 1e-7);
        assertEquals(0.1249898274739583, CaptureMath.dcMean(b[1], 0, 6), 1e-7);
        assertEquals(2.0 / 6, CaptureMath.fullScaleShare(b[0], 0, 6), 1e-12);
        assertEquals(1.0 / 6, CaptureMath.fullScaleShare(b[1], 0, 6), 1e-12);
        assertEquals(0.0, CaptureMath.dcMean(b[0], 3, 3), 0.0, "empty window");
        assertEquals(-0.75, CaptureMath.dcMean(new float[] {-1f, -0.5f}, 0, 2), 0.0, "a negative DC");
    }

    @Test
    void zeroCrossings() {
        float[][] b = block();
        assertEquals(4, CaptureMath.zeroCrossings(b[0], 0, 6));
        assertEquals(2, CaptureMath.zeroCrossings(b[1], 0, 6));
        assertEquals(0, CaptureMath.zeroCrossings(new float[] {0.2f, 0f, 0.9f, 0.1f, 0f}, 0, 5), "all non-negative");
        assertEquals(5, CaptureMath.zeroCrossings(new float[] {0.1f, -0.1f, 0.3f, -0.2f, 0.5f, -0.4f}, 0, 6),
                "alternating: the maximum n - 1");
        assertEquals(1, CaptureMath.zeroCrossings(new float[] {-0.3f, -0.1f, -0.2f, 0.6f}, 0, 4), "one upward");
        assertEquals(1, CaptureMath.zeroCrossings(new float[] {0.3f, 0.1f, -0.2f, -0.6f}, 0, 4), "one downward");
        assertEquals(4410.0, CaptureMath.perSecond(441, 4410, 44100), 1e-9);
        assertEquals(30.0, CaptureMath.perSecond(3, 4800, 48000), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> CaptureMath.perSecond(1, 0, 44100));
    }

    @Test
    void histogramOfEightBuckets() {
        float[][] b = block();
        assertArrayEquals(new int[] {2, 0, 1, 0, 1, 0, 0, 2}, CaptureMath.histogram8(b[0], 0, 6));
        assertArrayEquals(new int[] {3, 0, 1, 0, 1, 0, 0, 1}, CaptureMath.histogram8(b[1], 0, 6));
        assertArrayEquals(new int[] {0, 1, 0, 1, 0, 0, 1, 1},
                CaptureMath.histogram8(new float[] {0.125f, -0.4f, 0.80f, 1.5f}, 0, 4),
                "0.125 starts bucket 1, -0.4 -> 3, 0.8 -> 6, 1.5 is capped into 7");
    }
}
