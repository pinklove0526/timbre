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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Quiet test tones and the cap on total audible time for {@link AudioProbe}.
 *
 * <p>Dev tool, not shipped. Single-threaded use.
 */
final class ProbeTone {

    /** About -24 dBFS. */
    static final double AMPLITUDE = Math.pow(10.0, -24.0 / 20.0);
    /** Fade length at both ends of every segment. */
    static final int FADE_MS = 20;
    /** Cap on audible seconds across all runs, recorded in a ledger file. */
    static final double LEDGER_LIMIT_SECONDS = 30.0;

    private ProbeTone() {
    }

    /**
     * Builds a 440 Hz stereo 16-bit little-endian segment with linear 20 ms fades.
     *
     * @param rate    sample rate in Hz
     * @param seconds duration
     * @return interleaved PCM bytes (4 bytes per frame)
     */
    static byte[] segment(int rate, double seconds) {
        if (rate <= 0 || seconds <= 0) {
            throw new IllegalArgumentException("rate and seconds must be positive");
        }
        int frames = (int) Math.round(rate * seconds);
        int fade = rate * FADE_MS / 1000;
        byte[] out = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            double env = 1.0;
            if (i < fade) {
                env = (double) i / fade;
            } else if (i >= frames - fade) {
                env = (double) (frames - 1 - i) / fade;
            }
            short s = (short) Math.round(Math.sin(2.0 * Math.PI * 440.0 * i / rate) * AMPLITUDE * env * 32767.0);
            int p = i * 4;
            out[p] = (byte) s;
            out[p + 1] = (byte) (s >> 8);
            out[p + 2] = (byte) s;
            out[p + 3] = (byte) (s >> 8);
        }
        return out;
    }

    /**
     * Reserves audible time in the ledger; refuses if the cap would be exceeded.
     *
     * @param ledger  ledger file
     * @param label   what the sound is for
     * @param seconds planned audible seconds
     * @throws IllegalStateException if the total would exceed the limit
     */
    static void reserve(Path ledger, String label, double seconds) {
        Objects.requireNonNull(ledger, "ledger");
        try {
            double total = 0;
            if (Files.exists(ledger)) {
                List<String> lines = Files.readAllLines(ledger);
                for (String l : lines) {
                    String[] f = l.split("\\s+");
                    if (f.length >= 2) {
                        total += Double.parseDouble(f[f.length - 1]);
                    }
                }
            }
            if (total + seconds > LEDGER_LIMIT_SECONDS) {
                throw new IllegalStateException("audible budget exceeded: " + total + " s used, " + seconds
                        + " s requested, limit " + LEDGER_LIMIT_SECONDS + " s");
            }
            Files.writeString(ledger, label.replace(' ', '_') + " " + seconds + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot use ledger " + ledger + ": " + e, e);
        }
    }
}
