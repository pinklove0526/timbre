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
package com.jellyfish.timbre.audio.support;

import com.jellyfish.timbre.model.SampleSource;
import java.util.SplittableRandom;

/** Test signal generators and independent sample packing helpers. */
public final class Samples {

    private Samples() {
    }

    /** @return a sine of {@code frames} samples with {@code cycles} periods, amplitude 0.9 */
    public static float[] sine(int frames, double cycles) {
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (float) (0.9 * Math.sin(2 * Math.PI * cycles * i / frames));
        }
        return out;
    }

    /** @return zeros with a 1.0 at {@code at} */
    public static float[] impulse(int frames, int at) {
        float[] out = new float[frames];
        out[at] = 1f;
        return out;
    }

    /** @return a linear ramp from -1 (inclusive) towards 1 (exclusive) */
    public static float[] ramp(int frames) {
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = -1f + 2f * i / frames;
        }
        return out;
    }

    /** @return seeded pseudo-random floats in [-1, 1) */
    public static float[] noise(long seed, int frames) {
        SplittableRandom r = new SplittableRandom(seed);
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            out[i] = (float) (r.nextDouble() * 2 - 1);
        }
        return out;
    }

    /** @return seeded pseudo-random signed integers spanning the full {@code bits}-bit range */
    public static int[] intNoise(long seed, int count, int bits) {
        SplittableRandom r = new SplittableRandom(seed);
        long min = -(1L << (bits - 1));
        long span = 1L << bits;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = (int) (min + (r.nextLong() >>> 1) % span);
        }
        return out;
    }

    /** @return the float that a decoder must produce for signed {@code bits}-bit value {@code v} */
    public static float toFloat(int bits, int v) {
        return (float) v / (float) (1L << (bits - 1));
    }

    /** @return signed little-endian packing of {@code v}, {@code bits} in 16, 24 or 32 */
    public static byte[] packSigned(int bits, int[] v) {
        int n = bits / 8;
        byte[] out = new byte[v.length * n];
        for (int i = 0; i < v.length; i++) {
            for (int b = 0; b < n; b++) {
                out[i * n + b] = (byte) (v[i] >> (8 * b));
            }
        }
        return out;
    }

    /** @return the values 0..255 as raw unsigned bytes */
    public static byte[] pack8(int[] v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    /** @return little-endian IEEE float bytes */
    public static byte[] packFloats(float[] v) {
        int[] bits = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            bits[i] = Float.floatToRawIntBits(v[i]);
        }
        return packSigned(32, bits);
    }

    /** Interleaves per-channel arrays frame by frame. */
    public static int[] interleave(int[]... channels) {
        int frames = channels[0].length;
        int[] out = new int[frames * channels.length];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels.length; c++) {
                out[f * channels.length + c] = channels[c][f];
            }
        }
        return out;
    }

    /** Interleaves per-channel float arrays frame by frame. */
    public static float[] interleave(float[]... channels) {
        int frames = channels[0].length;
        float[] out = new float[frames * channels.length];
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels.length; c++) {
                out[f * channels.length + c] = channels[c][f];
            }
        }
        return out;
    }

    /** Reads a whole source into planar arrays. */
    public static float[][] readAll(SampleSource source) {
        int frames = Math.toIntExact(source.frameCount());
        float[][] out = new float[source.channelCount()][frames];
        source.read(0, frames, out, 0);
        return out;
    }
}
