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
package com.jellyfish.timbre.ui.viewmodel;

import java.util.Locale;

/**
 * Text formats for times: a fixed-width clock for readouts and a compact label for ruler ticks.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    /**
     * Formats a frame position as {@code h:mm:ss.mmm}. The hours are never omitted and the milliseconds are
     * truncated, so the format has one fixed shape and the readout does not change width.
     *
     * @param frames     position in frames, at least 0
     * @param sampleRate sample rate in Hz, greater than zero
     * @return for example {@code 0:01:05.250}
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static String clock(long frames, int sampleRate) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must be >= 0: " + frames);
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        long totalSeconds = frames / sampleRate;
        long millis = (frames % sampleRate) * 1000 / sampleRate;
        long h = totalSeconds / 3600;
        long m = totalSeconds / 60 % 60;
        long s = totalSeconds % 60;
        return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", h, m, s, millis);
    }

    /**
     * Formats a ruler label with only as many decimals as the tick step needs. Below one minute the label is
     * plain seconds ({@code 5}, {@code 0.5}, {@code 0.000005}); from one minute {@code m:ss}; from one hour
     * {@code h:mm:ss}; the decimals follow the seconds in every form.
     *
     * @param seconds     the time of the tick, at least 0
     * @param stepSeconds the distance between labelled ticks, greater than 0; sets the decimals (up to 9)
     * @return the label, for example {@code 1:05.5} for 65.5 s with a 0.5 s step
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static String ruler(double seconds, double stepSeconds) {
        if (!(seconds >= 0) || Double.isInfinite(seconds)) {
            throw new IllegalArgumentException("seconds must be >= 0 and finite: " + seconds);
        }
        if (!(stepSeconds > 0) || Double.isInfinite(stepSeconds)) {
            throw new IllegalArgumentException("stepSeconds must be positive and finite: " + stepSeconds);
        }
        int decimals = decimalsFor(stepSeconds);
        long scale = 1;
        for (int i = 0; i < decimals; i++) {
            scale *= 10;
        }
        long units = Math.round(seconds * scale);
        long whole = units / scale;
        long frac = units % scale;
        String fraction = decimals == 0 ? "" : "." + String.format(Locale.ROOT, "%0" + decimals + "d", frac);
        long h = whole / 3600;
        long m = whole / 60 % 60;
        long s = whole % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d%s", h, m, s, fraction);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%d:%02d%s", m, s, fraction);
        }
        return s + fraction;
    }

    private static int decimalsFor(double step) {
        double scale = 1;
        for (int d = 0; d <= 9; d++) {
            double v = step * scale;
            if (Math.abs(v - Math.rint(v)) <= 1e-6 * Math.max(1.0, Math.abs(v)) * 1e-3) {
                return d;
            }
            scale *= 10;
        }
        return 9;
    }
}
