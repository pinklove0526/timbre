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
 * A device that plays 16-bit signed little-endian stereo PCM. Abstracts the Java Sound line so the
 * engine can be tested with a fake.
 *
 * <p><b>Threading contract:</b> {@link #write}, {@link #drain} and {@link #open} are called from one
 * writer thread at a time. {@link #stop()}, {@link #start()}, {@link #close()} and
 * {@link #positionFrames()} may be called from any other thread, including while the writer is blocked
 * in {@link #write} or {@link #drain}.
 *
 * <p><b>Measured device behaviour this contract allows for</b> (Milestone 2 Phase A): the position can
 * lead true playback by up to one buffer after {@code start()} and {@code stop()}; {@code write} on a
 * stopped line returns short or zero; a line only really starts running on the first write after
 * {@code start()}.
 */
public interface AudioSink extends AutoCloseable {

    /**
     * Opens the device for stereo 16-bit output.
     *
     * @param sampleRate   frames per second
     * @param bufferFrames requested device buffer in frames
     * @throws AudioDeviceException if the device rejects the rate or cannot be opened
     */
    void open(int sampleRate, int bufferFrames) throws AudioDeviceException;

    /** Starts (or resumes after {@link #stop()}) output of queued and future audio. */
    void start();

    /** Pauses output; queued audio is retained. Unblocks a writer that is blocked in {@link #write}. */
    void stop();

    /**
     * Queues audio, blocking while the device buffer is full.
     *
     * @param pcm    interleaved little-endian 16-bit stereo bytes
     * @param offset first byte
     * @param length number of bytes (a multiple of 4)
     * @return bytes accepted: may be short or zero if the sink is stopped, closed or flushed; never
     *         blocks forever once stopped or closed
     */
    int write(byte[] pcm, int offset, int length);

    /** Blocks until queued audio has played; returns promptly if the sink is stopped or closed. */
    void drain();

    /**
     * Frames reported as played since {@link #open}. Monotonic in steady state but, on some devices,
     * ahead of true playback by up to one buffer after start and stop.
     *
     * @return raw device position in frames
     */
    long positionFrames();

    /** @return the granted device buffer in frames */
    int bufferFrames();

    /** Closes the device and discards queued audio. Idempotent; unblocks a blocked writer or drain. */
    @Override
    void close();
}
