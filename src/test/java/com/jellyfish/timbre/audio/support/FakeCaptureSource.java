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
package com.jellyfish.timbre.audio.support;

import com.jellyfish.timbre.audio.AudioDeviceException;
import com.jellyfish.timbre.audio.CaptureSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/**
 * A virtual capture device that reproduces the Milestone 5 Phase A0 findings.
 *
 * <ul>
 *   <li>A ring of {@code bufferFrames} frames (the requested size, or {@link #grantBufferFrames}). The test feeds
 *       it with {@link #feed} or {@link #feedFrames}; when it is FULL, further bytes are DROPPED and counted
 *       (finding 7), not queued. Bytes fed before {@code start()} or after {@code stop()}/{@code close()} are
 *       dropped too.</li>
 *   <li>{@link #read} blocks until at least one frame is available and then returns up to {@code length} bytes
 *       (whole frames). {@link #stop()} and {@link #close()} wake a blocked read, which returns 0. After
 *       {@code stop()} read returns 0 even though {@link #availableBytes()} still reports the pending bytes:
 *       the tail is unreadable (finding 8).</li>
 *   <li>Single-use: {@code start()} after {@code stop()}, and any open, start, read or available after
 *       {@code close()}, throw {@link IllegalStateException}, so a recorder that violates D1' fails loudly.
 *       {@code stop()} and {@code close()} are idempotent.</li>
 *   <li>Switches: an open failure, a read error after N delivered bytes, a read that ignores {@code stop()} (only
 *       {@code close()} wakes it), a read that ignores both (only {@link #forceWake()} ends it).</li>
 *   <li>A gate ({@link #holdReads()}): the next read takes its bytes and then waits before returning until
 *       {@link #releaseReads()}, so the test can fill the buffer while the capture thread is stalled.</li>
 *   <li>The call order (open, start, read, available, stop, close) is recorded.</li>
 *   <li>A watchdog: a read never waits longer than {@link #WATCHDOG_MILLIS}; it then throws.</li>
 * </ul>
 *
 * <p>Test signal: the 16-bit value of frame f, channel c is {@link #pattern(long, int)}.
 *
 * <p>Thread-safe (one monitor).
 */
public final class FakeCaptureSource implements CaptureSource {

    /** A recorded call. */
    public enum Call { OPEN, START, READ, AVAILABLE, STOP, CLOSE }

    /** Longest wait inside a read before it throws. */
    public static final long WATCHDOG_MILLIS = 20_000;

    private final Object lock = new Object();
    private final List<Call> calls = new ArrayList<>();
    private final List<String> callThreads = new ArrayList<>();

    private byte[] ring = new byte[0];
    private int head;
    private int size;
    private int frameBytes = 4;
    private int bufferFrames;
    private int channels;
    private int sampleRate;
    private boolean opened;
    private boolean started;
    private boolean stopped;
    private boolean closed;
    private long generatedFrames;
    private long droppedFullBytes;
    private long droppedIdleBytes;
    private long deliveredBytes;

    private String openFailure;
    private int grantBufferFrames = -1;
    private long failAfterBytes = -1;
    private String failMessage;
    private boolean stopWakesRead = true;
    private boolean closeWakesRead = true;
    private boolean forcedWake;
    private boolean gateArmed;
    private boolean readerHeld;

    /** Creates an unopened fake. */
    public FakeCaptureSource() {
        // nothing to initialise
    }

    /**
     * The 16-bit test value of frame f, channel c: {@code ((f * 7 + c * 13) mod 65536) - 32768}.
     *
     * @param f frame index
     * @param c channel
     * @return the value, from -32768 to 32767
     */
    public static int pattern(long f, int c) {
        return (int) Math.floorMod(f * 7 + c * 13, 65536L) - 32768;
    }

    // ------------------------------------------------------------------ switches

    /**
     * Makes {@link #open} fail.
     *
     * @param message the exception message
     * @return this
     */
    public FakeCaptureSource failOpen(String message) {
        synchronized (lock) {
            this.openFailure = message;
        }
        return this;
    }

