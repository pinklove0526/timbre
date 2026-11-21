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
 * A device that captures 16-bit signed little-endian PCM (mono or stereo, interleaved). Abstracts the Java Sound
 * line so the recorder can be tested with a fake.
 *
 * <p><b>Single-use:</b> a source is opened once, started once, then stopped and closed. {@link #start()} after
 * {@link #stop()} or {@link #close()}, and {@link #open} or {@link #start()} after {@link #close()}, throw
 * {@link IllegalStateException}. Every recording uses a fresh source from a {@link CaptureSourceFactory}
 * (Phase A0 finding 8: a restarted line delivers stale buffered audio).
 *
 * <p><b>Threading contract:</b> {@link #open}, {@link #start()}, {@link #read} and {@link #availableBytes()} are
 * called from one thread at a time (the recorder's capture thread after {@code start}). {@link #stop()} and
 * {@link #close()} may also be called from any other thread, including while the reader is blocked in
 * {@link #read}; they wake it promptly and never make it throw.
 *
 * <p><b>Measured device behaviour this contract allows for</b> (Milestone 5 Phase A0): the device position is
 * unreliable while the buffer is full, so there is no position method; after {@link #stop()} the bytes still
 * reported by {@link #availableBytes()} cannot be read ({@link #read} returns 0).
 */
public interface CaptureSource extends AutoCloseable {

    /**
     * Opens the device for 16-bit signed little-endian capture.
     *
     * @param sampleRate   frames per second
     * @param channels     1 (mono) or 2 (stereo)
     * @param bufferFrames requested device buffer in frames
     * @throws AudioDeviceException  if the device cannot capture this format or cannot be opened; the message is
     *                               fit to show to the user
     * @throws IllegalStateException if the source was already opened or closed
     */
    void open(int sampleRate, int channels, int bufferFrames) throws AudioDeviceException;

    /**
     * Starts capturing.
     *
     * @throws IllegalStateException if not open, already started, stopped or closed
     */
    void start();

    /**
     * Reads captured bytes, blocking until data is available.
     *
     * @param dst    destination
     * @param offset first byte to write
     * @param length bytes wanted, a whole number of frames
     * @return bytes read, a whole number of frames from 0 to {@code length}; 0 after {@link #stop()} or
     *         {@link #close()} (also when another thread stops or closes the source during the call). Never
     *         throws because of a concurrent stop or close.
     */
    int read(byte[] dst, int offset, int length);

    /**
     * @return captured bytes waiting in the device buffer (after {@link #stop()} these are not readable); 0 after
     *         {@link #close()}
     */
    int availableBytes();

    /** Stops capturing and wakes a blocked reader. Idempotent; never throws. */
    void stop();

    /** Closes the device and wakes a blocked reader. Idempotent; never throws. */
    @Override
    void close();

    /** @return the granted device buffer in frames (valid after {@link #open}) */
    int bufferFrames();

    /** @return the channel count given to {@link #open} */
    int channels();

    /** @return the sample rate given to {@link #open} */
    int sampleRate();
}
