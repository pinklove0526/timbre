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
 * Detects a silent input (a muted or dead microphone): digital silence for 3 seconds or more.
 *
 * <p>Each block is judged by its peak over all channels. Consecutive frames of blocks whose peak is below
 * {@link #THRESHOLD} (-90 dBFS, {@code 10^(-90/20)}) accumulate; a block whose peak is at or above it resets the
 * count to 0. {@link #warning()} is true while the accumulated frames are at least {@code 3 * sampleRate};
 * {@link #everSilentWarning()} latches once the warning has been raised.
 *
 * <p><b>Threading:</b> not thread-safe; used by the capture thread only. Allocation-free.
 */
public final class SilenceWatch {

    /** The peak below which a block counts as digital silence: -90 dBFS. */
    public static final double THRESHOLD = Math.pow(10.0, -90.0 / 20.0);

    private final long limit;
    private long silentFrames;
    private boolean everWarned;

    /**
     * Creates the watch.
     *
     * @param sampleRate frames per second, greater than zero
     */
    public SilenceWatch(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        this.limit = 3L * sampleRate;
    }

    /**
     * Adds one block. A block of 0 frames changes nothing.
     *
     * @param frames    frames in the block, at least 0
     * @param blockPeak the largest magnitude in the block over all channels
     * @throws IllegalArgumentException if {@code frames} is negative
     */
    public void add(int frames, float blockPeak) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must be >= 0: " + frames);
        }
        if (frames == 0) {
            return;
        }
        if (Math.abs(blockPeak) < THRESHOLD) {
            silentFrames += frames;
            if (silentFrames >= limit) {
                everWarned = true;
            }
        } else {
            silentFrames = 0;
        }
    }

    /** @return true while the input has been silent for at least 3 seconds */
    public boolean warning() {
        return silentFrames >= limit;
    }

    /** @return true once {@link #warning()} has been true */
    public boolean everSilentWarning() {
        return everWarned;
    }
}
