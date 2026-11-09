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

/**
 * A read-only, random-access source of planar float32 audio.
 *
 * <p><b>Threading:</b> implementations must be thread-safe for concurrent
 * {@link #read} calls and must not allocate in {@code read}, because the audio
 * thread will call it.
 */
public interface SampleSource {

    /** @return the sample rate in Hz, always greater than zero */
    int sampleRate();

    /** @return the number of channels, always at least one */
    int channelCount();

    /** @return the number of frames (samples per channel) */
    long frameCount();

    /**
     * Copies {@code frames} frames starting at {@code startFrame} into {@code dest}.
     *
     * <p>{@code dest} must hold at least {@link #channelCount()} arrays, each with room
     * for {@code destOffset + frames} samples. Only the range
     * {@code [destOffset, destOffset + frames)} of each channel array is written.
     *
     * @param startFrame first frame to read
     * @param frames     number of frames to read
     * @param dest       destination, indexed {@code [channel][frame]}
     * @param destOffset first index to write in each channel array
     * @throws IndexOutOfBoundsException if {@code [startFrame, startFrame + frames)}
     *                                   is not within {@code [0, frameCount()]}
     * @throws IllegalArgumentException  if {@code dest} or {@code destOffset} has the wrong shape
     */
    void read(long startFrame, int frames, float[][] dest, int destOffset);
}