    /**
     * Grants another buffer size than requested.
     *
     * @param frames the granted size
     * @return this
     */
    public FakeCaptureSource grantBufferFrames(int frames) {
        synchronized (lock) {
            this.grantBufferFrames = frames;
        }
        return this;
    }

    /**
     * Delivers at most {@code bytes} bytes in total; the read after that throws.
     *
     * @param bytes   bytes to deliver first (whole frames)
     * @param message the exception message
     * @return this
     */
    public FakeCaptureSource failAfterBytes(long bytes, String message) {
        synchronized (lock) {
            this.failAfterBytes = bytes;
            this.failMessage = message;
        }
        return this;
    }

    /**
     * Whether {@code stop()} and {@code close()} wake a blocked read (default both true).
     *
     * @param onStop  stop wakes it
     * @param onClose close wakes it
     * @return this
     */
    public FakeCaptureSource wakes(boolean onStop, boolean onClose) {
        synchronized (lock) {
            this.stopWakesRead = onStop;
            this.closeWakesRead = onClose;
        }
        return this;
    }

    /** Ends any blocked read with 0, whatever the wake switches say. */
    public void forceWake() {
        synchronized (lock) {
            forcedWake = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ feeding

    /**
     * Feeds raw bytes (whole frames). Bytes that do not fit the buffer are dropped.
     *
     * @param pcm interleaved 16-bit little-endian bytes
     */
    public void feed(byte[] pcm) {
        synchronized (lock) {
            if (pcm.length % frameBytes != 0) {
                throw new IllegalArgumentException("not whole frames");
            }
            generatedFrames += pcm.length / frameBytes;
            store(pcm, pcm.length);
        }
    }

    /**
     * Feeds {@code frames} frames from a generator called with (frame index, channel); the frame index continues
     * across calls (dropped frames advance it too).
     *
     * @param frames    frames to feed
     * @param generator gives the 16-bit value
     */
    public void feedFrames(int frames, IntBinaryOperator generator) {
        synchronized (lock) {
            byte[] b = new byte[frames * frameBytes];
            int p = 0;
            for (int i = 0; i < frames; i++) {
                long f = generatedFrames + i;
                for (int c = 0; c < channels; c++) {
                    int v = generator.applyAsInt((int) f, c);
                    b[p++] = (byte) v;
                    b[p++] = (byte) (v >> 8);
                }
            }
            generatedFrames += frames;
            store(b, b.length);
        }
    }

    /**
     * Feeds {@code frames} frames of {@link #pattern}.
     *
     * @param frames frames to feed
     */
    public void feedPattern(int frames) {
        feedFrames(frames, (f, c) -> pattern(f, c));
    }

    private void store(byte[] b, int len) {
        if (!started || stopped || closed) {
            droppedIdleBytes += len;
            return;
        }
        int free = ring.length - size;
        int n = Math.min(free, len);
        for (int i = 0; i < n; i++) {
            ring[(head + size + i) % ring.length] = b[i];
        }
        size += n;
        droppedFullBytes += len - n;
        lock.notifyAll();
    }

    // ------------------------------------------------------------------ gate

    /** Arms the gate: the next read takes its bytes and then waits before returning. */
    public void holdReads() {
        synchronized (lock) {
            gateArmed = true;
        }
    }

    /**
     * Waits until a reader is held at the gate.
     *
     * @param timeoutMillis the longest wait
     * @return true if a reader is held
     * @throws InterruptedException if interrupted
     */
    public boolean awaitHeld(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!readerHeld) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    return false;
                }
                lock.wait(left);
            }
            return true;
        }
    }

    /** Opens the gate and lets a held reader return. */
    public void releaseReads() {
        synchronized (lock) {
            gateArmed = false;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------ CaptureSource

    private void record(Call c) {
        calls.add(c);
        callThreads.add(Thread.currentThread().getName());
    }

    @Override
    public void open(int rate, int channelCount, int requestedBufferFrames) throws AudioDeviceException {
        synchronized (lock) {
            record(Call.OPEN);
            if (opened || closed) {
                throw new IllegalStateException("fake: open twice or after close");
            }
            if (openFailure != null) {
                throw new AudioDeviceException(openFailure);
            }
            this.sampleRate = rate;
            this.channels = channelCount;
            this.frameBytes = 2 * channelCount;
            this.bufferFrames = grantBufferFrames > 0 ? grantBufferFrames : requestedBufferFrames;
            this.ring = new byte[bufferFrames * frameBytes];
            this.opened = true;
        }
    }

    @Override
    public void start() {
        synchronized (lock) {
            record(Call.START);
            if (!opened || started || stopped || closed) {
                throw new IllegalStateException("fake: start is allowed once, after open, never after stop/close");
            }
            started = true;
        }
    }

    @Override
    public int read(byte[] dst, int offset, int length) {
        synchronized (lock) {
            record(Call.READ);
            if (closed) {
                throw new IllegalStateException("fake: read after close");
            }
            if (!started) {
                throw new IllegalStateException("fake: read before start");
            }
            if (length % frameBytes != 0) {
                throw new IllegalArgumentException("fake: length is not whole frames: " + length);
            }
            long deadline = System.nanoTime() + WATCHDOG_MILLIS * 1_000_000L;
            while (true) {
                if (forcedWake || (closed && closeWakesRead) || (stopped && stopWakesRead)) {
                    return 0;
                }
                if (size > 0 && !stopped && !closed) {
                    break;
                }
                waitUntil(deadline);
            }
            if (failAfterBytes >= 0 && deliveredBytes >= failAfterBytes) {
                throw new IllegalStateException(failMessage);
            }
            int n = Math.min(length, size);
            if (failAfterBytes >= 0) {
                n = (int) Math.min(n, failAfterBytes - deliveredBytes);
            }
            n -= n % frameBytes;
            for (int i = 0; i < n; i++) {
                dst[offset + i] = ring[(head + i) % ring.length];
            }
            head = (head + n) % ring.length;
            size -= n;
            deliveredBytes += n;
            if (gateArmed) {
                readerHeld = true;
                lock.notifyAll();
                while (gateArmed && !forcedWake) {
                    waitUntil(deadline);
                }
                readerHeld = false;
            }
            return n;
        }
    }

    private void waitUntil(long deadline) {
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) {
            throw new IllegalStateException("fake: read watchdog expired");
        }
        try {
            lock.wait(left);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fake: interrupted", e);
        }
    }

    @Override
    public int availableBytes() {
        synchronized (lock) {
            record(Call.AVAILABLE);
            if (closed) {
                throw new IllegalStateException("fake: available after close");
            }
            return size;
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            record(Call.STOP);
            stopped = true;
            lock.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            record(Call.CLOSE);
            closed = true;
            lock.notifyAll();
        }
    }

    @Override
    public int bufferFrames() {
        synchronized (lock) {
            return bufferFrames;
        }
    }

    @Override
    public int channels() {
        synchronized (lock) {
            return channels;
        }
    }

    @Override
    public int sampleRate() {
        synchronized (lock) {
            return sampleRate;
        }
    }

    // ------------------------------------------------------------------ inspection

    /** @return a copy of the recorded calls */
    public List<Call> calls() {
        synchronized (lock) {
            return List.copyOf(calls);
        }
    }

    /** @return the thread name of each recorded call, in order */
    public List<String> callThreads() {
        synchronized (lock) {
            return List.copyOf(callThreads);
        }
    }

    /** @return true once {@code close()} was called */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** @return bytes dropped because the buffer was full */
    public long droppedFullBytes() {
        synchronized (lock) {
            return droppedFullBytes;
        }
    }

    /** @return bytes dropped because the fake was not running (before start, after stop or close) */
    public long droppedIdleBytes() {
        synchronized (lock) {
            return droppedIdleBytes;
        }
    }

    /** @return bytes waiting in the buffer */
    public int pendingBytes() {
        synchronized (lock) {
            return size;
        }
    }

    /** @return bytes delivered by reads */
    public long deliveredBytes() {
        synchronized (lock) {
            return deliveredBytes;
        }
    }
}
