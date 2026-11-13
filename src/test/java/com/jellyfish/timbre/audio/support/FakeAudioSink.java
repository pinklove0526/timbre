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
import com.jellyfish.timbre.audio.AudioSink;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A deterministic stand-in for the audio device.
 *
 * <p>Time is virtual: in {@link Pace#MANUAL} the test calls {@link #advance(long)}; in {@link Pace#AUTO} a
 * daemon thread advances it from the system clock times a speed factor. The device consumes queued audio
 * only while started. {@code write} blocks while the queue is full, and returns early (short or zero) once
 * the sink is stopped, closed, or a scripted action says so; {@code stop()} and {@code close()} always
 * unblock a blocked write or drain.
 *
 * <p>{@link Artifacts#PULSE_LIKE} reproduces the Phase A position artifacts: on every start and stop the
 * reported position is lifted to {@code true position + one buffer}, and stays flat until true playback
 * catches up (see {@code traces/README.md}); the position is reported in 20 ms steps (rounded up, as the traces lead wall time by 0 to 20 ms) and the queue holds
 * two buffers. The model is validated against the recorded traces in {@code FakeAudioSinkValidationTest}.
 *
 * <p>Thread-safe.
 */
public final class FakeAudioSink implements AudioSink {

    /** How the test advances device time. */
    public enum Pace { MANUAL, AUTO }

    /** Position behaviour. */
    public enum Artifacts { NONE, PULSE_LIKE }

    /** One scripted behaviour for the next write call. */
    public sealed interface Action permits Zero, Short, Throw {
    }

    /** Return 0 without accepting anything. */
    public record Zero() implements Action {
    }

    /**
     * Accept at most this many bytes, then return.
     *
     * @param bytes limit (rounded down to whole frames)
     */
    public record Short(int bytes) implements Action {
    }

    /** Throw a RuntimeException. */
    public record Throw() implements Action {
    }

    private final Object lock = new Object();
    private final Pace pace;
    private final Artifacts artifacts;
    private final double autoSpeed;
    private final Deque<Action> script = new ArrayDeque<>();
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();

    private int rate;
    private int bufferFrames;
    private int capacityFrames;
    private boolean opened;
    private boolean started;
    private boolean closed;
    private long deviceNanos;
    private double carry;
    private long writtenFrames;
    private long consumedFrames;
    private long leadFloor;
    private boolean leadPending;
    private boolean writerBlocked;
    private boolean drainBlocked;
    private long acceptedWhileStopped;
    private int writeCalls;
    private int closeCount;
    private AudioDeviceException openFailure;
    private Thread auto;
    private boolean discard;

    /**
     * Creates a sink.
     *
     * @param pace      manual or automatic time
     * @param artifacts position model
     */
    public FakeAudioSink(Pace pace, Artifacts artifacts) {
        this(pace, artifacts, 1.0);
    }

    /**
     * Creates a sink.
     *
     * @param pace      manual or automatic time
     * @param artifacts position model
     * @param autoSpeed device seconds per real second in AUTO mode
     */
    public FakeAudioSink(Pace pace, Artifacts artifacts, double autoSpeed) {
        this.pace = pace;
        this.artifacts = artifacts;
        this.autoSpeed = autoSpeed;
    }

    /** Makes the next {@code open} fail. @param failure the exception to throw */
    public void failOpenWith(AudioDeviceException failure) {
        synchronized (lock) {
            openFailure = failure;
        }
    }

    /** Queues scripted behaviour for the next write calls, one action per call. @param actions the actions */
    public void script(Action... actions) {
        synchronized (lock) {
            for (Action a : actions) {
                script.add(a);
            }
        }
    }

    @Override
    public void open(int sampleRate, int bufferFrames) throws AudioDeviceException {
        synchronized (lock) {
            if (openFailure != null) {
                throw openFailure;
            }
            this.rate = sampleRate;
            this.bufferFrames = bufferFrames;
            this.capacityFrames = artifacts == Artifacts.PULSE_LIKE ? 2 * bufferFrames : bufferFrames;
            this.opened = true;
        }
        if (pace == Pace.AUTO) {
            auto = new Thread(this::autoLoop, "fake-sink-clock");
            auto.setDaemon(true);
            auto.start();
        }
    }

    private void autoLoop() {
        long last = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            advance((long) ((now - last) * autoSpeed));
            last = now;
        }
    }

    /**
     * Advances device time and consumes queued audio if started.
     *
     * @param nanos device nanoseconds to add
     */
    public void advance(long nanos) {
        synchronized (lock) {
            deviceNanos += nanos;
            if (started && !closed) {
                double frames = nanos * 1e-9 * rate + carry;
                long whole = (long) frames;
                carry = frames - whole;
                consumedFrames += Math.min(whole, writtenFrames - consumedFrames);
            }
            lock.notifyAll();
        }
    }

    /** @return the virtual device time in nanoseconds, usable as the engine's clock */
    public long nanoTime() {
        synchronized (lock) {
            return deviceNanos;
        }
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (!started) {
                started = true;
                leadPending = true; // like the real line, the lift happens with the first data after start
            }
            lock.notifyAll();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (started) {
                started = false;
                leadPending = false;
                leadFloor = consumedFrames + bufferFrames;
            }
            lock.notifyAll();
        }
    }

    @Override
    public int write(byte[] pcm, int offset, int length) {
        synchronized (lock) {
            if (!opened || closed) {
                return 0;
            }
            writeCalls++;
            int limit = length;
            Action a = script.poll();
            if (a instanceof Throw) {
                throw new IllegalStateException("scripted write failure");
            }
            if (a instanceof Zero) {
                return 0;
            }
            if (a instanceof Short s) {
                limit = Math.min(length, s.bytes() / 4 * 4);
            }
            int accepted = 0;
            while (!closed) {
                long free = capacityFrames - (writtenFrames - consumedFrames);
                int take = (int) Math.min(free * 4, limit - accepted) / 4 * 4;
                if (take > 0) {
                    if (started && leadPending) {
                        leadPending = false;
                        leadFloor = consumedFrames + bufferFrames;
                    }
                    if (!discard) {
                        received.write(pcm, offset + accepted, take);
                    }
                    writtenFrames += take / 4;
                    accepted += take;
                    if (!started) {
                        acceptedWhileStopped += take;
                    }
                }
                if (accepted >= limit || !started) {
                    break;
                }
                writerBlocked = true;
                lock.notifyAll();
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    writerBlocked = false;
                }
            }
            return accepted;
        }
    }

    @Override
    public void drain() {
        synchronized (lock) {
            while (started && !closed && writtenFrames > consumedFrames) {
                drainBlocked = true;
                lock.notifyAll();
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } finally {
                    drainBlocked = false;
                }
            }
        }
    }

    @Override
    public long positionFrames() {
        synchronized (lock) {
            if (artifacts == Artifacts.NONE) {
                return consumedFrames;
            }
            long lifted = Math.min(leadFloor, writtenFrames + bufferFrames);
            long pos = Math.max(consumedFrames, lifted);
            long step = Math.max(1, rate / 50);
            return (pos + step - 1) / step * step; // the real position is a staircase that reads at the end of a step
        }
    }

    @Override
    public int bufferFrames() {
        synchronized (lock) {
            return bufferFrames;
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                started = false;
                closeCount++;
            }
            lock.notifyAll();
        }
        Thread t = auto;
        if (t != null) {
            t.interrupt();
        }
    }

    // ------------------------------------------------------------------ inspection

    /** Stops recording accepted bytes (so the audio thread allocates nothing in the sink). @param on true to discard */
    public void discardReceived(boolean on) {
        synchronized (lock) {
            discard = on;
        }
    }

    /** @return every byte accepted so far */
    public byte[] received() {
        synchronized (lock) {
            return received.toByteArray();
        }
    }

    /** @return the number of bytes accepted so far */
    public int receivedBytes() {
        synchronized (lock) {
            return received.size();
        }
    }

    /** @return bytes accepted while the sink was stopped */
    public long acceptedWhileStopped() {
        synchronized (lock) {
            return acceptedWhileStopped;
        }
    }

    /** @return whether the sink is started (not stopped or closed) */
    public boolean isStarted() {
        synchronized (lock) {
            return started && !closed;
        }
    }

    /** @return whether close() has been called */
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** @return how many times close() actually closed the device (0 or 1) */
    public int closeCount() {
        synchronized (lock) {
            return closeCount;
        }
    }

    /** @return number of write calls that reached the device */
    public int writeCalls() {
        synchronized (lock) {
            return writeCalls;
        }
    }

    /** @return frames queued and not yet played */
    public long queuedFrames() {
        synchronized (lock) {
            return writtenFrames - consumedFrames;
        }
    }

    /** @return frames played in device time */
    public long consumedFrames() {
        synchronized (lock) {
            return consumedFrames;
        }
    }

    /**
     * Waits until a writer is blocked on a full queue.
     *
     * @param timeoutMillis maximum wait
     * @return true if a writer is blocked
     */
    public boolean awaitWriterBlocked(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!writerBlocked) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Waits until a drain is blocked.
     *
     * @param timeoutMillis maximum wait
     * @return true if drain is blocked
     */
    public boolean awaitDrainBlocked(long timeoutMillis) {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (lock) {
            while (!drainBlocked) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    lock.wait(Math.max(1, left / 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
