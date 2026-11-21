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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An immutable {@link SampleSource} over equal-size planar float chunks, built incrementally (for example by a
 * recorder) so that finishing needs no big copy. Every chunk holds {@code chunkFrames} frames except the last,
 * which may be partial.
 *
 * <p><b>Threading:</b> the built source is immutable and safe for concurrent reads; {@link #read} does not
 * allocate. The {@link Builder} is not thread-safe: one writer thread appends, and {@link Builder#build()}
 * publishes the result safely (all chunk contents are written before the source is constructed; hand the built
 * source to other threads through any safe publication, for example a volatile field or a thread join).
 */
public final class ChunkedSampleSource implements SampleSource {

    /** The default chunk size in frames. */
    public static final int DEFAULT_CHUNK_FRAMES = 65536;

    /**
     * Allocates one chunk. The default ({@link #DEFAULT}) allocates {@code new float[channels][frames]}; tests
     * inject one that throws {@link OutOfMemoryError}.
     *
     * <p><b>Threading:</b> called on the Builder's writer thread only.
     */
    @FunctionalInterface
    public interface ChunkFactory {

        /** Allocates plain arrays: {@code new float[channels][frames]}. */
        ChunkFactory DEFAULT = (frames, channels) -> new float[channels][frames];

        /**
         * Allocates a chunk.
         *
         * @param frames   frames per channel
         * @param channels channel count
         * @return arrays indexed {@code [channel][frame]}, at least {@code channels} arrays of at least
         *         {@code frames} samples each
         * @throws OutOfMemoryError if there is not enough memory
         */
        float[][] allocate(int frames, int channels);
    }

    private final int sampleRate;
    private final int channelCount;
    private final int chunkFrames;
    private final float[][][] chunks;
    private final long frameCount;

    private ChunkedSampleSource(int sampleRate, int channelCount, int chunkFrames, float[][][] chunks,
            long frameCount) {
        this.sampleRate = sampleRate;
        this.channelCount = channelCount;
        this.chunkFrames = chunkFrames;
        this.chunks = chunks;
        this.frameCount = frameCount;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int channelCount() {
        return channelCount;
    }

    @Override
    public long frameCount() {
        return frameCount;
    }

    /** @return the chunk size in frames this source was built with */
    public int chunkFrames() {
        return chunkFrames;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads may cross any number of chunk boundaries. A read of 0 frames at {@link #frameCount()} is allowed;
     * any frame at or beyond the end throws, exactly as for every {@link SampleSource}.
     */
    @Override
    public void read(long startFrame, int frames, float[][] dest, int destOffset) {
        ReadChecks.check(frameCount, channelCount, startFrame, frames, dest, destOffset);
        long pos = startFrame;
        int out = destOffset;
        int remaining = frames;
        while (remaining > 0) {
            int chunk = (int) (pos / chunkFrames);
            int within = (int) (pos % chunkFrames);
            int n = Math.min(remaining, chunkFrames - within);
            float[][] data = chunks[chunk];
            for (int c = 0; c < channelCount; c++) {
                System.arraycopy(data[c], within, dest[c], out, n);
            }
            pos += n;
            out += n;
            remaining -= n;
        }
    }

    /**
     * Collects frames into chunks and builds the immutable source once.
     *
     * <p>Memory: chunks are allocated lazily, one per {@code chunkFrames} frames, when the first frame for them
     * arrives; {@link #append} allocates nothing else except for the occasional growth of the internal chunk
     * list. The last chunk keeps its full size after {@link #build()}.
     *
     * <p><b>Threading:</b> not thread-safe; use from one writer thread.
     */
    public static final class Builder {

        private final int sampleRate;
        private final int channelCount;
        private final int chunkFrames;
        private final ChunkFactory factory;
        private final List<float[][]> chunks = new ArrayList<>();
        private long frames;
        private boolean built;

        /**
         * Creates a builder with the default chunk size and allocation.
         *
         * @param sampleRate   sample rate in Hz, greater than zero
         * @param channelCount channels, at least one
         */
        public Builder(int sampleRate, int channelCount) {
            this(sampleRate, channelCount, DEFAULT_CHUNK_FRAMES, ChunkFactory.DEFAULT);
        }

        /**
         * Creates a builder.
         *
         * @param sampleRate   sample rate in Hz, greater than zero
         * @param channelCount channels, at least one
         * @param chunkFrames  frames per chunk, at least one
         * @param factory      allocates the chunks
         * @throws IllegalArgumentException if a number is out of range
         */
        public Builder(int sampleRate, int channelCount, int chunkFrames, ChunkFactory factory) {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
            }
            if (channelCount < 1) {
                throw new IllegalArgumentException("channelCount must be >= 1: " + channelCount);
            }
            if (chunkFrames < 1) {
                throw new IllegalArgumentException("chunkFrames must be >= 1: " + chunkFrames);
            }
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.chunkFrames = chunkFrames;
            this.factory = Objects.requireNonNull(factory, "factory");
        }

        /**
         * Appends {@code count} frames from positions {@code [0, count)} of every channel array.
         *
         * <p>All chunks this call needs are allocated BEFORE any sample is copied. If an allocation fails, the
         * chunks allocated by this call are released and the error propagates, so the frames stored before the
         * call are unchanged and nothing of this block is stored.
         *
         * @param planar the samples, indexed {@code [channel][frame]}
         * @param count  frames to append, at least 0
         * @throws IllegalStateException    if {@link #build()} was called, or the factory returned a wrong shape
         * @throws IllegalArgumentException if {@code planar} has the wrong shape or {@code count} is negative
         * @throws OutOfMemoryError         if the chunk factory fails; nothing of this block is stored
         */
        public void append(float[][] planar, int count) {
            requireOpen();
            Objects.requireNonNull(planar, "planar");
            if (count < 0) {
                throw new IllegalArgumentException("count must be >= 0: " + count);
            }
            if (planar.length < channelCount) {
                throw new IllegalArgumentException("planar has " + planar.length + " channel arrays, need "
                        + channelCount);
            }
            for (int c = 0; c < channelCount; c++) {
                if (planar[c] == null || planar[c].length < count) {
                    throw new IllegalArgumentException("planar[" + c + "] is null or shorter than " + count);
                }
            }
            if (count == 0) {
                return;
            }
            long end = frames + count;
            int needed = (int) ((end + chunkFrames - 1) / chunkFrames);
            int before = chunks.size();
            try {
                while (chunks.size() < needed) {
                    chunks.add(checkShape(factory.allocate(chunkFrames, channelCount)));
                }
            } catch (OutOfMemoryError | RuntimeException e) {
                while (chunks.size() > before) {
                    chunks.remove(chunks.size() - 1);
                }
                throw e;
            }
            int src = 0;
            long pos = frames;
            while (src < count) {
                int chunk = (int) (pos / chunkFrames);
                int within = (int) (pos % chunkFrames);
                int n = Math.min(count - src, chunkFrames - within);
                float[][] data = chunks.get(chunk);
                for (int c = 0; c < channelCount; c++) {
                    System.arraycopy(planar[c], src, data[c], within, n);
                }
                src += n;
                pos += n;
            }
            frames = end;
        }

        private float[][] checkShape(float[][] chunk) {
            if (chunk == null || chunk.length < channelCount) {
                throw new IllegalStateException("the chunk factory returned too few channel arrays");
            }
            for (int c = 0; c < channelCount; c++) {
                if (chunk[c] == null || chunk[c].length < chunkFrames) {
                    throw new IllegalStateException("the chunk factory returned a short channel array");
                }
            }
            return chunk;
        }

        /**
         * @return the frames appended so far
         * @throws IllegalStateException if {@link #build()} was called
         */
        public long frameCount() {
            requireOpen();
            return frames;
        }

        /**
         * Finishes and returns the immutable source; the builder is unusable afterwards.
         *
         * @return the source (0 frames is allowed)
         * @throws IllegalStateException if called twice
         */
        public ChunkedSampleSource build() {
            requireOpen();
            built = true;
            ChunkedSampleSource source = new ChunkedSampleSource(sampleRate, channelCount, chunkFrames,
                    chunks.toArray(new float[0][][]), frames);
            chunks.clear();
            return source;
        }

        private void requireOpen() {
            if (built) {
                throw new IllegalStateException("the builder has already built its source");
            }
        }
    }
}
