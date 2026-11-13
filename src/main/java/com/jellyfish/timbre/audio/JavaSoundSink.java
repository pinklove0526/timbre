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
import javax.sound.sampled.SourceDataLine;

/**
 * {@link AudioSink} over a {@link SourceDataLine} on the system default output route, 16-bit signed
 * little-endian stereo. Never calls {@code flush()} (on the measured stack it makes the position jump).
 *
 * <p><b>Threading:</b> see {@link AudioSink}. All line methods used here are safe to call from
 * different threads.
 */
public final class JavaSoundSink implements AudioSink {

    private static final int FRAME_BYTES = 4;

    private volatile SourceDataLine line;
    private volatile int bufferFrames;

    /** Creates a closed sink. */
    public JavaSoundSink() {
        // nothing to initialise
    }

    @Override
    public void open(int sampleRate, int bufferFrames) throws AudioDeviceException {
        if (sampleRate <= 0 || bufferFrames <= 0) {
            throw new IllegalArgumentException("sampleRate and bufferFrames must be positive");
        }
        if (line != null) {
            throw new IllegalStateException("already open");
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 2, true, false);
        String what = "16-bit signed little-endian stereo at " + sampleRate + " Hz";
        try {
            if (!AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, fmt))) {
                throw new AudioDeviceException("The default audio output does not support " + what);
            }
            SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
            l.open(fmt, bufferFrames * FRAME_BYTES);
            this.bufferFrames = l.getBufferSize() / FRAME_BYTES;
            this.line = l;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new AudioDeviceException("Cannot open the default audio output for " + what + ": " + e.getMessage(),
                    e);
        }
    }

    private SourceDataLine requireLine() {
        SourceDataLine l = line;
        if (l == null) {
            throw new IllegalStateException("not open");
        }
        return l;
    }

    @Override
    public void start() {
        requireLine().start();
    }

    @Override
    public void stop() {
        requireLine().stop();
    }

    @Override
    public int write(byte[] pcm, int offset, int length) {
        Objects.requireNonNull(pcm, "pcm");
        return requireLine().write(pcm, offset, length);
    }

    @Override
    public void drain() {
        requireLine().drain();
    }

    @Override
    public long positionFrames() {
        return requireLine().getLongFramePosition();
    }

    @Override
    public int bufferFrames() {
        return bufferFrames;
    }

    @Override
    public void close() {
        SourceDataLine l = line;
        if (l != null) {
            l.close();
        }
    }
}
