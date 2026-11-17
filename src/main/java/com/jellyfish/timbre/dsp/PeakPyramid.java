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
package com.jellyfish.timbre.dsp;

import com.jellyfish.timbre.model.SampleSource;
import java.util.Objects;

/**
 * A multi-resolution summary of a {@link SampleSource} for drawing waveforms: for every channel and block
 * of frames it keeps the minimum, the maximum and the sum of squares (for the RMS).
 *
 * <p>Level 0 has blocks of {@link #BASE_BLOCK_FRAMES} frames; every following level has blocks
 * {@link #LEVEL_FACTOR} times as long (a block of level {@code k + 1} summarises up to four blocks of level
 * {@code k}). Levels continue until a level has exactly one block; an empty source has no levels. The last
 * block of each level may cover fewer frames. Non-finite samples are sanitised before aggregation: NaN
 * becomes 0, +Infinity becomes {@link Float#MAX_VALUE} and -Infinity becomes {@code -Float.MAX_VALUE}.
 *
 * <p><b>Threading:</b> immutable and thread-safe once {@link #build(SampleSource)} has returned; all methods
 * may be called concurrently from any thread. {@code build} reads the source on the calling thread and is
 * as thread-safe as the source's {@code read}.
 */
public final class PeakPyramid {

    /** Frames in a block of level 0. */
    public static final int BASE_BLOCK_FRAMES = 256;
    /** Ratio of the block lengths of two neighbouring levels. */
    public static final int LEVEL_FACTOR = 4;

    private static final int READ_FRAMES = BASE_BLOCK_FRAMES * 64;

    private final int channelCount;
    private final long frameCount;
    private final int[] blockCounts;
    private final long[] blockFrames;
    // [level][channel][block]
    private final float[][][] mins;
    private final float[][][] maxs;
    private final double[][][] sumSquares;

    private PeakPyramid(int channelCount, long frameCount, int[] blockCounts, long[] blockFrames,
            float[][][] mins, float[][][] maxs, double[][][] sumSquares) {
        this.channelCount = channelCount;
        this.frameCount = frameCount;
        this.blockCounts = blockCounts;
        this.blockFrames = blockFrames;
        this.mins = mins;
        this.maxs = maxs;
        this.sumSquares = sumSquares;
    }

    /**
     * Builds the pyramid by reading the whole source once, in blocks.
     *
     * @param source the source to summarise
     * @return the pyramid
     * @throws NullPointerException     if {@code source} is null
     * @throws IllegalArgumentException if the source is too long for the pyramid (more than
     *                                  {@code Integer.MAX_VALUE} level-0 blocks)
     */
    public static PeakPyramid build(SampleSource source) {
        Objects.requireNonNull(source, "source");
        int channels = source.channelCount();
        long frames = source.frameCount();
        if (frames == 0) {
            return new PeakPyramid(channels, 0, new int[0], new long[0], new float[0][][], new float[0][][],
                    new double[0][][]);
        }
        long base = (frames + BASE_BLOCK_FRAMES - 1) / BASE_BLOCK_FRAMES;
        if (base > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("source too long for a peak pyramid: " + frames + " frames");
        }
        int levels = 1;
        for (long n = base; n > 1; n = (n + LEVEL_FACTOR - 1) / LEVEL_FACTOR) {
            levels++;
        }
        int[] counts = new int[levels];
        long[] bframes = new long[levels];
        float[][][] mn = new float[levels][][];
        float[][][] mx = new float[levels][][];
        double[][][] sq = new double[levels][][];
        long n = base;
        long bf = BASE_BLOCK_FRAMES;
        for (int l = 0; l < levels; l++) {
            counts[l] = (int) n;
            bframes[l] = bf;
            mn[l] = new float[channels][(int) n];
            mx[l] = new float[channels][(int) n];
            sq[l] = new double[channels][(int) n];
            n = (n + LEVEL_FACTOR - 1) / LEVEL_FACTOR;
            bf *= LEVEL_FACTOR;
        }
        fillBase(source, channels, frames, mn[0], mx[0], sq[0]);
        for (int l = 1; l < levels; l++) {
            for (int c = 0; c < channels; c++) {
                float[] lowMin = mn[l - 1][c];
                float[] lowMax = mx[l - 1][c];
                double[] lowSq = sq[l - 1][c];
                for (int b = 0; b < counts[l]; b++) {
                    int first = b * LEVEL_FACTOR;
                    int end = Math.min(first + LEVEL_FACTOR, counts[l - 1]);
                    float lo = lowMin[first];
                    float hi = lowMax[first];
                    double s = lowSq[first];
                    for (int i = first + 1; i < end; i++) {
                        lo = Math.min(lo, lowMin[i]);
                        hi = Math.max(hi, lowMax[i]);
                        s += lowSq[i];
                    }
                    mn[l][c][b] = lo;
                    mx[l][c][b] = hi;
                    sq[l][c][b] = s;
                }
            }
        }
        return new PeakPyramid(channels, frames, counts, bframes, mn, mx, sq);
    }

