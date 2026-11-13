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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Plays a {@link MixPlan} through an {@link AudioSink}: a transport (play, pause, resume, stop) over one
 * dedicated audio thread.
 *
 * <p>Design (Milestone 2 decisions 3' and 5'): the audio thread owns preallocated buffers, renders
 * 1024-frame blocks and writes them with blocking writes; the unwritten remainder of a short write is
 * kept and retried, never dropped; the thread parks while paused; {@code flush()} is never used; the
 * first block is rendered before the device is started; at the natural end the final partial block is
 * written and the device drained. Pause is {@link AudioSink#stop()}/{@link AudioSink#start()} on the open
 * device; stop closes the device and the next play reopens it.
 *
 * <p><b>Threading:</b> control methods ({@code play}, {@code pause}, {@code resume}, {@code stop},
 * {@code setPlan}, {@code close}) may be called from any thread and serialise on a private lock.
 * {@link #state()}, {@link #positionFrames()}, {@link #startFrame()} and {@link #stats()} are non-blocking
 * and may be called from any thread (for example every animation frame). The audio thread, a daemon named
 * {@code timbre-audio}, never takes the control lock; it uses volatile fields and park/unpark. Listener
 * threads are described in {@link PlaybackListener}.
 */
public final class PlaybackEngine {

    /** Transport state. */
    public enum State {
        /** Not playing; the cursor is at the start frame of the last run. */
        STOPPED,
        /** Audio is being produced. */
        PLAYING,
        /** Paused with the device open. */
        PAUSED
    }

    /** Frames rendered per block. */
    static final int BLOCK_FRAMES = 1024;
    private static final double BUFFER_SECONDS = 0.2;
    private static final long JOIN_MILLIS = 2000;
    private static final int FRAME_BYTES = 4;

    private final Supplier<AudioSink> sinkFactory;
    private final LongSupplier nanoClock;
    private final AtomicReference<MixPlan> plan = new AtomicReference<>(MixPlan.empty());
    private final Object lock = new Object();
    private final Object stateLock = new Object();
    private final List<PlaybackListener> listeners = new CopyOnWriteArrayList<>();

    private volatile State state = State.STOPPED;
    private volatile Run current;
    private volatile long lastStartFrame;
    private boolean closed;

    private final AtomicLong blocksRendered = new AtomicLong();
    private final AtomicLong shortWrites = new AtomicLong();
    private final AtomicLong suspectedUnderruns = new AtomicLong();
    private volatile long maxRenderNanos;

    /** Creates an engine that plays through the default Java Sound output. */
    public PlaybackEngine() {
        this(JavaSoundSink::new);
    }

    /**
     * Creates an engine with a custom device factory (a new sink is requested for every play).
     *
     * @param sinkFactory creates a closed sink
     */
    public PlaybackEngine(Supplier<AudioSink> sinkFactory) {
        this(sinkFactory, System::nanoTime);
    }

    PlaybackEngine(Supplier<AudioSink> sinkFactory, LongSupplier nanoClock) {
        this.sinkFactory = Objects.requireNonNull(sinkFactory, "sinkFactory");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    // ------------------------------------------------------------------ control

    /**
     * Replaces what is played. While playing or paused the audio thread picks the new plan up at the next
     * block boundary, so a block is never mixed from two plans.
     *
     * @param newPlan the plan
     * @throws IllegalArgumentException if a run is active, the new plan is not empty and its sample rate
     *                                  differs from the running one
     */
    public void setPlan(MixPlan newPlan) {
        Objects.requireNonNull(newPlan, "plan");
        synchronized (lock) {
            Run r = current;
            if (r != null && !newPlan.isEmpty() && newPlan.sampleRate() != r.rate) {
                throw new IllegalArgumentException("sample rate " + newPlan.sampleRate()
                        + " Hz differs from the playing " + r.rate + " Hz");
            }
            plan.set(newPlan);
        }
    }

    /**
     * Starts playing from a frame. If already playing or paused, the current run is stopped first. The
     * device is opened on the caller's thread.
     *
     * @param fromFrame project frame to start at, at least 0
     * @return false (and STOPPED, no device opened) if the plan is empty or {@code fromFrame} is at or after
     *         its end
     * @throws AudioDeviceException if the device cannot be opened or started
     * @throws IllegalStateException if the engine is closed
     */
    public boolean play(long fromFrame) throws AudioDeviceException {
        if (fromFrame < 0) {
            throw new IllegalArgumentException("fromFrame must not be negative: " + fromFrame);
        }
        synchronized (lock) {
            requireOpen();
            stopLocked();
            MixPlan p = plan.get();
            if (p.isEmpty() || fromFrame >= p.length()) {
                return false;
            }
            int rate = p.sampleRate();
            AudioSink sink = Objects.requireNonNull(sinkFactory.get(), "sink");
            try {
                sink.open(rate, (int) Math.round(rate * BUFFER_SECONDS));
            } catch (AudioDeviceException e) {
                sink.close();
                throw e;
            }
            Run r;
            try {
                r = new Run(sink, p, fromFrame);
                r.renderFirstBlock();
                sink.start();
            } catch (RuntimeException e) {
                sink.close();
                throw new AudioDeviceException("Cannot start playback at " + rate + " Hz: " + e.getMessage(), e);
            }
            blocksRendered.set(0);
            shortWrites.set(0);
            suspectedUnderruns.set(0);
            blocksRendered.incrementAndGet();
            synchronized (r.clock) {
                r.clock.onStart(nanoClock.getAsLong());
            }
            synchronized (stateLock) {
                current = r;
                state = State.PLAYING;
                lastStartFrame = fromFrame;
            }
            fireState(State.STOPPED, State.PLAYING);
            r.thread.start();
            return true;
        }
    }

    /** Pauses playback (no-op unless PLAYING). The device stays open and queued audio is kept. */
    public void pause() {
        synchronized (lock) {
            Run r;
            synchronized (stateLock) {
                r = current;
                if (state != State.PLAYING || r == null || r.finished) {
                    return;
                }
                state = State.PAUSED;
            }
            long now = nanoClock.getAsLong();
            synchronized (r.clock) {
                // take the last good estimate before stop() makes the device position jump, then freeze
                r.clock.estimate(now, safePosition(r), r.framesWritten);
                r.clock.onPause(now);
            }
            r.paused = true;
            r.sink.stop();
            r.awaitNotInWrite();
            fireState(State.PLAYING, State.PAUSED);
        }
    }

    /** Resumes after {@link #pause()} (no-op unless PAUSED). */
    public void resume() {
        synchronized (lock) {
            Run r;
            synchronized (stateLock) {
                r = current;
                if (state != State.PAUSED || r == null || r.finished) {
                    return;
                }
            }
            synchronized (r.clock) {
                r.clock.onResume(nanoClock.getAsLong());
            }
            r.sink.start();
            synchronized (stateLock) {
                if (r.finished) {
                    return;
                }
                state = State.PLAYING;
            }
            r.paused = false;
            LockSupport.unpark(r.thread);
            fireState(State.PAUSED, State.PLAYING);
        }
    }

    /**
     * Stops playback (no-op when STOPPED): the device is closed, queued audio is discarded and the cursor
     * returns to the start frame of the run. Returns within about 2 s.
     */
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    /** Stops, joins the audio thread and refuses further plays. Idempotent. */
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            stopLocked();
            closed = true;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
    }

    private void stopLocked() {
        Run r;
        State old;
        synchronized (stateLock) {
            r = current;
            if (r == null || r.finished) {
                return;
            }
            r.finished = true;
            r.stopRequested = true;
            old = state;
            state = State.STOPPED;
            current = null;
        }
        LockSupport.unpark(r.thread);
        r.closeSink();
        r.join(JOIN_MILLIS);
        synchronized (r.clock) {
            r.clock.onStop();
        }
        fireState(old, State.STOPPED);
    }

    // ------------------------------------------------------------------ queries

    /** @return the current transport state */
    public State state() {
        return state;
    }

    /**
     * The cursor in project frames: while PLAYING the clock estimate plus the start frame, while PAUSED the
     * frozen value, while STOPPED the start frame of the last run. Never decreases within a run.
     *
     * @return project frame
     */
    public long positionFrames() {
        Run r = current;
        if (r == null || state == State.STOPPED || r.finished) {
            return lastStartFrame;
        }
        long now = nanoClock.getAsLong();
        long played;
        synchronized (r.clock) {
            played = r.clock.estimate(now, safePosition(r), r.framesWritten);
        }
        return Math.min(r.startFrame + played, plan.get().length());
    }

    private static long safePosition(Run r) {
        try {
            return r.sink.positionFrames();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** @return the start frame of the last run (the cursor while STOPPED) */
    public long startFrame() {
        return lastStartFrame;
    }

    /** @return counters of the current or last run */
    public EngineStats stats() {
        return new EngineStats(blocksRendered.get(), shortWrites.get(), maxRenderNanos, suspectedUnderruns.get());
    }

    /**
     * Adds a listener.
     *
     * @param l the listener
     */
    public void addListener(PlaybackListener l) {
        listeners.add(Objects.requireNonNull(l, "listener"));
    }

    /**
     * Removes a listener.
     *
     * @param l the listener
     */
    public void removeListener(PlaybackListener l) {
        listeners.remove(l);
    }

    private void fireState(State oldState, State newState) {
        for (PlaybackListener l : listeners) {
            try {
                l.stateChanged(oldState, newState);
            } catch (RuntimeException ignored) {
                // a faulty listener must not break playback
            }
        }
    }

    // ------------------------------------------------------------------ one run

    /** One play-to-stop run: owns the sink, buffers and the audio thread. */
    private final class Run {
        final AudioSink sink;
        final int rate;
        final long startFrame;
        final PlaybackClock clock;
        final Thread thread;
        final long bufferNanos;
        volatile boolean paused;
        volatile boolean stopRequested;
        volatile boolean inWrite;
        volatile long framesWritten;
        boolean finished; // guarded by stateLock
        private final AtomicBoolean sinkClosed = new AtomicBoolean();

        private MixPlan mixPlan;
        private StereoMixer mixer;
        private final float[] left = new float[BLOCK_FRAMES];
        private final float[] right = new float[BLOCK_FRAMES];
        private final float[][] pair = {left, right};
        private final byte[] pcm = new byte[BLOCK_FRAMES * FRAME_BYTES];
        private int pendingBytes;
        private long nextFrame;

        Run(AudioSink sink, MixPlan p, long startFrame) {
            this.sink = sink;
            this.mixPlan = p;
            this.rate = p.sampleRate();
            this.startFrame = startFrame;
            this.mixer = new StereoMixer(p, BLOCK_FRAMES);
            this.clock = new PlaybackClock(rate, sink.bufferFrames());
            this.bufferNanos = (long) (sink.bufferFrames() * 1e9 / rate);
            this.nextFrame = startFrame;
            this.thread = new Thread(this::loop, "timbre-audio");
            this.thread.setDaemon(true);
        }

        void renderFirstBlock() {
            renderNext();
        }

        private void renderNext() {
            long t0 = System.nanoTime();
            int n = (int) Math.min(BLOCK_FRAMES, mixPlan.length() - nextFrame);
            mixer.mix(nextFrame, n, left, right);
            SampleCodec.encode(pair, 0, n, 2, WavEncoding.PCM_16, pcm);
            pendingBytes = n * FRAME_BYTES;
            nextFrame += n;
            long took = System.nanoTime() - t0;
            if (took > maxRenderNanos) {
                maxRenderNanos = took;
            }
        }

        void closeSink() {
            if (sinkClosed.compareAndSet(false, true)) {
                sink.close();
            }
        }

        void join(long millis) {
            if (Thread.currentThread() == thread || !thread.isAlive()) {
                return;
            }
            try {
                thread.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void awaitNotInWrite() {
            long deadline = System.nanoTime() + JOIN_MILLIS * 1_000_000L;
            while (inWrite && System.nanoTime() < deadline) {
                LockSupport.parkNanos(50_000L);
            }
        }

        private long lastReturn;

        private void loop() {
            try {
                lastReturn = nanoClock.getAsLong();
                while (true) {
                    if (!writePending()) {
                        return;
                    }
                    MixPlan np = PlaybackEngine.this.plan.get();
                    if (np != mixPlan) {
                        mixPlan = np;
                        mixer = new StereoMixer(np, BLOCK_FRAMES);
                    }
                    if (nextFrame >= mixPlan.length()) {
                        break;
                    }
                    renderNext();
                    blocksRendered.incrementAndGet();
                }
                while (true) {
                    if (stopRequested) {
                        return;
                    }
                    sink.drain();
                    if (stopRequested) {
                        return;
                    }
                    if (!paused) {
                        break;
                    }
                    waitWhilePaused();
                }
                end(null);
            } catch (Throwable t) {
                if (!stopRequested) {
                    end(t);
                }
            }
        }

        /** Writes the pending block, retrying the remainder of short writes. @return false if stopped */
        private boolean writePending() {
            int off = 0;
            while (off < pendingBytes) {
                if (stopRequested) {
                    return false;
                }
                inWrite = true;
                if (paused) {
                    inWrite = false;
                    waitWhilePaused();
                    continue;
                }
                long now = nanoClock.getAsLong();
                if (now - lastReturn > bufferNanos) {
                    suspectedUnderruns.incrementAndGet();
                }
                int want = pendingBytes - off;
                int n = sink.write(pcm, off, want);
                lastReturn = nanoClock.getAsLong();
                inWrite = false;
                if (n < want) {
                    shortWrites.incrementAndGet();
                }
                if (n > 0) {
                    off += n;
                    framesWritten += n / FRAME_BYTES;
                } else if (!paused && !stopRequested) {
                    LockSupport.parkNanos(500_000L);
                }
            }
            return !stopRequested;
        }

        private void waitWhilePaused() {
            while (paused && !stopRequested) {
                LockSupport.park(this);
            }
            lastReturn = nanoClock.getAsLong();
        }

        /** Ends the run from the audio thread: natural end ({@code error == null}) or failure. */
        private void end(Throwable error) {
            State old;
            synchronized (stateLock) {
                if (finished) {
                    return;
                }
                finished = true;
                old = state;
                state = State.STOPPED;
                if (current == this) {
                    current = null;
                }
            }
            closeSink();
            synchronized (clock) {
                clock.onStop();
            }
            fireState(old, State.STOPPED);
            for (PlaybackListener l : listeners) {
                try {
                    if (error == null) {
                        l.finished();
                    } else {
                        l.failed(error);
                    }
                } catch (RuntimeException ignored) {
                    // a faulty listener must not break playback
                }
            }
        }
    }
}
