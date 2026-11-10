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

import com.jellyfish.timbre.audio.support.RiffBuilder;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Known-answer tests. The data payloads are hand-written byte literals made of
 * distinct bytes (so any byte-order or channel-order mistake changes the result)
 * and the expected floats are computed here with plain arithmetic. Nothing in the
 * expectations comes from {@link WavWriter} or any codec helper; the RIFF header
 * comes from the independent test builder.
 */
class WavKnownAnswerTest {

    @TempDir
    Path dir;

    // ---------------------------------------------------------------- helpers

    /** Converts int literals to bytes (so literals above 0x7F are readable). */
    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private float[][] decode(byte[] file) throws IOException {
        Path p = dir.resolve("in.wav");
        Files.write(p, file);
        WavFile wav = WavReader.read(p);
        MemorySampleSource s = wav.source();
        float[][] out = new float[s.channelCount()][(int) s.frameCount()];
        s.read(0, (int) s.frameCount(), out, 0);
        return out;
    }

    private static void assertExact(float[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length, "channel count");
        for (int c = 0; c < expected.length; c++) {
            assertArrayEquals(expected[c], actual[c], 0f, "channel " + c);
        }
    }

    /** Writes the samples and returns the payload of the data chunk, walking the chunks by hand. */
    private byte[] writtenDataPayload(float[][] samples, WavEncoding enc) throws IOException {
        Path p = dir.resolve("out.wav");
        WavWriter.write(p, MemorySampleSource.copyOf(44100, samples), enc);
        byte[] f = Files.readAllBytes(p);
        int pos = 12;
        while (pos + 8 <= f.length) {
            String id = new String(f, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long size = (f[pos + 4] & 0xFFL) | ((f[pos + 5] & 0xFFL) << 8)
                    | ((f[pos + 6] & 0xFFL) << 16) | ((f[pos + 7] & 0xFFL) << 24);
            if (id.equals("data")) {
                return Arrays.copyOfRange(f, pos + 8, pos + 8 + (int) size);
            }
            pos += 8 + (int) size + (int) (size & 1);
        }
        throw new AssertionError("no data chunk");
    }

    private static float[][] mono(float... v) {
        return new float[][] {v};
    }

    // ---------------------------------------------------------------- decode

    @Test
    void decode16BitDistinctBytes() throws IOException {
        // 0x1234 and 0xFEDC (= -292), little-endian: 34 12, DC FE
        byte[] file = RiffBuilder.wave().fmt(1, 1, 44100, 16).data(b(0x34, 0x12, 0xDC, 0xFE)).build();
        assertExact(mono(4660 / 32768f, -292 / 32768f), decode(file));
    }

    @Test
    void decode24BitDistinctBytes() throws IOException {
        // 0x123456 and 0xFEDCBA (= -74566): 56 34 12, BA DC FE
        byte[] file = RiffBuilder.wave().fmt(1, 1, 44100, 24).data(b(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE)).build();
        assertExact(mono(1193046 / 8388608f, -74566 / 8388608f), decode(file));
    }

    @Test
    void decode32BitDistinctBytes() throws IOException {
        // 0x12345678 and -0x12345678 (= 0xEDCBA988): 78 56 34 12, 88 A9 CB ED
        byte[] file = RiffBuilder.wave().fmt(1, 1, 44100, 32)
                .data(b(0x78, 0x56, 0x34, 0x12, 0x88, 0xA9, 0xCB, 0xED)).build();
        int v = 0x12345678;
        assertExact(mono((float) v / 2147483648f, (float) -v / 2147483648f), decode(file));
    }

    @Test
    void decode8BitUnsignedNonSymmetricValues() throws IOException {
        // unsigned bytes: 0x00, 0x01, 0x40, 0x7F, 0x80, 0x81, 0xC3, 0xFF
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 8)
                .data(b(0x00, 0x01, 0x40, 0x7F, 0x80, 0x81, 0xC3, 0xFF)).build();
        assertExact(mono(-128 / 128f, -127 / 128f, -64 / 128f, -1 / 128f, 0f, 1 / 128f, 67 / 128f, 127 / 128f),
                decode(file));
    }

    @Test
    void decodeFloat32DistinctBytes() throws IOException {
        // 0x3E800000 = 0.25 -> 00 00 80 3E ; 0xBF000000 = -0.5 -> 00 00 00 BF
        byte[] file = RiffBuilder.wave().fmt(3, 1, 44100, 32).data(b(0x00, 0x00, 0x80, 0x3E, 0x00, 0x00, 0x00, 0xBF))
                .build();
        assertExact(mono(0.25f, -0.5f), decode(file));
    }

