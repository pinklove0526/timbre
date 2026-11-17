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
import com.jellyfish.timbre.model.SampleSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev tool (not a test, not shipped) that writes the WAV files for the Milestone 2 manual listening test:
 * {@code beeps_stereo_44100.wav} (20 s, one beep every 250 ms with a 10 ms gap, rising pitch in a 20-beep
 * cycle so a skipped or repeated segment is heard as a pitch jump), {@code pan_test_mono_44100.wav} (mono
 * tone) and {@code tone_mono_48000.wav} (48 kHz, 10 s, 440 Hz, for the 48 kHz path and the sample-rate mismatch error). All
 * about -24 dBFS with 20 ms fades.
 *
 * <p>Milestone 3 additions for the waveform display: {@code sweep_mono_44100.wav} (10 s logarithmic sweep
 * 50 Hz to 10 kHz at about -12 dBFS), {@code clipped_sine_mono_44100.wav} (5 s, 220 Hz, driven past full
 * scale and hard-clipped to +-1.0), {@code silence_stereo_44100.wav} (5 s), {@code impulses_mono_44100.wav}
 * (10 s of silence with single-sample impulses +1.0 at 1.0 s, -1.0 at 2.5 s, +0.5 at 5.0 s and -0.5 at
 * 9.0 s), {@code stereo_difference_44100.wav} (8 s: left 440 Hz at -6 dBFS, right 660 Hz at -18 dBFS) and,
 * only with {@code -Dtimbre.kit.long=true}, {@code long_stereo_44100_10min.wav} (10 minutes, 16-bit: a
 * slowly amplitude-modulated 220 Hz tone on the left, a 330 Hz tone on the right; generated on the fly, not
 * held in memory).
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
        write(dir.resolve("tone_mono_48000.wav"), 48000, tone(48000, 440.0, 10.0));
        int files = 3;
        write(dir.resolve("sweep_mono_44100.wav"), rate, sweep(rate, 10.0, 50.0, 10_000.0, dbfs(-12.0)));
        write(dir.resolve("clipped_sine_mono_44100.wav"), rate, clippedSine(rate, 5.0, 220.0, 2.5));
        write(dir.resolve("silence_stereo_44100.wav"), rate, new float[rate * 5], new float[rate * 5]);
        write(dir.resolve("impulses_mono_44100.wav"), rate, impulses(rate));
        write(dir.resolve("stereo_difference_44100.wav"), rate, sine(rate, 8.0, 440.0, dbfs(-6.0)),
                sine(rate, 8.0, 660.0, dbfs(-18.0)));
        files += 5;
        if (Boolean.getBoolean("timbre.kit.long")) {
            WavWriter.write(dir.resolve("long_stereo_44100_10min.wav"), new LongToneSource(rate, 600),
                    WavEncoding.PCM_16);
            files++;
        }
        System.out.println("wrote " + files + " files to " + dir.getFileName());
    }

    private static double dbfs(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    private static float[] sweep(int rate, double seconds, double f0, double f1, double amplitude) {
        float[] t = new float[(int) (rate * seconds)];
        double k = Math.log(f1 / f0);
        for (int i = 0; i < t.length; i++) {
            double time = (double) i / rate;
            // phase of a logarithmic sweep: 2 pi f0 T / ln(f1/f0) * (exp(t ln(f1/f0) / T) - 1)
            double phase = 2 * Math.PI * f0 * seconds / k * (Math.exp(time * k / seconds) - 1.0);
            t[i] = (float) (Math.sin(phase) * amplitude);
        }
        return t;
    }

    private static float[] clippedSine(int rate, double seconds, double freq, double drive) {
        float[] t = new float[(int) (rate * seconds)];
        for (int i = 0; i < t.length; i++) {
            double v = Math.sin(2 * Math.PI * freq * i / rate) * drive;
            t[i] = (float) Math.max(-1.0, Math.min(1.0, v));
        }
        return t;
    }

    private static float[] sine(int rate, double seconds, double freq, double amplitude) {
        float[] t = new float[(int) (rate * seconds)];
        for (int i = 0; i < t.length; i++) {
            t[i] = (float) (Math.sin(2 * Math.PI * freq * i / rate) * amplitude);
        }
        return t;
    }

    private static float[] impulses(int rate) {
        float[] t = new float[rate * 10];
        t[rate] = 1.0f;
        t[(int) (rate * 2.5)] = -1.0f;
        t[rate * 5] = 0.5f;
        t[rate * 9] = -0.5f;
        return t;
    }

    /** Generates the 10-minute stereo test tone on demand so it is never held in memory. */
    private static final class LongToneSource implements SampleSource {
        private final int rate;
        private final long frames;

        LongToneSource(int rate, int seconds) {
            this.rate = rate;
            this.frames = (long) rate * seconds;
        }

        @Override
        public int sampleRate() {
            return rate;
        }

        @Override
        public int channelCount() {
            return 2;
        }

        @Override
        public long frameCount() {
            return frames;
        }

        @Override
        public void read(long startFrame, int count, float[][] dest, int destOffset) {
            for (int i = 0; i < count; i++) {
                double time = (double) (startFrame + i) / rate;
                double modulation = 0.55 + 0.35 * Math.sin(2 * Math.PI * time / 45.0); // slow swell, 45 s period
                dest[0][destOffset + i] = (float) (Math.sin(2 * Math.PI * 220.0 * time) * modulation);
                dest[1][destOffset + i] = (float) (Math.sin(2 * Math.PI * 330.0 * time) * 0.3);
            }
        }
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
