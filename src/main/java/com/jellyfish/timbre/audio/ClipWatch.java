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

/**
 * Detects sustained clipping (amended decision 3'): more than 1 % of the samples at full scale in about the
 * last second.
 *
 * <p><b>Window rule.</b> The watch remembers the blocks given to {@link #add}. The window is the SHORTEST run of
 * most recent blocks whose frames add up to at least {@code sampleRate} frames (one second); while fewer frames
 * than that have been added in total, the window is every block so far. {@link #warning()} is true when, over
 * the window, {@code fullScaleSamples / (frames * channels)} is STRICTLY greater than 1 %, that is when
 * {@code 100 * fullScale > frames * channels}. Exactly 1 % is not a warning. A full-scale sample is one with
 * {@code |v| >= 0.999} ({@link #FULL_SCALE}); the caller counts them.
 *
 * <p>{@link #everClipped()} latches: it is true once {@link #warning()} has been true after any {@code add}.
 *
 * <p><b>Threading:</b> not thread-safe; used by the capture thread only. {@link #add} does not allocate (the
 * block ring is allocated once, with room for one-frame blocks).
 */
public final class ClipWatch {

    /** The magnitude from which a sample counts as full scale. */
    public static final double FULL_SCALE = 0.999;

    private final int sampleRate;
    private final int channels;
    private final int[] blockFrames;
    private final int[] blockFull;
    private int head;
    private int count;
    private long frames;
    private long full;
    private boolean warning;
    private boolean everClipped;

    /**
     * Creates the watch.
     *
     * @param sampleRate frames per second (the window length), greater than zero
     * @param channels   channels per frame, at least one
     */
    public ClipWatch(int sampleRate, int channels) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (channels < 1) {
            throw new IllegalArgumentException("channels must be >= 1: " + channels);
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.blockFrames = new int[sampleRate + 2];
        this.blockFull = new int[sampleRate + 2];
    }

    /**
     * Adds one block. A block of 0 frames is ignored.
     *
     * @param blockFrameCount  frames in the block, at least 0
     * @param fullScaleSamples samples in the block (over all channels) with {@code |v| >= 0.999}, from 0 to
     *                         {@code blockFrameCount * channels}
     * @throws IllegalArgumentException if a count is out of range
     */
    public void add(int blockFrameCount, int fullScaleSamples) {
        if (blockFrameCount < 0 || fullScaleSamples < 0 || (long) fullScaleSamples > (long) blockFrameCount * channels) {
            throw new IllegalArgumentException("bad block: " + blockFrameCount + " frames, " + fullScaleSamples
                    + " full-scale samples");
        }
        if (blockFrameCount == 0) {
            return;
        }
        int tail = (head + count) % blockFrames.length;
        blockFrames[tail] = blockFrameCount;
        blockFull[tail] = fullScaleSamples;
        count++;
        frames += blockFrameCount;
        full += fullScaleSamples;
        while (count > 1 && frames - blockFrames[head] >= sampleRate) {
            frames -= blockFrames[head];
            full -= blockFull[head];
            head = (head + 1) % blockFrames.length;
            count--;
        }
        warning = 100L * full > frames * channels;
        if (warning) {
            everClipped = true;
        }
    }

    /** @return true while more than 1 % of the samples in the window are at full scale */
    public boolean warning() {
        return warning;
    }

    /** @return true once {@link #warning()} has been true */
    public boolean everClipped() {
        return everClipped;
    }
}
