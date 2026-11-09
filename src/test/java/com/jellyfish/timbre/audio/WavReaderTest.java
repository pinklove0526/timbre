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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.RiffBuilder;
import com.jellyfish.timbre.audio.support.Samples;
import com.jellyfish.timbre.audio.support.TestWav;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WavReaderTest {

    @TempDir
    Path dir;

    /** Reads through the stream and the path entry points and checks they agree. */
    private WavFile read(byte[] bytes) throws IOException {
        WavFile fromStream = WavReader.read(new ByteArrayInputStream(bytes));
        Path p = dir.resolve("t-" + System.nanoTime() + ".wav");
        Files.write(p, bytes);
        WavFile fromPath = WavReader.read(p);
        assertEquals(fromStream.encoding(), fromPath.encoding());
        assertEquals(fromStream.source().frameCount(), fromPath.source().frameCount());
        assertArrayEquals(Samples.readAll(fromStream.source()), Samples.readAll(fromPath.source()));
        return fromPath;
    }

    private static void assertSamples(float[][] expected, WavFile f) {
        assertArrayEquals(expected, Samples.readAll(f.source()));
    }

    private static UnsupportedAudioFormatException rejected(byte[] bytes) {
        UnsupportedAudioFormatException e = assertThrows(UnsupportedAudioFormatException.class,
                () -> WavReader.read(new ByteArrayInputStream(bytes)));
        return e;
    }

    static Stream<Arguments> formats() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (int ch : new int[] {1, 2}) {
            b.add(Arguments.of(1, 8, ch, WavEncoding.PCM_8));
            b.add(Arguments.of(1, 16, ch, WavEncoding.PCM_16));
            b.add(Arguments.of(1, 24, ch, WavEncoding.PCM_24));
            b.add(Arguments.of(1, 32, ch, WavEncoding.PCM_32));
            b.add(Arguments.of(3, 32, ch, WavEncoding.FLOAT_32));
        }
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("formats")
    void readsMonoAndStereo(int tag, int bits, int channels, WavEncoding enc) throws IOException {
        TestWav w = TestWav.build(tag, bits, channels, 1000, 44100, 7);
        WavFile f = read(w.file());
        assertEquals(enc, f.encoding());
        assertEquals(44100, f.source().sampleRate());
        assertEquals(channels, f.source().channelCount());
        assertEquals(1000, f.source().frameCount());
        assertSamples(w.expected(), f);
    }

    @ParameterizedTest
    @MethodSource("multichannel")
    void multichannelInterleaveOrder(int channels) throws IOException {
        int[][] ch = new int[channels][4];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < 4; i++) {
                ch[c][i] = (c + 1) * 1000 + i;
            }
        }
        byte[] file = RiffBuilder.wave().fmt(1, channels, 8000, 16)
                .data(Samples.packSigned(16, Samples.interleave(ch))).build();
        WavFile f = read(file);
        float[][] got = Samples.readAll(f.source());
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < 4; i++) {
                assertEquals((c + 1) * 1000 + i, Math.round(got[c][i] * 32768f), "ch " + c + " frame " + i);
            }
        }
    }

    static Stream<Integer> multichannel() {
        return Stream.of(3, 6);
    }

    @Test
    void extensiblePcm16Stereo() throws IOException {
        int[] a = {1, -2, 300};
        int[] b = {-32768, 32767, 0};
        byte[] file = RiffBuilder.wave().fmtExtensible(1, 2, 48000, 16, 16, 3)
                .data(Samples.packSigned(16, Samples.interleave(a, b))).build();
        WavFile f = read(file);
        assertEquals(WavEncoding.PCM_16, f.encoding());
        assertEquals(-1.0f, Samples.readAll(f.source())[1][0]);
    }

    @Test
    void extensiblePcm24ThreeChannels() throws IOException {
        int[] v = {8388607, -8388608, 5};
        byte[] file = RiffBuilder.wave().fmtExtensible(1, 3, 44100, 24, 24, 0)
                .data(Samples.packSigned(24, Samples.interleave(v, v, v))).build();
        WavFile f = read(file);
        assertEquals(WavEncoding.PCM_24, f.encoding());
        assertEquals(3, f.source().channelCount());
        assertEquals(-1.0f, Samples.readAll(f.source())[2][1]);
    }

    @Test
    void extensible24ValidBitsIn32BitContainerIsReadAtContainerWidth() throws IOException {
        // left-justified 24-bit samples in a 32-bit container: value << 8
        int[] raw = {0x12345600, -0x00000100, 0x7FFFFF00};
        byte[] file = RiffBuilder.wave().fmtExtensible(1, 1, 44100, 32, 24, 4)
                .data(Samples.packSigned(32, raw)).build();
        WavFile f = read(file);
        assertEquals(WavEncoding.PCM_32, f.encoding());
        float[] got = Samples.readAll(f.source())[0];
        for (int i = 0; i < raw.length; i++) {
            assertEquals((float) raw[i] / 2147483648f, got[i]);
        }
    }

    @Test
    void extensibleFloat() throws IOException {
        float[] v = {0.25f, -0.5f, 1.0f};
        byte[] file = RiffBuilder.wave().fmtExtensible(3, 1, 96000, 32, 32, 4)
                .data(Samples.packFloats(v)).build();
        WavFile f = read(file);
        assertEquals(WavEncoding.FLOAT_32, f.encoding());
        assertArrayEquals(v, Samples.readAll(f.source())[0]);
    }

    private static byte[] pcm16Data(int... v) {
        return Samples.packSigned(16, v);
    }

    @Test
    void skipsUnknownChunksAroundFmtAndAfterData() throws IOException {
        byte[] file = RiffBuilder.wave()
                .chunk("LIST", new byte[10])
                .fmt(1, 1, 8000, 16)
                .chunk("fact", new byte[4])
                .chunk("bext", new byte[256])
                .data(pcm16Data(1, 2, 3))
                .chunk("cue ", new byte[24])
                .build();
        WavFile f = read(file);
        assertEquals(3, f.source().frameCount());
    }

    @Test
    void honoursPadByteAfterOddSizedChunks() throws IOException {
        byte[] file = RiffBuilder.wave()
                .chunk("junk", new byte[5])
                .fmt(1, 1, 8000, 16)
                .chunk("odd ", new byte[1])
                .data(pcm16Data(10, 20))
                .build();
        WavFile f = read(file);
        assertEquals(2, f.source().frameCount());
        assertEquals(20, Math.round(Samples.readAll(f.source())[0][1] * 32768f));
    }

    @Test
    void ignoresEverythingAfterData() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 16).data(pcm16Data(1, 2))
                .raw(new byte[] {1, 2, 3}).build();
        assertEquals(2, read(file).source().frameCount());
    }

    @Test
    void declaredDataSizeZeroUsesBytesPresent() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 16).dataDeclaring(0, pcm16Data(1, 2, 3)).build();
        assertEquals(3, read(file).source().frameCount());
    }

    @Test
    void declaredDataSizeFFFFFFFFUsesBytesPresent() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 16).dataDeclaring(0xFFFFFFFFL, pcm16Data(1, 2, 3, 4))
                .build();
        assertEquals(4, read(file).source().frameCount());
    }

    @Test
    void declaredDataSizeLargerThanFileUsesBytesPresent() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 16).dataDeclaring(1000, pcm16Data(1, 2, 3)).build();
        assertEquals(3, read(file).source().frameCount());
    }

    @Test
    void declaredDataSizeSmallerThanFileIsHonoured() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 1, 8000, 16).dataDeclaring(4, pcm16Data(1, 2, 3, 4)).build();
        assertEquals(2, read(file).source().frameCount());
    }

    @Test
    void trailingPartialFrameIsDropped() throws IOException {
        byte[] payload = new byte[2 * 4 + 3]; // 2 stereo 16-bit frames + 3 stray bytes
        byte[] file = RiffBuilder.wave().fmt(1, 2, 8000, 16).dataDeclaring(payload.length, payload).build();
        assertEquals(2, read(file).source().frameCount());
    }

    @Test
    void emptyDataGivesZeroFrames() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 2, 8000, 16).data(new byte[0]).build();
        WavFile f = read(file);
        assertEquals(0, f.source().frameCount());
        assertEquals(2, f.source().channelCount());
    }

    @Test
    void tinyFileDeclaringHugeDataDoesNotAllocateHugeArrays() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 2, 8000, 16).dataDeclaring(0xFFFFFFF0L, new byte[8]).build();
        // a 1 MiB limit would trip the size guard if allocation followed the declared size
        WavFile fromStream = WavReader.read(new ByteArrayInputStream(file), 1 << 20);
        Path p = dir.resolve("tiny.wav");
        Files.write(p, file);
        WavFile fromPath = WavReader.read(p, 1 << 20);
        assertEquals(2, fromStream.source().frameCount());
        assertEquals(2, fromPath.source().frameCount());
    }

    @Test
    void sizeGuardRejectsFilesAboveTheLimit() throws IOException {
        byte[] file = RiffBuilder.wave().fmt(1, 2, 8000, 16).data(new byte[4000]).build(); // 1000 frames
        // 1000 frames * 2 channels * 4 bytes = 8000 decoded bytes
        UnsupportedAudioFormatException e1 = assertThrows(UnsupportedAudioFormatException.class,
                () -> WavReader.read(new ByteArrayInputStream(file), 7999));
        assertTrue(e1.getMessage().contains("too large"), e1.getMessage());
        Path p = dir.resolve("big.wav");
        Files.write(p, file);
        assertThrows(UnsupportedAudioFormatException.class, () -> WavReader.read(p, 7999));
        assertEquals(1000, WavReader.read(p, 8000).source().frameCount());
        assertEquals(1000, WavReader.read(new ByteArrayInputStream(file), 8000).source().frameCount());
    }

    // ---- value mapping ----

    @Test
    void mapping16Bit() throws IOException {
        float[] got = Samples.readAll(read(RiffBuilder.wave().fmt(1, 1, 8000, 16)
                .data(pcm16Data(-32768, 32767, 0)).build()).source())[0];
        assertEquals(-1.0f, got[0]);
        assertEquals(32767 / 32768f, got[1]);
        assertEquals(0f, got[2]);
    }

    @Test
    void mapping8BitUnsigned() throws IOException {
        float[] got = Samples.readAll(read(RiffBuilder.wave().fmt(1, 1, 8000, 8)
                .data(new byte[] {0, (byte) 128, (byte) 255}).build()).source())[0];
        assertEquals(-1.0f, got[0]);
        assertEquals(0f, got[1]);
        assertEquals(127 / 128f, got[2]);
    }

    @Test
    void mapping24BitMinAndMax() throws IOException {
        float[] got = Samples.readAll(read(RiffBuilder.wave().fmt(1, 1, 8000, 24)
                .data(Samples.packSigned(24, new int[] {-8388608, 8388607})).build()).source())[0];
        assertEquals(-1.0f, got[0]);
        assertEquals(8388607 / 8388608f, got[1]);
    }

    @Test
    void mapping32BitMinAndMax() throws IOException {
        float[] got = Samples.readAll(read(RiffBuilder.wave().fmt(1, 1, 8000, 32)
                .data(Samples.packSigned(32, new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE, 0})).build())
                .source())[0];
        assertEquals(-1.0f, got[0]);
        assertEquals(1.0f, got[1]); // 2^31-1 rounds to 2^31 in float32
        assertEquals(0f, got[2]);
    }

    @Test
    void floatValuesAreVerbatimIncludingNaNAndInfinity() throws IOException {
        float[] v = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f, 5.5f};
        float[] got = Samples.readAll(read(RiffBuilder.wave().fmt(3, 1, 8000, 32).data(Samples.packFloats(v))
                .build()).source())[0];
        for (int i = 0; i < v.length; i++) {
            assertEquals(Float.floatToRawIntBits(v[i]), Float.floatToRawIntBits(got[i]), "index " + i);
        }
    }

    // ---- rejected / malformed input ----

    private static byte[] ok16() {
        return RiffBuilder.wave().fmt(1, 1, 8000, 16).data(new byte[4]).build();
    }

    static Stream<Arguments> badFiles() {
        byte[] okFile = ok16();
        byte[] rf64 = okFile.clone();
        System.arraycopy(RiffBuilder.ascii("RF64"), 0, rf64, 0, 4);
        byte[] rifx = okFile.clone();
        System.arraycopy(RiffBuilder.ascii("RIFX"), 0, rifx, 0, 4);
        return Stream.of(
                Arguments.of("empty file", new byte[0], "too short"),
                Arguments.of("truncated header", java.util.Arrays.copyOf(okFile, 8), "too short"),
                Arguments.of("non-RIFF", RiffBuilder.wave().magic("JUNK").fmt(1, 1, 8000, 16).data(new byte[4]).build(),
                        "not a RIFF"),
                Arguments.of("not WAVE form", RiffBuilder.wave().form("AVI ").build(), "not WAVE"),
                Arguments.of("RF64", rf64, "RF64"),
                Arguments.of("RIFX", rifx, "RIFX"),
                Arguments.of("missing fmt", RiffBuilder.wave().chunk("LIST", new byte[4]).build(), "no fmt"),
                Arguments.of("missing data", RiffBuilder.wave().fmt(1, 1, 8000, 16).build(), "no data"),
                Arguments.of("fmt after data", RiffBuilder.wave().data(new byte[4]).fmt(1, 1, 8000, 16).build(),
                        "before the fmt"),
                Arguments.of("64-bit float", RiffBuilder.wave().fmt(3, 1, 8000, 64).data(new byte[8]).build(),
                        "64-bit float"),
                Arguments.of("12-bit PCM", RiffBuilder.wave().fmtRaw(1, 1, 8000, 12000, 2, 12).data(new byte[4])
                        .build(), "12-bit"),
                Arguments.of("mismatched blockAlign", RiffBuilder.wave().fmtRaw(1, 2, 8000, 32000, 3, 16)
                        .data(new byte[8]).build(), "blockAlign"),
                Arguments.of("zero channels", RiffBuilder.wave().fmtRaw(1, 0, 8000, 0, 0, 16).data(new byte[4])
                        .build(), "zero channels"),
                Arguments.of("65 channels", RiffBuilder.wave().fmt(1, 65, 8000, 16).data(new byte[130]).build(),
                        "65 channels"),
                Arguments.of("zero sample rate", RiffBuilder.wave().fmtRaw(1, 1, 0, 0, 2, 16).data(new byte[4])
                        .build(), "sample rate of zero"),
                Arguments.of("A-law", RiffBuilder.wave().fmt(6, 1, 8000, 8).data(new byte[4]).build(), "A-law"),
                Arguments.of("mu-law", RiffBuilder.wave().fmt(7, 1, 8000, 8).data(new byte[4]).build(), "mu-law"),
                Arguments.of("ADPCM", RiffBuilder.wave().fmt(2, 1, 8000, 4).data(new byte[4]).build(), "ADPCM"),
                Arguments.of("unknown EXTENSIBLE subformat", RiffBuilder.wave().fmtExtensible(0x55, 1, 8000, 16, 16, 0)
                        .data(new byte[4]).build(), "EXTENSIBLE subformat"),
                Arguments.of("fmt too small", RiffBuilder.wave().chunk("fmt ", new byte[8]).data(new byte[4]).build(),
                        "fmt chunk is too small"),
                Arguments.of("file ends inside fmt", RiffBuilder.wave().chunkDeclaring("fmt ", 16, new byte[6])
                        .build(), "inside the fmt"),
                Arguments.of("file ends inside unknown chunk", RiffBuilder.wave().fmt(1, 1, 8000, 16)
                        .chunkDeclaring("LIST", 100, new byte[3]).build(), "inside chunk"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFiles")
    void rejectsBadInputWithClearMessage(String name, byte[] bytes, String fragment) throws IOException {
        UnsupportedAudioFormatException e = rejected(bytes);
        assertTrue(e.getMessage().contains(fragment), name + ": message was: " + e.getMessage());
        Path p = dir.resolve("bad.wav");
        Files.write(p, bytes);
        UnsupportedAudioFormatException e2 = assertThrows(UnsupportedAudioFormatException.class,
                () -> WavReader.read(p));
        assertTrue(e2.getMessage().contains(fragment), name + " (path): message was: " + e2.getMessage());
    }

    @Test
    void missingFileIsAPlainIoException() {
        assertThrows(java.nio.file.NoSuchFileException.class, () -> WavReader.read(dir.resolve("nope.wav")));
    }
}
