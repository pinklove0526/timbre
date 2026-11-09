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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.audio.support.Samples;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;

/** Opt-in: runs only with {@code -Dtimbre.testWavDir=<directory>}. Reads the directory, never modifies it. */
class WavCorpusTest {

    @Test
    void realWorldFilesRoundTripAndAgreeWithTheJdk() throws Exception {
        String prop = System.getProperty("timbre.testWavDir");
        assumeTrue(prop != null && !prop.isBlank(), "timbre.testWavDir not set; corpus test skipped");
        Path root = Path.of(prop);
        assertTrue(Files.isDirectory(root), "not a directory: " + root);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT)
                    .endsWith(".wav")).sorted().toList();
        }
        assumeTrue(!files.isEmpty(), "no .wav files under the corpus directory; corpus test skipped");
        Path tmp = Files.createTempDirectory("timbre-corpus");
        int ok = 0;
        int unsupported = 0;
        int jdkChecked = 0;
        List<String> failures = new ArrayList<>();
        try {
            for (Path f : files) {
                String name = root.relativize(f).toString();
                WavFile first;
                try {
                    first = WavReader.read(f);
                } catch (UnsupportedAudioFormatException e) {
                    unsupported++;
                    System.out.println("CORPUS unsupported " + name + ": " + e.getMessage());
                    continue;
                }
                try {
                    Path out = tmp.resolve("rt.wav");
                    WavWriter.write(out, first.source(), first.encoding());
                    WavFile second = WavReader.read(out);
                    assertEquals(first.encoding(), second.encoding());
                    assertEquals(first.source().sampleRate(), second.source().sampleRate());
                    assertEquals(first.source().frameCount(), second.source().frameCount());
                    float[][] a = Samples.readAll(first.source());
                    float[][] b = Samples.readAll(second.source());
                    for (int c = 0; c < a.length; c++) {
                        for (int i = 0; i < a[c].length; i++) {
                            if (Float.floatToRawIntBits(a[c][i]) != Float.floatToRawIntBits(b[c][i])) {
                                throw new AssertionError("round trip differs at ch " + c + " frame " + i);
                            }
                        }
                    }
                    if (crossCheckWithJdk(f, first, a)) {
                        jdkChecked++;
                    }
                    ok++;
                } catch (AssertionError | IOException e) {
                    failures.add(name + ": " + e);
                }
            }
        } finally {
            try (Stream<Path> s = Files.list(tmp)) {
                for (Path p : s.toList()) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(tmp);
        }
        System.out.println("CORPUS summary: files=" + files.size() + " roundTripOk=" + ok + " unsupported="
                + unsupported + " jdkCrossChecked=" + jdkChecked + " failures=" + failures.size());
        assertTrue(failures.isEmpty(), "corpus failures: " + failures);
    }

    /** @return true if the JDK could read the file and agreed with our decoding */
    private static boolean crossCheckWithJdk(Path file, WavFile ours, float[][] oursPlanar) throws IOException {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat fmt = in.getFormat();
            assertEquals(ours.source().sampleRate(), (int) fmt.getSampleRate(), "JDK sample rate");
            assertEquals(ours.source().channelCount(), fmt.getChannels(), "JDK channels");
            assertEquals(ours.encoding().bitsPerSample(), fmt.getSampleSizeInBits(), "JDK sample size");
            assertEquals(ours.source().frameCount(), in.getFrameLength(), "JDK frame length");
            byte[] raw = in.readAllBytes();
            ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int channels = oursPlanar.length;
            int frames = oursPlanar.length == 0 ? 0 : oursPlanar[0].length;
            for (int f = 0; f < frames; f++) {
                for (int c = 0; c < channels; c++) {
                    float expect = switch (ours.encoding()) {
                        case PCM_8 -> ((raw[f * channels + c] & 0xFF) - 128) / 128f;
                        case PCM_16 -> b.getShort(2 * (f * channels + c)) / 32768f;
                        case PCM_24 -> {
                            int o = 3 * (f * channels + c);
                            yield ((raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8) | (raw[o + 2] << 16)) / 8388608f;
                        }
                        case PCM_32 -> (float) b.getInt(4 * (f * channels + c)) / 2147483648f;
                        case FLOAT_32 -> b.getFloat(4 * (f * channels + c));
                    };
                    if (Float.floatToRawIntBits(expect) != Float.floatToRawIntBits(oursPlanar[c][f])
                            && !(Float.isNaN(expect) && Float.isNaN(oursPlanar[c][f]))) {
                        throw new AssertionError("JDK bytes disagree with our decoding at ch " + c + " frame " + f);
                    }
                }
            }
            return true;
        } catch (UnsupportedAudioFileException e) {
            System.out.println("CORPUS jdk cannot read " + file.getFileName() + ": " + e.getMessage());
            return false;
        }
    }
}
