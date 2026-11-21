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

import java.util.Objects;

/**
 * Pure helpers of the capture probe, kept separate so the normal test suite can check them: 16-bit
 * little-endian PCM to planar float (the same scaling as the playback side: {@code v / 32768}), peak and RMS
 * of a window in dBFS, a nearest-rank percentile, and the diagnostic aggregates of the {@code diag} mode (DC
 * mean, full-scale share, zero crossings, an 8-bucket magnitude histogram).
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class CaptureMath {

    private CaptureMath() {
    }

    /**
     * Converts interleaved 16-bit signed little-endian PCM to planar float: {@code -32768 -> -1.0},
     * {@code 32767 -> 32767 / 32768}.
     *
     * @param src      the bytes
     * @param offset   the first byte
     * @param frames   the number of frames
     * @param channels the channel count, at least 1
     * @param dst      the output, {@code dst[channel][frame]}, each at least {@code frames} long
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static void pcm16LeToFloat(byte[] src, int offset, int frames, int channels, float[][] dst) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dst, "dst");
        if (channels < 1 || frames < 0 || offset < 0 || offset + frames * channels * 2 > src.length
                || dst.length < channels) {
            throw new IllegalArgumentException("bad arguments: offset " + offset + ", frames " + frames
                    + ", channels " + channels);
        }
        int p = offset;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int v = (src[p] & 0xFF) | (src[p + 1] << 8);
                dst[c][f] = (short) v / 32768f;
                p += 2;
            }
        }
    }

    /**
     * Converts a linear amplitude to dBFS.
     *
     * @param linear the amplitude, at least 0
     * @return {@code 20 log10(linear)}; negative infinity for 0
     */
    public static double dbfs(double linear) {
        return linear <= 0 ? Double.NEGATIVE_INFINITY : 20.0 * Math.log10(linear);
    }

    /**
     * Returns the peak of a window in dBFS.
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return {@code dbfs(max |v|)}; negative infinity for digital silence or an empty window
     */
    public static double peakDbfs(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        double peak = 0;
        for (int i = from; i < to; i++) {
            peak = Math.max(peak, Math.abs(samples[i]));
        }
        return dbfs(peak);
    }

    /**
     * Returns the RMS of a window in dBFS.
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return {@code dbfs(sqrt(mean(v^2)))}; negative infinity for digital silence or an empty window
     */
    public static double rmsDbfs(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        if (to <= from) {
            return Double.NEGATIVE_INFINITY;
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) samples[i] * samples[i];
        }
        return dbfs(Math.sqrt(sum / (to - from)));
    }

    /**
     * Nearest-rank percentile of sorted values: the value at index {@code ceil(p / 100 * n) - 1}, clamped
     * to the list.
     *
     * @param sorted the values in ascending order, at least one
     * @param p      the percentile in [0, 100]
     * @return the percentile value
     * @throws IllegalArgumentException if the list is empty or {@code p} is out of range
     */
    public static double percentile(double[] sorted, double p) {
        Objects.requireNonNull(sorted, "sorted");
        if (sorted.length == 0 || !(p >= 0 && p <= 100)) {
            throw new IllegalArgumentException("empty list or percentile out of range: " + p);
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    /** Magnitude at or above which a sample counts as full scale. */
    public static final double FULL_SCALE = 0.999;

    /**
     * Returns the mean of a window (the DC offset as a fraction of full scale).
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return the mean; 0 for an empty window
     */
    public static double dcMean(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        if (to <= from) {
            return 0;
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += samples[i];
        }
        return sum / (to - from);
    }

    /**
     * Returns the share of samples whose magnitude is at least {@value #FULL_SCALE}.
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return the share in [0, 1]; 0 for an empty window
     */
    public static double fullScaleShare(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        if (to <= from) {
            return 0;
        }
        int n = 0;
        for (int i = from; i < to; i++) {
            if (Math.abs(samples[i]) >= FULL_SCALE) {
                n++;
            }
        }
        return (double) n / (to - from);
    }

    /**
     * Counts sign changes between neighbouring samples; 0 counts as non-negative.
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return the number of crossings, at most {@code to - from - 1}
     */
    public static int zeroCrossings(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        int n = 0;
        for (int i = from + 1; i < to; i++) {
            if ((samples[i - 1] < 0) != (samples[i] < 0)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Converts a crossing count of a window to crossings per second.
     *
     * @param crossings  the count
     * @param frames     the window length in frames, greater than 0
     * @param sampleRate the sample rate
     * @return crossings per second
     */
    public static double perSecond(int crossings, int frames, double sampleRate) {
        if (frames <= 0) {
            throw new IllegalArgumentException("frames must be > 0: " + frames);
        }
        return crossings * sampleRate / frames;
    }

    /**
     * An 8-bucket histogram of the magnitudes: bucket {@code k} holds {@code k/8 <= |v| < (k+1)/8}; a
     * magnitude of 1 or more goes into the last bucket.
     *
     * @param samples the samples
     * @param from    the first index
     * @param to      the end index (exclusive)
     * @return the 8 counts
     */
    public static int[] histogram8(float[] samples, int from, int to) {
        Objects.requireNonNull(samples, "samples");
        int[] h = new int[8];
        for (int i = from; i < to; i++) {
            h[Math.min(7, (int) (Math.abs(samples[i]) * 8))]++;
        }
        return h;
    }
}
