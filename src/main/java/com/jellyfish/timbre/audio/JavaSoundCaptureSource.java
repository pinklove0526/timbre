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

import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * {@link CaptureSource} over a {@link TargetDataLine} on the system DEFAULT input, 16-bit signed little-endian,
 * opened with an explicitly requested buffer ({@code line.open(format, bufferFrames * frameBytes)}, amended
 * decision 1'). Never calls {@code flush()}.
 *
 * <p>Measured on the reference machine (Milestone 5 Phase A0): after {@code stop()} the bytes reported by
 * {@code available()} cannot be read ({@code read} returns 0), and {@code start()} after {@code stop()} delivers
 * stale buffered audio, so this source is single-use (see {@link CaptureSource}). {@code stop()} and
 * {@code close()} from another thread wake a blocked {@code read} within about 1 ms.
 *
 * <p><b>Threading:</b> see {@link CaptureSource}. The line methods used here are safe to call from different
 * threads.
 */
public final class JavaSoundCaptureSource implements CaptureSource {

    private enum Phase { NEW, OPEN, STARTED, STOPPED, CLOSED }

    private volatile Phase phase = Phase.NEW;
    private volatile TargetDataLine line;
    private volatile int bufferFrames;
    private volatile int channels;
    private volatile int sampleRate;

    /** Creates an unopened source. */
    public JavaSoundCaptureSource() {
        // nothing to initialise
    }

    @Override
    public void open(int rate, int channelCount, int requestedBufferFrames) throws AudioDeviceException {
        if (rate <= 0 || requestedBufferFrames <= 0) {
            throw new IllegalArgumentException("sampleRate and bufferFrames must be positive");
        }
        if (channelCount != 1 && channelCount != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2: " + channelCount);
        }
        synchronized (this) {
            if (phase != Phase.NEW) {
                throw new IllegalStateException("a capture source can be opened only once (it is " + phase + ")");
            }
            int frameBytes = 2 * channelCount;
            AudioFormat fmt = new AudioFormat(rate, 16, channelCount, true, false);
            String what = "16-bit " + (channelCount == 1 ? "mono" : "stereo") + " at " + rate + " Hz";
            try {
                if (!AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, fmt))) {
                    throw new AudioDeviceException("No usable input device for " + what);
                }
                TargetDataLine l = AudioSystem.getTargetDataLine(fmt);
                l.open(fmt, requestedBufferFrames * frameBytes);
                this.bufferFrames = l.getBufferSize() / frameBytes;
                this.channels = channelCount;
                this.sampleRate = rate;
                this.line = l;
                this.phase = Phase.OPEN;
            } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                throw new AudioDeviceException("Cannot open the default audio input for " + what + ": "
                        + e.getMessage(), e);
            }
        }
    }

    @Override
    public synchronized void start() {
        if (phase != Phase.OPEN) {
            throw new IllegalStateException("a capture source can be started only once, after open (it is "
                    + phase + ")");
        }
        line.start();
        phase = Phase.STARTED;
    }

    @Override
    public int read(byte[] dst, int offset, int length) {
        Objects.requireNonNull(dst, "dst");
        TargetDataLine l = line;
        if (l == null) {
            throw new IllegalStateException("not open");
        }
        if (phase != Phase.STARTED) {
            return 0;
        }
        try {
            return l.read(dst, offset, length);
        } catch (RuntimeException e) {
            if (phase != Phase.STARTED) {
                return 0; // stopped or closed by another thread during the call
            }
            throw e;
        }
    }

    @Override
    public int availableBytes() {
        TargetDataLine l = line;
        if (l == null || phase == Phase.CLOSED) {
            return 0;
        }
        return l.available();
    }

    @Override
    public void stop() {
        TargetDataLine l = line;
        if (l == null || phase == Phase.CLOSED) {
            return;
        }
        phase = Phase.STOPPED;
        try {
            l.stop();
        } catch (RuntimeException ignored) {
            // stop must not throw
        }
    }

    @Override
    public void close() {
        phase = Phase.CLOSED;
        TargetDataLine l = line;
        if (l != null) {
            try {
                l.close();
            } catch (RuntimeException ignored) {
                // close must not throw
            }
        }
    }

    @Override
    public int bufferFrames() {
        return bufferFrames;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }
}
