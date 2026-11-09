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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jellyfish.timbre.audio.support.FakeSource;
import com.jellyfish.timbre.audio.support.RiffBuilder;
import com.jellyfish.timbre.audio.support.Samples;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class WavWriterTest {

    @TempDir
    Path dir;

    private byte[] write(WavEncoding enc, SampleSource src) throws IOException {
        Path p = dir.resolve("out.wav");
        WavWriter.write(p, src, enc);
        return Files.readAllBytes(p);
    }

    private static MemorySampleSource mem(int rate, float[]... ch) {
        return MemorySampleSource.copyOf(rate, ch);
    }

    private static ByteBuffer le(byte[] b) {
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static String tag(byte[] b, int off) {
        return new String(b, off, 4, StandardCharsets.US_ASCII);
    }

    private static byte[] tail(byte[] b, int from) {
        return Arrays.copyOfRange(b, from, b.length);
    }

    @Test
    void pcm16StereoHeaderBytes() throws IOException {
        byte[] f = write(WavEncoding.PCM_16, mem(44100, new float[] {0f, 0.5f, -1f}, new float[] {1f, 0f, 0.25f}));
        ByteBuffer b = le(f);
        assertEquals(56, f.length);
        assertEquals("RIFF", tag(f, 0));
        assertEquals(48, b.getInt(4));
        assertEquals("WAVE", tag(f, 8));
        assertEquals("fmt ", tag(f, 12));
        assertEquals(16, b.getInt(16));
        assertEquals(1, b.getShort(20));
        assertEquals(2, b.getShort(22));
        assertEquals(44100, b.getInt(24));
        assertEquals(176400, b.getInt(28));
        assertEquals(4, b.getShort(32));
        assertEquals(16, b.getShort(34));
        assertEquals("data", tag(f, 36));
        assertEquals(12, b.getInt(40));
        // frame 0: L=0, R=32767 (1.0 clamps); frame 1: L=16384, R=0
        assertArrayEquals(Samples.packSigned(16, new int[] {0, 32767, 16384, 0, -32768, 8192}), tail(f, 44));
    }

    @Test
    void floatMonoHasFactChunkAndTag3() throws IOException {
        float[] v = {0.5f, -0.0f, 3f};
        byte[] f = write(WavEncoding.FLOAT_32, mem(48000, v));
        ByteBuffer b = le(f);
        assertEquals("fmt ", tag(f, 12));
        assertEquals(16, b.getInt(16));
        assertEquals(3, b.getShort(20));
        assertEquals(32, b.getShort(34));
        assertEquals("fact", tag(f, 36));
        assertEquals(4, b.getInt(40));
        assertEquals(3, b.getInt(44));
        assertEquals("data", tag(f, 48));
        assertEquals(12, b.getInt(52));
        assertEquals(f.length - 8, b.getInt(4));
        assertArrayEquals(Samples.packFloats(v), tail(f, 56));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 6})
    void moreThanTwoChannelsUseExtensible(int channels) throws IOException {
        WavEncoding enc = channels == 3 ? WavEncoding.PCM_24 : WavEncoding.FLOAT_32;
        float[][] ch = new float[channels][2];
        byte[] f = write(enc, MemorySampleSource.copyOf(48000, ch));
        ByteBuffer b = le(f);
        int bits = enc.bitsPerSample();
        assertEquals(40, b.getInt(16));
        assertEquals((short) 0xFFFE, b.getShort(20));
        assertEquals(channels, b.getShort(22));
        assertEquals(channels * (bits / 8), b.getShort(32));
        assertEquals(bits, b.getShort(34));
        assertEquals(22, b.getShort(36));
        assertEquals(bits, b.getShort(38));
        assertEquals(0, b.getInt(40));
        int subTag = enc.isFloat() ? 3 : 1;
        assertArrayEquals(RiffBuilder.guid(subTag), Arrays.copyOfRange(f, 44, 60));
        assertEquals(enc.isFloat() ? "fact" : "data", tag(f, 60));
    }

    @Test
    void oddDataSizeGetsPadByte8Bit() throws IOException {
        byte[] f = write(WavEncoding.PCM_8, mem(8000, new float[] {0f, 0f, 0f}));
        ByteBuffer b = le(f);
        assertEquals(3, b.getInt(40));
        assertEquals(48, f.length);
        assertEquals(40, b.getInt(4)); // RIFF size excludes the first 8 bytes and includes the pad
        assertEquals(0, f[47]);
    }

    @Test
    void oddDataSizeGetsPadByte24Bit() throws IOException {
        byte[] f = write(WavEncoding.PCM_24, mem(8000, new float[] {0.5f, 0f, -0.5f})); // 9 data bytes
        ByteBuffer b = le(f);
        assertEquals(9, b.getInt(40));
        assertEquals(54, f.length);
        assertEquals(46, b.getInt(4));
        assertEquals(0, f[53]);
    }

    @ParameterizedTest
    @EnumSource(WavEncoding.class)
    void clampsOutOfRangeSamples(WavEncoding enc) throws IOException {
        byte[] f = write(enc, mem(8000, new float[] {2f, -2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}));
        byte[] expected = switch (enc) {
            case PCM_8 -> Samples.pack8(new int[] {255, 0, 255, 0});
            case PCM_16 -> Samples.packSigned(16, new int[] {32767, -32768, 32767, -32768});
            case PCM_24 -> Samples.packSigned(24, new int[] {8388607, -8388608, 8388607, -8388608});
            case PCM_32 -> Samples.packSigned(32, new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MIN_VALUE});
            case FLOAT_32 -> Samples.packFloats(new float[] {2f, -2f, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY});
        };
        assertArrayEquals(expected, tail(f, f.length - expected.length));
    }

    @Test
    void nanBecomesZeroInIntegerEncodings() throws IOException {
        for (WavEncoding enc : new WavEncoding[] {WavEncoding.PCM_8, WavEncoding.PCM_16, WavEncoding.PCM_24,
            WavEncoding.PCM_32}) {
            byte[] f = write(enc, mem(8000, new float[] {Float.NaN, Float.NaN}));
            int bytes = enc.bitsPerSample() / 8;
            byte[] data = Arrays.copyOfRange(f, 44, 44 + 2 * bytes);
            byte[] zero = new byte[2 * bytes];
            if (enc == WavEncoding.PCM_8) {
                Arrays.fill(zero, (byte) 128);
            }
            assertArrayEquals(zero, data, enc.name());
        }
    }

    @Test
    void roundsExactHalfCasesToEven() throws IOException {
        float[] v = new float[6];
        float[] halves = {0.5f, 1.5f, 2.5f, -0.5f, -1.5f, -2.5f};
        for (int i = 0; i < 6; i++) {
            v[i] = halves[i] / 32768f; // exactly representable
        }
        byte[] f = write(WavEncoding.PCM_16, mem(8000, v));
        assertArrayEquals(Samples.packSigned(16, new int[] {0, 2, 2, 0, -2, -2}), tail(f, 44));
    }

    @Test
    void eightBitOutputIsUnsigned() throws IOException {
        byte[] f = write(WavEncoding.PCM_8, mem(8000, new float[] {-1f, 0f, 127 / 128f, 1f, -0.5f}));
        // 5 data bytes + pad byte
        assertArrayEquals(Samples.pack8(new int[] {0, 128, 255, 255, 64}), Arrays.copyOfRange(f, 44, 49));
    }

    @Test
    void rejectsOutputsOver4GiBBeforeCreatingAnything() throws IOException {
        SampleSource huge = FakeSource.silence(44100, 2, 1L << 31); // 8 GiB of 16-bit stereo
        assertThrows(UnsupportedAudioFormatException.class,
                () -> WavWriter.write(dir.resolve("huge.wav"), huge, WavEncoding.PCM_16));
        // data alone fits, but the headers push the RIFF size over 4 GiB - 1
        SampleSource edge = FakeSource.silence(8000, 1, 4294967260L);
        assertThrows(UnsupportedAudioFormatException.class,
                () -> WavWriter.write(dir.resolve("edge.wav"), edge, WavEncoding.PCM_8));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(0, s.count(), "nothing may be created");
        }
    }

    @Test
    void rejectsMoreThan64Channels() throws IOException {
        SampleSource s = FakeSource.silence(8000, 65, 10);
        assertThrows(IllegalArgumentException.class, () -> WavWriter.write(dir.resolve("x.wav"), s,
                WavEncoding.PCM_16));
    }

    private static FakeSource failingSource() {
        return new FakeSource(8000, 1, 100_000, (start, n, dest, off) -> {
            if (start >= 16384) {
                throw new IllegalStateException("simulated failure");
            }
            Arrays.fill(dest[0], off, off + n, 0f);
        });
    }

    @Test
    void failureMidWriteLeavesExistingTargetUnchangedAndNoTempFile() throws IOException {
        Path target = dir.resolve("keep.wav");
        byte[] original = {1, 2, 3, 4, 5};
        Files.write(target, original);
        assertThrows(IllegalStateException.class, () -> WavWriter.write(target, failingSource(), WavEncoding.PCM_16));
        assertArrayEquals(original, Files.readAllBytes(target));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(1, s.count(), "only the target may remain");
        }
    }

    @Test
    void failureMidWriteLeavesAbsentTargetAbsentAndNoTempFile() throws IOException {
        Path target = dir.resolve("never.wav");
        assertThrows(IllegalStateException.class, () -> WavWriter.write(target, failingSource(), WavEncoding.PCM_16));
        assertEquals(false, Files.exists(target));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void overwritesAnExistingFile() throws IOException {
        Path target = dir.resolve("o.wav");
        Files.write(target, new byte[100]);
        WavWriter.write(target, mem(8000, new float[] {0.5f}), WavEncoding.PCM_16);
        byte[] f = Files.readAllBytes(target);
        assertEquals(46, f.length);
        assertEquals("RIFF", tag(f, 0));
    }

    @Test
    void emptySourceWritesHeaderOnly() throws IOException {
        byte[] f = write(WavEncoding.PCM_16, MemorySampleSource.copyOf(8000, new float[1][0]));
        assertEquals(44, f.length);
        assertEquals(0, le(f).getInt(40));
        assertEquals(36, le(f).getInt(4));
    }

    @Test
    void leavesNoTemporaryFileAfterSuccess() throws IOException {
        write(WavEncoding.PCM_16, mem(8000, new float[] {0f}));
        try (Stream<Path> s = Files.list(dir)) {
            assertEquals(1, s.count());
        }
    }
}
