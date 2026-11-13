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
 * Turns the raw, artefact-ridden device position into a played-frames estimate for the cursor. Pure and
 * deterministic: it starts no threads and never reads the system clock; every time is passed in as
 * nanoseconds from any monotonic origin.
 *
 * <p>Rules (Milestone 2 decision 5', from the Phase A measurements):
 * <ul>
 *   <li>During the first {@link #WINDOW_BUFFERS} buffer durations after each {@code onStart} and
 *       {@code onResume}, the estimate is the device position capped by the elapsed playing time (the
 *       device position leads true playback by up to one buffer there). Afterwards the device position
 *       is trusted, so a device clock that runs faster than the system clock cannot cause long-run lag.
 *       The window of two buffers was chosen from the traces: the lead is repaid within about one
 *       buffer (190 ms of 200 ms).</li>
 *   <li>The estimate never exceeds the frames written.</li>
 *   <li>Between device steps (about 20 ms) the position is extrapolated at the nominal rate, by at most
 *       the size of the last step (clamped to 10 to 100 ms).</li>
 *   <li>It never decreases within a run, and it is frozen while paused.</li>
 * </ul>
 *
 * <p><b>Threading:</b> not thread-safe; callers synchronise.
 */
public final class PlaybackClock {

    /** Length of the capped window after start and resume, in device buffer durations. */
    public static final double WINDOW_BUFFERS = 2.0;

    private enum Mode { IDLE, PLAYING, PAUSED }

    private final int sampleRate;
    private final long windowNanos;
    private final long minStepFrames;
    private final long maxStepFrames;

    private Mode mode = Mode.IDLE;
    private long segmentStart;
    private long accumulatedNanos;
    private long last;
    private long lastDevicePos;
    private long lastChangeNanos;
    private long lastStepFrames;

    /**
     * Creates a clock.
     *
     * @param sampleRate   frames per second
     * @param bufferFrames device buffer in frames
     */
    public PlaybackClock(int sampleRate, int bufferFrames) {
        if (sampleRate <= 0 || bufferFrames <= 0) {
            throw new IllegalArgumentException("sampleRate and bufferFrames must be positive");
        }
        this.sampleRate = sampleRate;
        this.windowNanos = (long) (WINDOW_BUFFERS * bufferFrames * 1e9 / sampleRate);
        this.minStepFrames = Math.max(1, sampleRate / 100);
        this.maxStepFrames = Math.max(minStepFrames, sampleRate / 10);
    }

    /** @return the capped window length in nanoseconds */
    public long windowNanos() {
        return windowNanos;
    }

    /**
     * The device was started for a fresh run.
     *
     * @param nanos current time
     */
    public void onStart(long nanos) {
        mode = Mode.PLAYING;
        segmentStart = nanos;
        accumulatedNanos = 0;
        last = 0;
        lastDevicePos = -1;
        lastChangeNanos = nanos;
        lastStepFrames = sampleRate / 50;
    }

    /**
     * Playback was paused; the estimate freezes at its last value.
     *
     * @param nanos current time
     * @throws IllegalStateException if not playing
     */
    public void onPause(long nanos) {
        if (mode != Mode.PLAYING) {
            throw new IllegalStateException("not playing: " + mode);
        }
        accumulatedNanos += nanos - segmentStart;
        mode = Mode.PAUSED;
    }

    /**
     * Playback resumed.
     *
     * @param nanos current time
     * @throws IllegalStateException if not paused
     */
    public void onResume(long nanos) {
        if (mode != Mode.PAUSED) {
            throw new IllegalStateException("not paused: " + mode);
        }
        mode = Mode.PLAYING;
        segmentStart = nanos;
        lastChangeNanos = nanos;
    }

    /** The run ended; the clock returns to idle and estimates 0 until the next start. */
    public void onStop() {
        mode = Mode.IDLE;
        last = 0;
    }

    /**
     * Estimates the frames played since the device was opened.
     *
     * @param nanos               current time
     * @param devicePositionFrames raw device position
     * @param framesWritten       frames handed to the device so far
     * @return played frames, never decreasing within a run
     */
    public long estimate(long nanos, long devicePositionFrames, long framesWritten) {
        if (mode == Mode.IDLE) {
            return 0;
        }
        if (mode == Mode.PAUSED) {
            return last;
        }
        if (devicePositionFrames != lastDevicePos) {
            if (lastDevicePos >= 0 && devicePositionFrames > lastDevicePos) {
                lastStepFrames = Math.min(maxStepFrames, Math.max(minStepFrames,
                        devicePositionFrames - lastDevicePos));
            }
            lastDevicePos = devicePositionFrames;
            lastChangeNanos = nanos;
        }
        long sinceChange = Math.max(0, nanos - lastChangeNanos);
        long extrapolated = Math.min((long) (sinceChange * 1e-9 * sampleRate), lastStepFrames);
        long v = Math.min(devicePositionFrames + extrapolated, framesWritten);
        if (nanos - segmentStart < windowNanos) {
            long elapsedFrames = (long) ((accumulatedNanos + (nanos - segmentStart)) * 1e-9 * sampleRate);
            v = Math.min(v, elapsedFrames);
        }
        if (v < last) {
            v = last;
        }
        last = v;
        return v;
    }
}