    private static void fillBase(SampleSource source, int channels, long frames, float[][] mn, float[][] mx,
            double[][] sq) {
        float[][] buf = new float[channels][READ_FRAMES];
        for (long pos = 0; pos < frames; pos += READ_FRAMES) {
            int n = (int) Math.min(READ_FRAMES, frames - pos);
            source.read(pos, n, buf, 0);
            int firstBlock = (int) (pos / BASE_BLOCK_FRAMES);
            for (int c = 0; c < channels; c++) {
                float[] data = buf[c];
                for (int off = 0; off < n; off += BASE_BLOCK_FRAMES) {
                    int end = Math.min(off + BASE_BLOCK_FRAMES, n);
                    float lo = Float.POSITIVE_INFINITY;
                    float hi = Float.NEGATIVE_INFINITY;
                    double s = 0;
                    for (int i = off; i < end; i++) {
                        float v = sanitize(data[i]);
                        if (v < lo) {
                            lo = v;
                        }
                        if (v > hi) {
                            hi = v;
                        }
                        s += (double) v * v;
                    }
                    int block = firstBlock + off / BASE_BLOCK_FRAMES;
                    mn[c][block] = lo;
                    mx[c][block] = hi;
                    sq[c][block] = s;
                }
            }
        }
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

    /** @return the number of channels of the summarised source */
    public int channelCount() {
        return channelCount;
    }

    /** @return the number of frames of the summarised source */
    public long frameCount() {
        return frameCount;
    }

    /** @return the number of levels; 0 for an empty source */
    public int levelCount() {
        return blockCounts.length;
    }

    /**
     * Returns the nominal block length of a level.
     *
     * @param level the level, {@code 0 <= level < levelCount()}
     * @return frames per block ({@code 256 * 4^level}); the last block may hold fewer
     * @throws IndexOutOfBoundsException if the level does not exist
     */
    public long blockFrames(int level) {
        checkLevel(level);
        return blockFrames[level];
    }

    /**
     * Returns the number of blocks of a level.
     *
     * @param level the level, {@code 0 <= level < levelCount()}
     * @return the block count, at least 1
     * @throws IndexOutOfBoundsException if the level does not exist
     */
    public int blockCount(int level) {
        checkLevel(level);
        return blockCounts[level];
    }

    /**
     * Returns the number of source frames a block covers.
     *
     * @param level the level
     * @param block the block, {@code 0 <= block < blockCount(level)}
     * @return {@code blockFrames(level)}, or less for the last block
     * @throws IndexOutOfBoundsException if the level or block does not exist
     */
    public long framesInBlock(int level, int block) {
        checkBlock(level, block);
        long start = block * blockFrames[level];
        return Math.min(blockFrames[level], frameCount - start);
    }

    /**
     * Chooses the level to draw at a zoom: the largest level whose blocks are at most half a pixel wide
     * ({@code blockFrames(level) <= framesPerPixel / 2}); a level past the top is clamped to the top.
     *
     * @param framesPerPixel source frames per pixel column, greater than 0
     * @return the level, or -1 if even level 0 is too coarse (or the pyramid is empty)
     * @throws IllegalArgumentException if {@code framesPerPixel} is not a positive finite number
     */
    public int levelFor(double framesPerPixel) {
        if (!(framesPerPixel > 0) || Double.isInfinite(framesPerPixel)) {
            throw new IllegalArgumentException("framesPerPixel must be positive and finite: " + framesPerPixel);
        }
        int best = -1;
        double limit = framesPerPixel / 2;
        for (int l = 0; l < blockFrames.length; l++) {
            if (blockFrames[l] <= limit) {
                best = l;
            } else {
                break;
            }
        }
        return best;
    }

    /**
     * Aggregates whole blocks of one level exactly. {@code out} receives {@code {min, max, rms}} where
     * {@code rms = sqrt(sum of squares / frames)} over the frames of those blocks. The block range is
     * clamped to the existing blocks; an empty range gives zeros.
     *
     * @param channel    the channel
     * @param level      the level
     * @param firstBlock first block, inclusive
     * @param endBlock   last block, exclusive
     * @param out        array of at least 3 floats receiving min, max and rms
     * @throws IndexOutOfBoundsException if the channel or level does not exist
     * @throws IllegalArgumentException  if {@code out} is shorter than 3
     */
    public void summarize(int channel, int level, long firstBlock, long endBlock, float[] out) {
        Objects.requireNonNull(out, "out");
        if (out.length < 3) {
            throw new IllegalArgumentException("out needs at least 3 elements: " + out.length);
        }
        checkChannel(channel);
        checkLevel(level);
        int count = blockCounts[level];
        long first = Math.max(0, firstBlock);
        long end = Math.min(count, endBlock);
        if (first >= end) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 0f;
            return;
        }
        float[] lowMin = mins[level][channel];
        float[] lowMax = maxs[level][channel];
        double[] lowSq = sumSquares[level][channel];
        float lo = lowMin[(int) first];
        float hi = lowMax[(int) first];
        double s = lowSq[(int) first];
        for (int i = (int) first + 1; i < (int) end; i++) {
            lo = Math.min(lo, lowMin[i]);
            hi = Math.max(hi, lowMax[i]);
            s += lowSq[i];
        }
        long frames = Math.min(end * blockFrames[level], frameCount) - first * blockFrames[level];
        out[0] = lo;
        out[1] = hi;
        out[2] = (float) Math.sqrt(s / frames);
    }

