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
package com.jellyfish.timbre.model;

import java.util.Objects;

/**
 * An immutable in-memory {@link SampleSource} holding planar float32 audio.
 *
 * <p><b>Threading:</b> immutable and safe for concurrent reads; {@link #read} does not allocate.
 */
public final class MemorySampleSource implements SampleSource {

    private static final int MAX_FRAMES = Integer.MAX_VALUE - 8;

    private final int sampleRate;
    private final float[][] data;
    private final int frames;

    private MemorySampleSource(int sampleRate, float[][] data) {
        this.sampleRate = sampleRate;
        this.data = data;
        this.frames = data[0].length;
    }

    /**
     * Creates a source from a defensive copy of {@code channels}.
     *
     * @param sampleRate sample rate in Hz, greater than zero
     * @param channels   samples indexed {@code [channel][frame]}
     * @return the new source
     * @throws IllegalArgumentException if the arguments are invalid
     */
    public static MemorySampleSource copyOf(int sampleRate, float[][] channels) {
        validate(sampleRate, channels);
        float[][] copy = new float[channels.length][];
        for (int c = 0; c < channels.length; c++) {
            copy[c] = channels[c].clone();
        }
        return new MemorySampleSource(sampleRate, copy);
    }

    /**
     * Creates a source that takes ownership of {@code channels} without copying.
     *
     * <p><b>Warning:</b> the caller must not keep a reference to, or modify, the arrays
     * after this call; doing so breaks immutability and thread safety.
     *
     * @param sampleRate sample rate in Hz, greater than zero
     * @param channels   samples indexed {@code [channel][frame]}, adopted as is
     * @return the new source
     * @throws IllegalArgumentException if the arguments are invalid
     */
    public static MemorySampleSource adopt(int sampleRate, float[][] channels) {
        validate(sampleRate, channels);
        return new MemorySampleSource(sampleRate, channels);
    }

    private static void validate(int sampleRate, float[][] channels) {
        Objects.requireNonNull(channels, "channels");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (channels.length < 1) {
            throw new IllegalArgumentException("at least one channel is required");
        }
        int len = -1;
        for (int c = 0; c < channels.length; c++) {
            float[] ch = Objects.requireNonNull(channels[c], "channel array");
            if (len < 0) {
                len = ch.length;
            } else if (ch.length != len) {
                throw new IllegalArgumentException("channel " + c + " has " + ch.length
                        + " frames, expected " + len);
            }
        }
        if (len > MAX_FRAMES) {
            throw new IllegalArgumentException("too many frames: " + len);
        }
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channelCount() {
        return data.length;
    }

    @Override
    public long frameCount() {
        return frames;
    }

    @Override
    public void read(long startFrame, int count, float[][] dest, int destOffset) {
        ReadChecks.check(frames, data.length, startFrame, count, dest, destOffset);
        int start = (int) startFrame;
        for (int c = 0; c < data.length; c++) {
            System.arraycopy(data[c], start, dest[c], destOffset, count);
        }
    }
}