    @Test
    void decodeStereo16PinsChannelOrder() throws IOException {
        // frame 0: L=0x1234 R=0xFEDC ; frame 1: L=0x0102 R=0x0304
        byte[] file = RiffBuilder.wave().fmt(1, 2, 44100, 16)
                .data(b(0x34, 0x12, 0xDC, 0xFE, 0x02, 0x01, 0x04, 0x03)).build();
        assertExact(new float[][] {{4660 / 32768f, 258 / 32768f}, {-292 / 32768f, 772 / 32768f}}, decode(file));
    }

    @Test
    void decodeThreeChannel24BitPinsChannelOrder() throws IOException {
        // frame 0: 0x010203, 0x0A0B0C, 0xF0E0D0 (negative) ; frame 1: 0x112233, 0x445566, 0x778899
        byte[] file = RiffBuilder.wave().fmtExtensible(1, 3, 48000, 24, 24, 0)
                .data(b(0x03, 0x02, 0x01, 0x0C, 0x0B, 0x0A, 0xD0, 0xE0, 0xF0,
                        0x33, 0x22, 0x11, 0x66, 0x55, 0x44, 0x99, 0x88, 0x77))
                .build();
        float d = 8388608f;
        assertExact(new float[][] {
                {66051 / d, 1122867 / d},
                {658188 / d, 4478310 / d},
                {(0xF0E0D0 - 0x1000000) / d, 7833753 / d}},
                decode(file));
    }

    @Test
    void decodeExtensible24ValidBitsLeftJustifiedIn32BitContainer() throws IOException {
        // low byte is padding: 00 56 34 12 -> 0x12345600 ; 00 BA DC FE -> 0xFEDCBA00 (negative)
        byte[] file = RiffBuilder.wave().fmtExtensible(1, 1, 44100, 32, 24, 4)
                .data(b(0x00, 0x56, 0x34, 0x12, 0x00, 0xBA, 0xDC, 0xFE)).build();
        assertExact(mono(0x123456 / 8388608f, -74566 / 8388608f), decode(file));
    }

    // ---------------------------------------------------------------- encode

    @Test
    void write16BitProducesExactBytes() throws IOException {
        assertArrayEquals(b(0x34, 0x12, 0xDC, 0xFE),
                writtenDataPayload(mono(4660 / 32768f, -292 / 32768f), WavEncoding.PCM_16));
    }

    @Test
    void write24BitProducesExactBytes() throws IOException {
        assertArrayEquals(b(0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE),
                writtenDataPayload(mono(1193046 / 8388608f, -74566 / 8388608f), WavEncoding.PCM_24));
    }

    @Test
    void write32BitProducesExactBytes() throws IOException {
        // 0x12345678 is not exactly representable as float32; 0x12345680 is (24 significant bits).
        int v = 0x12345680;
        assertEquals(v, (int) (float) v, "test value must be exact in float32");
        assertArrayEquals(b(0x80, 0x56, 0x34, 0x12, 0x80, 0xA9, 0xCB, 0xED),
                writtenDataPayload(mono((float) v / 2147483648f, (float) -v / 2147483648f), WavEncoding.PCM_32));
    }

    @Test
    void write8BitProducesExactBytes() throws IOException {
        assertArrayEquals(b(0x00, 0x01, 0x40, 0x7F, 0x80, 0x81, 0xC3, 0xFF),
                writtenDataPayload(mono(-128 / 128f, -127 / 128f, -64 / 128f, -1 / 128f, 0f, 1 / 128f, 67 / 128f,
                        127 / 128f), WavEncoding.PCM_8));
    }

    @Test
    void writeFloat32ProducesExactBytes() throws IOException {
        assertArrayEquals(b(0x00, 0x00, 0x80, 0x3E, 0x00, 0x00, 0x00, 0xBF),
                writtenDataPayload(mono(0.25f, -0.5f), WavEncoding.FLOAT_32));
    }

    @Test
    void writeStereo16PinsChannelOrder() throws IOException {
        assertArrayEquals(b(0x34, 0x12, 0xDC, 0xFE, 0x02, 0x01, 0x04, 0x03),
                writtenDataPayload(new float[][] {{4660 / 32768f, 258 / 32768f}, {-292 / 32768f, 772 / 32768f}},
                        WavEncoding.PCM_16));
    }

    @Test
    void writeThreeChannel24BitPinsChannelOrder() throws IOException {
        float d = 8388608f;
        assertArrayEquals(
                b(0x03, 0x02, 0x01, 0x0C, 0x0B, 0x0A, 0xD0, 0xE0, 0xF0,
                        0x33, 0x22, 0x11, 0x66, 0x55, 0x44, 0x99, 0x88, 0x77),
                writtenDataPayload(new float[][] {
                        {66051 / d, 1122867 / d},
                        {658188 / d, 4478310 / d},
                        {(0xF0E0D0 - 0x1000000) / d, 7833753 / d}}, WavEncoding.PCM_24));
    }
}
