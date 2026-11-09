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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jellyfish.timbre.audio.support.Samples;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Cross-checks against the JDK's javax.sound.sampled (test scope only). */
class WavJdkOracleTest {

    private static final int FRAMES = 700;
    private static final int RATE = 44100;

    @TempDir
    Path dir;

    static Stream<Arguments> cases() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (WavEncoding e : WavEncoding.values()) {
            for (int ch = 1; ch <= 2; ch++) {
                b.add(Arguments.of(e, ch));
            }
        }
        return b.build();
    }

    private static AudioFormat jdkFormat(WavEncoding e, int channels) {
        int bits = e.bitsPerSample();
        int frameSize = channels * bits / 8;
        AudioFormat.Encoding enc = e.isFloat() ? AudioFormat.Encoding.PCM_FLOAT
                : e == WavEncoding.PCM_8 ? AudioFormat.Encoding.PCM_UNSIGNED : AudioFormat.Encoding.PCM_SIGNED;
        return new AudioFormat(enc, RATE, bits, channels, frameSize, RATE, false);
    }

    /** Test signal: per-channel integer codes (unsigned 0..255 for 8-bit) or floats. */
    private record Signal(float[][] floats, byte[] interleavedBytes) {
    }

    private static Signal signal(WavEncoding e, int channels) {
        float[][] f = new float[channels][FRAMES];
        byte[] bytes;
        if (e.isFloat()) {
            for (int c = 0; c < channels; c++) {
                f[c] = Samples.noise(31 + c, FRAMES);
            }
            bytes = Samples.packFloats(Samples.interleave(f));
        } else {
            int bits = e.bitsPerSample();
            int[][] ints = new int[channels][];
            for (int c = 0; c < channels; c++) {
                ints[c] = Samples.intNoise(77 + c, FRAMES, bits);
                if (bits == 32) {
                    // 32-bit PCM is not lossless through float32: keep only 24 significant bits so the values
                    // are exactly representable and the byte comparison is meaningful
                    for (int i = 0; i < FRAMES; i++) {
                        ints[c][i] &= 0xFFFFFF00;
                    }
                }
                for (int i = 0; i < FRAMES; i++) {
                    if (bits == 8) {
                        ints[c][i] += 128;
                        f[c][i] = (ints[c][i] - 128) / 128f;
                    } else {
                        f[c][i] = Samples.toFloat(bits, ints[c][i]);
                    }
                }
            }
            int[] inter = Samples.interleave(ints);
            bytes = bits == 8 ? Samples.pack8(inter) : Samples.packSigned(bits, inter);
        }
        return new Signal(f, bytes);
    }

    @ParameterizedTest(name = "JDK writes, we read: {0} x{1}")
    @MethodSource("cases")
    void jdkWrittenFileIsReadIdentically(WavEncoding e, int channels) throws Exception {
        Signal s = signal(e, channels);
        AudioFormat fmt = jdkFormat(e, channels);
        Path file = dir.resolve("jdk.wav");
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(s.interleavedBytes()), fmt, FRAMES)) {
            if (!AudioSystem.isFileTypeSupported(AudioFileFormat.Type.WAVE, ais)) {
                System.out.println("JDK-ORACLE (a) " + e + " x" + channels + ": SKIPPED (JDK cannot write this format)");
                assumeTrue(false, "JDK cannot write " + e);
            }
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        }
        System.out.println("JDK-ORACLE (a) " + e + " x" + channels + ": RAN");
        WavFile ours = WavReader.read(file);
        assertEquals(e, ours.encoding());
        assertEquals(RATE, ours.source().sampleRate());
        assertEquals(FRAMES, ours.source().frameCount());
        assertArrayEquals(s.floats(), Samples.readAll(ours.source()));
    }

    @ParameterizedTest(name = "we write, JDK reads: {0} x{1}")
    @MethodSource("cases")
    void ourFileIsReadByJdkWithIdenticalBytes(WavEncoding e, int channels) throws Exception {
        Signal s = signal(e, channels);
        AudioFormat fmt = jdkFormat(e, channels);
        try (AudioInputStream probe = new AudioInputStream(new ByteArrayInputStream(new byte[0]), fmt, 0)) {
            if (!AudioSystem.isFileTypeSupported(AudioFileFormat.Type.WAVE, probe)) {
                System.out.println("JDK-ORACLE (b) " + e + " x" + channels + ": SKIPPED (JDK does not support the format)");
                assumeTrue(false, "JDK does not support " + e);
            }
        }
        Path file = dir.resolve("ours.wav");
        WavWriter.write(file, MemorySampleSource.copyOf(RATE, s.floats()), e);
        try (AudioInputStream in = readWithJdk(file, e, channels)) {
            AudioFormat got = in.getFormat();
            assertEquals(RATE, (int) got.getSampleRate());
            assertEquals(channels, got.getChannels());
            assertEquals(e.bitsPerSample(), got.getSampleSizeInBits());
            assertEquals(FRAMES, in.getFrameLength());
            assertArrayEquals(s.interleavedBytes(), in.readAllBytes());
        }
        System.out.println("JDK-ORACLE (b) " + e + " x" + channels + ": RAN");
    }

    private AudioInputStream readWithJdk(Path file, WavEncoding e, int channels) throws IOException {
        try {
            return AudioSystem.getAudioInputStream(file.toFile());
        } catch (UnsupportedAudioFileException ex) {
            return fail("JDK supports " + e + " x" + channels + " but rejected our file: " + ex.getMessage());
        }
    }
}