    /**
     * Returns the minimum of one block.
     *
     * @param channel the channel
     * @param level   the level
     * @param block   the block
     * @return the sanitised minimum sample of the block
     * @throws IndexOutOfBoundsException if an index does not exist
     */
    public float blockMin(int channel, int level, int block) {
        checkChannel(channel);
        checkBlock(level, block);
        return mins[level][channel][block];
    }

    /**
     * Returns the maximum of one block.
     *
     * @param channel the channel
     * @param level   the level
     * @param block   the block
     * @return the sanitised maximum sample of the block
     * @throws IndexOutOfBoundsException if an index does not exist
     */
    public float blockMax(int channel, int level, int block) {
        checkChannel(channel);
        checkBlock(level, block);
        return maxs[level][channel][block];
    }

    /**
     * Returns the sum of squares of one block.
     *
     * @param channel the channel
     * @param level   the level
     * @param block   the block
     * @return the sum of the squared sanitised samples of the block
     * @throws IndexOutOfBoundsException if an index does not exist
     */
    public double blockSumSquares(int channel, int level, int block) {
        checkChannel(channel);
        checkBlock(level, block);
        return sumSquares[level][channel][block];
    }

    private void checkChannel(int channel) {
        if (channel < 0 || channel >= channelCount) {
            throw new IndexOutOfBoundsException("channel " + channel + " not in [0, " + channelCount + ")");
        }
    }

    private void checkLevel(int level) {
        if (level < 0 || level >= blockCounts.length) {
            throw new IndexOutOfBoundsException("level " + level + " not in [0, " + blockCounts.length + ")");
        }
    }

    private void checkBlock(int level, int block) {
        checkLevel(level);
        if (block < 0 || block >= blockCounts[level]) {
            throw new IndexOutOfBoundsException("block " + block + " not in [0, " + blockCounts[level] + ")");
        }
    }
}
