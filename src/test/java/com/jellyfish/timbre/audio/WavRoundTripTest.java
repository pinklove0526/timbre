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
package com.jellyfish.timbre.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.RiffBuilder;
import com.jellyfish.timbre.audio.support.Samples;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WavRoundTripTest {

    @TempDir
    Path dir;

    private WavFile roundTrip(WavEncoding enc, MemorySampleSource src) throws IOException {
        Path p = dir.resolve("rt.wav");
        WavWriter.write(p, src, enc);
        return WavReader.read(p);
    }

    private static void assertBitExact(float[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int c = 0; c < expected.length; c++) {
            assertEquals(expected[c].length, actual[c].length);
            for (int i = 0; i < expected[c].length; i++) {
                assertEquals(Float.floatToRawIntBits(expected[c][i]), Float.floatToRawIntBits(actual[c][i]),
                        "ch " + c + " frame " + i);
            }
        }
    }

    @Test
    void eightBitAllCodePointsExact() throws IOException {
        float[] v = new float[256];
        for (int i = 0; i < 256; i++) {
            v[i] = (i - 128) / 128f;
        }
        WavFile f = roundTrip(WavEncoding.PCM_8, MemorySampleSource.copyOf(8000, new float[][] {v}));
        assertEquals(WavEncoding.PCM_8, f.encoding());
        assertBitExact(new float[][] {v}, Samples.readAll(f.source()));
    }

    @Test
    void sixteenBitAllCodePointsExact() throws IOException {
        float[] v = new float[65536];
        for (int i = 0; i < v.length; i++) {
            v[i] = (i - 32768) / 32768f;
        }
        WavFile f = roundTrip(WavEncoding.PCM_16, MemorySampleSource.copyOf(44100, new float[][] {v}));
        assertBitExact(new float[][] {v}, Samples.readAll(f.source()));
    }

    @Test
    void twentyFourBitBoundaryAndRandomValuesExact() throws IOException {
        int[] fixed = {-8388608, -8388607, -1, 0, 1, 8388606, 8388607};
        int[] rnd = Samples.intNoise(42, 20000, 24);
        float[] v = new float[fixed.length + rnd.length];
        for (int i = 0; i < v.length; i++) {
            v[i] = Samples.toFloat(24, i < fixed.length ? fixed[i] : rnd[i - fixed.length]);
        }
        WavFile f = roundTrip(WavEncoding.PCM_24, MemorySampleSource.copyOf(48000, new float[][] {v}));
        assertBitExact(new float[][] {v}, Samples.readAll(f.source()));
    }

    @Test
    void float32IsBitExactIncludingNegativeZeroAndSpecials() throws IOException {
        float[] special = {-0.0f, 0.0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN,
            Float.intBitsToFloat(0x7FC12345), Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.5f, -3.25f};
        float[] noise = Samples.noise(9, 5000);
        float[] v = new float[special.length + noise.length];
        System.arraycopy(special, 0, v, 0, special.length);
        System.arraycopy(noise, 0, v, special.length, noise.length);
        WavFile f = roundTrip(WavEncoding.FLOAT_32, MemorySampleSource.copyOf(96000, new float[][] {v}));
        assertEquals(WavEncoding.FLOAT_32, f.encoding());
        assertBitExact(new float[][] {v}, Samples.readAll(f.source()));
    }

    /**
     * 32-bit PCM is not lossless through float32 (24-bit mantissa). The int -> float -> int trip may
     * differ by at most half a float32 ulp of the original value (rounding to nearest float) plus the
     * clamp at +2^31 (a float 2^31 maps back to 2^31 - 1); this test allows one ulp of the float value.
     */
    @Test
    void thirtyTwoBitPcmIsWithinFloat32Tolerance() throws IOException {
        int[] fixed = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE,
            16777217, 16777216, -16777217};
        int[] rnd = Samples.intNoise(5, 20000, 32);
        int[] all = new int[fixed.length + rnd.length];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        System.arraycopy(rnd, 0, all, fixed.length, rnd.length);
        byte[] in = RiffBuilder.wave().fmt(1, 1, 44100, 32).data(Samples.packSigned(32, all)).build();
        WavFile read = WavReader.read(new java.io.ByteArrayInputStream(in));
        Path out = dir.resolve("out32.wav");
        WavWriter.write(out, read.source(), WavEncoding.PCM_32);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(out)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < all.length; i++) {
            long got = b.getInt(44 + 4 * i);
            double tolerance = Math.ulp((float) all[i]);
            assertTrue(Math.abs(got - (long) all[i]) <= tolerance,
                    "index " + i + ": " + all[i] + " -> " + got + " (tolerance " + tolerance + ")");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {8000, 44100, 48000, 96000})
    void sampleRatesArePreserved(int rate) throws IOException {
        WavFile f = roundTrip(WavEncoding.PCM_16, MemorySampleSource.copyOf(rate,
                new float[][] {Samples.sine(100, 3)}));
        assertEquals(rate, f.source().sampleRate());
    }

    @Test
    void threeChannel24BitIsExact() throws IOException {
        float[][] ch = new float[3][];
        for (int c = 0; c < 3; c++) {
            int[] ints = Samples.intNoise(100 + c, 500, 24);
            ch[c] = new float[500];
            for (int i = 0; i < 500; i++) {
                ch[c][i] = Samples.toFloat(24, ints[i]);
            }
        }
        WavFile f = roundTrip(WavEncoding.PCM_24, MemorySampleSource.copyOf(48000, ch));
        assertEquals(3, f.source().channelCount());
        assertBitExact(ch, Samples.readAll(f.source()));
    }

    @Test
    void sixChannelFloatIsExact() throws IOException {
        float[][] ch = new float[6][];
        for (int c = 0; c < 6; c++) {
            ch[c] = Samples.noise(200 + c, 300);
        }
        WavFile f = roundTrip(WavEncoding.FLOAT_32, MemorySampleSource.copyOf(44100, ch));
        assertEquals(6, f.source().channelCount());
        assertBitExact(ch, Samples.readAll(f.source()));
    }
}
