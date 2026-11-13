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

import com.jellyfish.timbre.audio.WavEncoding;
import com.jellyfish.timbre.audio.WavWriter;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev tool (not a test, not shipped) that writes the WAV files for the Milestone 2 manual listening test:
 * {@code beeps_stereo_44100.wav} (20 s, one beep every 250 ms with a 10 ms gap, rising pitch in a 20-beep
 * cycle so a skipped or repeated segment is heard as a pitch jump), {@code pan_test_mono_44100.wav} (mono
 * tone) and {@code bass_mono_48000.wav} (for the sample-rate mismatch error). All about -24 dBFS.
 *
 * <p>Usage: {@code ManualKitGenerator <output directory>}.
 */
public final class ManualKitGenerator {

    private static final double AMPLITUDE = Math.pow(10.0, -24.0 / 20.0);

    private ManualKitGenerator() {
    }

    /**
     * Writes the three files.
     *
     * @param args the output directory
     * @throws IOException if writing fails
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ManualKitGenerator <output directory>");
            System.exit(2);
        }
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);
        int rate = 44100;
        int beepFrames = rate * 250 / 1000;
        int gapFrames = rate * 10 / 1000;
        int total = rate * 20;
        float[] beeps = new float[total];
        for (int k = 0; k * beepFrames < total; k++) {
            double freq = 262.0 * Math.pow(2.0, (k % 20) / 10.0);
            int start = k * beepFrames;
            int len = Math.min(beepFrames - gapFrames, total - start);
            for (int i = 0; i < len; i++) {
                double env = Math.min(1.0, Math.min(i, len - 1 - i) / (rate * 0.005));
                beeps[start + i] = (float) (Math.sin(2 * Math.PI * freq * i / rate) * AMPLITUDE * env);
            }
        }
        write(dir.resolve("beeps_stereo_44100.wav"), rate, beeps, beeps.clone());
        write(dir.resolve("pan_test_mono_44100.wav"), rate, tone(rate, 330.0, 8.0));
        write(dir.resolve("bass_mono_48000.wav"), 48000, tone(48000, 110.0, 3.0));
        System.out.println("wrote 3 files to " + dir.getFileName());
    }

    private static float[] tone(int rate, double freq, double seconds) {
        float[] t = new float[(int) (rate * seconds)];
        int fade = rate / 50;
        for (int i = 0; i < t.length; i++) {
            double env = Math.min(1.0, Math.min(i, t.length - 1 - i) / (double) fade);
            t[i] = (float) (Math.sin(2 * Math.PI * freq * i / rate) * AMPLITUDE * env);
        }
        return t;
    }

    private static void write(Path file, int rate, float[]... channels) throws IOException {
        WavWriter.write(file, MemorySampleSource.copyOf(rate, channels), WavEncoding.PCM_16);
    }
}
