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

import com.jellyfish.timbre.dsp.PeakPyramid;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.SampleSource;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns the clips of one track into per-pixel-column min, max and RMS for ONE channel lane.
 *
 * <p>Column {@code i} covers the timeline frames {@code [frameAtX(i), frameAtX(i + 1))} of the
 * {@link TimeAxis}. Timeline frame {@code t} of a clip is source frame {@code sourceStart + (t - timelineStart)}.
 * Frames outside every clip (gaps, before the first and after the last clip) are silence: zeros that
 * contribute 0 to min, max and the sum of squares and count in the frame total of the RMS (so a column that
 * is half gap has its minimum and maximum widened to include 0).
 *
 * <p>The mode follows the zoom:
 * <ul>
 *   <li>{@code framesPerPixel < 1}: {@link LaneColumns.Mode#SAMPLE_LINES}, one value per visible frame read
 *       from the sources;</li>
 *   <li>{@code framesPerPixel >= 512} (that is, {@link PeakPyramid#levelFor} is not -1) and every visible clip's
 *       pyramid is ready: {@link LaneColumns.Mode#PEAKS}. Each column aggregates the WHOLE pyramid blocks that
 *       cover its source range, a block-granular superset of the column's frames (min and max can only be
 *       wider, never narrower, than the exact values; the extra is under half a pixel by the choice of
 *       level). At most 9 blocks are aggregated per clip piece of a column, so at most 11 in a column that crosses a clip boundary;</li>
 *   <li>{@code 1 <= framesPerPixel < 512}: {@link LaneColumns.Mode#DIRECT}, exact values read from the
 *       samples (non-finite samples are sanitised like in the pyramid);</li>
 *   <li>otherwise (pyramid not ready, zoomed far out): {@link LaneColumns.Mode#PLACEHOLDER}, all zeros.</li>
 * </ul>
 *
 * <p>The clips must be sorted by timeline start and must not overlap (as {@code Track.clips()} guarantees),
 * and every source must have more channels than the lane index.
 *
 * <p><b>Threading:</b> an instance owns scratch buffers and is not thread-safe; use one per thread. The
 * clip list, the pyramid lookup and the sources are only read; the lookup is called on the calling thread and
 * may return {@code null} for a pyramid that is not ready. Sources must allow concurrent reads if shared.
 */
public final class LaneSampler {

    /** Zoom at which peaks take over from direct reading (twice the base block). */
    static final double PEAKS_FROM_FRAMES_PER_PIXEL = 2.0 * PeakPyramid.BASE_BLOCK_FRAMES;

    private static final int CHUNK = 4096;

    private float[][] scratch = new float[0][];

    /** Creates a sampler with empty scratch buffers. */
    public LaneSampler() {
        // buffers grow on demand
    }

    /**
     * Samples one lane into a new result.
     *
     * @param clips    the clips of the track, sorted and non-overlapping
     * @param channel  the channel of the lane
     * @param pyramids returns the pyramid of a source, or null if it is not ready
     * @param axis     the time axis
     * @param widthPx  the number of pixel columns, greater than 0
     * @return the lane columns
     * @throws IllegalArgumentException if an argument is out of range or a source lacks the channel
     */
    public LaneColumns sample(List<Clip> clips, int channel, Function<SampleSource, PeakPyramid> pyramids,
            TimeAxis axis, int widthPx) {
        return sample(clips, channel, pyramids, axis, widthPx, new LaneColumns());
    }

    /**
     * Samples one lane into a reusable result.
     *
     * @param clips    the clips of the track, sorted and non-overlapping
     * @param channel  the channel of the lane
     * @param pyramids returns the pyramid of a source, or null if it is not ready
     * @param axis     the time axis
     * @param widthPx  the number of pixel columns, greater than 0
     * @param out      the result to overwrite
     * @return {@code out}
     * @throws IllegalArgumentException if an argument is out of range or a source lacks the channel
     */
    public LaneColumns sample(List<Clip> clips, int channel, Function<SampleSource, PeakPyramid> pyramids,
            TimeAxis axis, int widthPx, LaneColumns out) {
        Objects.requireNonNull(clips, "clips");
        Objects.requireNonNull(pyramids, "pyramids");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(out, "out");
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0: " + channel);
        }
        if (widthPx <= 0) {
            throw new IllegalArgumentException("widthPx must be > 0: " + widthPx);
        }
        for (Clip c : clips) {
            if (c.source().channelCount() <= channel) {
                throw new IllegalArgumentException("a clip's source has " + c.source().channelCount()
                        + " channels, lane channel is " + channel);
            }
        }
        double fpp = axis.framesPerPixel();
        if (fpp < 1.0) {
            sampleLines(clips, channel, axis, widthPx, out);
            return out;
        }
        long visibleStart = axis.frameAtX(0);
        long visibleEnd = axis.frameAtX(widthPx);
        boolean peaks = fpp >= PEAKS_FROM_FRAMES_PER_PIXEL;
        PeakPyramid[] lookups = null;
        if (peaks) {
            lookups = new PeakPyramid[clips.size()];
            for (int k = 0; k < lookups.length; k++) {
                Clip c = clips.get(k);
                if (c.timelineEnd() <= visibleStart || c.timelineStart() >= visibleEnd) {
                    continue;
                }
                PeakPyramid p = pyramids.apply(c.source());
                if (p == null || p.levelFor(fpp) < 0) {
                    out.reset(LaneColumns.Mode.PLACEHOLDER, widthPx);
                    Arrays.fill(out.minArray(), 0, widthPx, 0f);
                    Arrays.fill(out.maxArray(), 0, widthPx, 0f);
                    Arrays.fill(out.rmsArray(), 0, widthPx, 0f);
                    return out;
                }
                lookups[k] = p;
            }
            out.reset(LaneColumns.Mode.PEAKS, widthPx);
        } else {
            out.reset(LaneColumns.Mode.DIRECT, widthPx);
        }
        columns(clips, channel, lookups, axis, widthPx, out);
        return out;
    }

    // ------------------------------------------------------------------ PEAKS and DIRECT

    private void columns(List<Clip> clips, int channel, PeakPyramid[] pyramids, TimeAxis axis, int widthPx,
            LaneColumns out) {
        float[] minOut = out.minArray();
        float[] maxOut = out.maxArray();
        float[] rmsOut = out.rmsArray();
        double fpp = axis.framesPerPixel();
        int first = 0; // first clip that may still touch the current column
        long colStart = axis.frameAtX(0);
        for (int x = 0; x < widthPx; x++) {
            long colEnd = axis.frameAtX(x + 1);
            while (first < clips.size() && clips.get(first).timelineEnd() <= colStart) {
                first++;
            }
            float lo = Float.POSITIVE_INFINITY;
            float hi = Float.NEGATIVE_INFINITY;
            double sumSq = 0;
            long frames = 0;
            long pieces = 0;
            int blocks = 0;
            for (int k = first; k < clips.size(); k++) {
                Clip c = clips.get(k);
                if (c.timelineStart() >= colEnd) {
                    break;
                }
                long from = Math.max(colStart, c.timelineStart());
                long to = Math.min(colEnd, c.timelineEnd());
                if (from >= to) {
                    continue;
                }
                long s0 = c.sourceStart() + (from - c.timelineStart());
                long s1 = s0 + (to - from);
                pieces += to - from;
                if (pyramids != null) {
                    PeakPyramid p = pyramids[k];
                    int level = p.levelFor(fpp);
                    long bf = p.blockFrames(level);
                    int firstBlock = (int) (s0 / bf);
                    int endBlock = (int) Math.min(p.blockCount(level), (s1 + bf - 1) / bf);
                    for (int b = firstBlock; b < endBlock; b++) {
                        lo = Math.min(lo, p.blockMin(channel, level, b));
                        hi = Math.max(hi, p.blockMax(channel, level, b));
                        sumSq += p.blockSumSquares(channel, level, b);
                        frames += p.framesInBlock(level, b);
                    }
                    blocks += endBlock - firstBlock;
                } else {
                    int n = (int) (to - from);
                    float[] buf = read(c.source(), s0, n, channel);
                    for (int i = 0; i < n; i++) {
                        float v = sanitize(buf[i]);
                        lo = Math.min(lo, v);
                        hi = Math.max(hi, v);
                        sumSq += (double) v * v;
                    }
                    frames += n;
                }
            }
            long gap = (colEnd - colStart) - pieces; // silence: not inside any clip
            if (gap > 0) {
                lo = Math.min(lo, 0f);
                hi = Math.max(hi, 0f);
                frames += gap;
            }
            if (frames == 0) {
                minOut[x] = 0f;
                maxOut[x] = 0f;
                rmsOut[x] = 0f;
            } else {
                minOut[x] = lo;
                maxOut[x] = hi;
                rmsOut[x] = (float) Math.sqrt(sumSq / frames);
            }
            out.noteBlocks(blocks);
            colStart = colEnd;
        }
    }

    // ------------------------------------------------------------------ SAMPLE_LINES

    private void sampleLines(List<Clip> clips, int channel, TimeAxis axis, int widthPx, LaneColumns out) {
        out.reset(LaneColumns.Mode.SAMPLE_LINES, widthPx);
        long start = axis.frameAtX(0);
        long end = axis.frameAtX(widthPx);
        int count = (int) (end - start + 1);
        float[] samples = out.samplesFor(start, count);
        Arrays.fill(samples, 0, count, 0f);
        for (Clip c : clips) {
            long from = Math.max(start, c.timelineStart());
            long to = Math.min(start + count, c.timelineEnd());
            for (long t = from; t < to; ) {
                int n = (int) Math.min(CHUNK, to - t);
                float[] buf = read(c.source(), c.sourceStart() + (t - c.timelineStart()), n, channel);
                for (int i = 0; i < n; i++) {
                    samples[(int) (t - start) + i] = sanitize(buf[i]);
                }
                t += n;
            }
        }
        float[] minOut = out.minArray();
        float[] maxOut = out.maxArray();
        float[] rmsOut = out.rmsArray();
        for (int x = 0; x < widthPx; x++) {
            float v = samples[(int) (axis.frameAtX(x) - start)];
            minOut[x] = v;
            maxOut[x] = v;
            rmsOut[x] = Math.abs(v);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Reads {@code n} frames of one channel (n at most {@link #CHUNK}) and returns the scratch array. */
    private float[] read(SampleSource source, long sourceFrame, int n, int channel) {
        int channels = source.channelCount();
        if (scratch.length < channels) {
            scratch = new float[channels][CHUNK];
        }
        source.read(sourceFrame, n, scratch, 0);
        return scratch[channel];
    }

    private static float sanitize(float v) {
        if (Float.isNaN(v)) {
            return 0f;
        }
        if (v == Float.POSITIVE_INFINITY) {
            return Float.MAX_VALUE;
        }
        if (v == Float.NEGATIVE_INFINITY) {
            return -Float.MAX_VALUE;
        }
        return v;
    }
}
