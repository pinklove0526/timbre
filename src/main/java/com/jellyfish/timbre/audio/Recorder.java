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

import com.jellyfish.timbre.model.ChunkedSampleSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.LongSupplier;

/**
 * Records from a {@link CaptureSource} into a {@link ChunkedSampleSource} on its own capture thread.
 *
 * <p><b>Capture loop</b> (Milestone 5 designs D1', D2'', 3', 7'). Blocks of {@link #BLOCK_FRAMES} frames. Each
 * iteration: (1) if the cap is reached ({@code LENGTH_LIMIT}) or a stop was requested ({@code USER}), run the end
 * sequence; (2) otherwise read {@code availableBytes()} for the overflow check, then read one block (blocking);
 * the first returned data sets tFirstData; the bytes are decoded into reusable planar buffers, appended to the
 * chunk store, and the frames read, the peaks, the {@link ClipWatch} and the {@link SilenceWatch} are updated. A
 * block that would pass the cap is trimmed so the total is exactly the cap.
 *
 * <p><b>End sequence.</b> For USER, while the line is still running, the thread reads what
 * {@code availableBytes()} reported (rounded down to whole frames, never asking for more than is available, and
 * still respecting the cap); then, for every reason, it calls the source's {@code stop()} and then
 * {@code close()}, builds the {@link RecordingResult}, sets the state to IDLE, delivers
 * {@code stateChanged(IDLE)} and then exactly one {@code finished(result)}. A stopped source is never restarted.
 * A read that throws or returns an impossible count ends with ERROR and keeps the frames read so far; an
 * {@link OutOfMemoryError} from the chunk factory ends with OUT_OF_MEMORY and keeps everything stored before the
 * failing block. A zero-frame recording has a null source.
 *
 * <p><b>Dropouts (D2'').</b> Before each read, {@code availableBytes() >= bufferBytes - frameBytes} means a
 * suspected overflow. One dropout is counted per episode; an episode ends when {@code availableBytes()} falls
 * below half the buffer. Each time the condition holds (after the first data) the loss is estimated as
 * {@code max(0, round((now - tFirstData) * rate) - framesRead - availableBytes / frameBytes)} and the largest
 * estimate is kept. No silence is inserted for lost frames.
 *
 * <p><b>Threading:</b> the control methods ({@link #start}, {@link #stop()}, {@link #stopAndWait},
 * {@link #close()}) may be called from any thread (the JavaFX application thread in the app); the query
 * methods may be called from any thread at any time and never block. The capture thread is a daemon named
 * "timbre-capture"; it never takes a lock that a control thread holds, never blocks on the UI, and allocates
 * nothing on its hot path except one chunk per {@code chunkFrames} frames (see
 * {@link ChunkedSampleSource.Builder}). {@link #stop()} never blocks; {@link #stopAndWait} blocks for at most
 * twice its timeout (the UI should prefer {@link #stop()} and {@link RecorderListener#finished}).
 * {@link #pollPeaks} has a single consumer (the UI meter).
 */
public final class Recorder implements AutoCloseable {

    /** Frames per read. */
    public static final int BLOCK_FRAMES = 1024;

    /** The default length cap: 30 minutes (decision 7'). */
    public static final int DEFAULT_MAX_SECONDS = 30 * 60;

    /** The timeout {@link #close()} uses for each wait of its stop sequence. */
    public static final long CLOSE_TIMEOUT_MILLIS = 1000;

    private static final int FRAME_BYTES_PER_CHANNEL = 2;

    private final CaptureSourceFactory factory;
    private final int bufferFrames;
    private final long maxFrames;
    private final LongSupplier nanoClock;
    private final int chunkFrames;
    private final ChunkedSampleSource.ChunkFactory chunkFactory;
    private final List<RecorderListener> listeners = new CopyOnWriteArrayList<>();
    private final Object control = new Object();

    private volatile Session session;
    private volatile RecorderState state = RecorderState.IDLE;
    private volatile boolean closed;

    /**
     * Creates a recorder with the defaults: a 100 ms buffer ({@code sampleRate / 10} frames), a 30-minute cap,
     * {@link System#nanoTime()} and {@link ChunkedSampleSource#DEFAULT_CHUNK_FRAMES}-frame chunks.
     *
     * @param factory creates a fresh capture source for each recording
     */
    public Recorder(CaptureSourceFactory factory) {
        this(factory, 0, 0, System::nanoTime, ChunkedSampleSource.DEFAULT_CHUNK_FRAMES,
                ChunkedSampleSource.ChunkFactory.DEFAULT);
    }

    /**
     * Creates a recorder.
     *
     * @param factory      creates a fresh capture source for each recording
     * @param bufferFrames requested device buffer in frames; 0 means 100 ms ({@code sampleRate / 10})
     * @param maxFrames    the length cap in frames read; 0 means 30 minutes ({@code 30 * 60 * sampleRate})
     * @param nanoClock    the wall clock in nanoseconds for the dropout estimate
     * @param chunkFrames  frames per storage chunk, at least one
     * @param chunkFactory allocates the storage chunks
     * @throws IllegalArgumentException if a number is out of range
     */
    public Recorder(CaptureSourceFactory factory, int bufferFrames, long maxFrames, LongSupplier nanoClock,
            int chunkFrames, ChunkedSampleSource.ChunkFactory chunkFactory) {
        this.factory = Objects.requireNonNull(factory, "factory");
        if (bufferFrames < 0) {
            throw new IllegalArgumentException("bufferFrames must be >= 0: " + bufferFrames);
        }
        if (maxFrames < 0) {
            throw new IllegalArgumentException("maxFrames must be >= 0: " + maxFrames);
        }
        if (chunkFrames < 1) {
            throw new IllegalArgumentException("chunkFrames must be >= 1: " + chunkFrames);
        }
        this.bufferFrames = bufferFrames;
        this.maxFrames = maxFrames;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.chunkFrames = chunkFrames;
        this.chunkFactory = Objects.requireNonNull(chunkFactory, "chunkFactory");
    }

    /**
     * Adds a listener (called on the capture thread).
     *
     * @param listener the listener
     */
    public void addListener(RecorderListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    public void removeListener(RecorderListener listener) {
        listeners.remove(listener);
    }

    /**
     * Starts a recording: creates a fresh source from the factory, opens and starts it, and starts the capture
     * thread. Returns once the source is running. If opening fails, the source is closed, the state stays IDLE
     * and no thread is started.
     *
     * @param sampleRate frames per second, greater than zero
     * @param channels   1 (mono) or 2 (stereo)
     * @throws AudioDeviceException     if the source cannot be opened (the message is fit to show to the user)
     * @throws IllegalStateException    if already recording or closed
     * @throws IllegalArgumentException if a number is out of range
     */
    public void start(int sampleRate, int channels) throws AudioDeviceException {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("channels must be 1 or 2: " + channels);
        }
        synchronized (control) {
            if (closed) {
                throw new IllegalStateException("the recorder is closed");
            }
            if (state == RecorderState.RECORDING) {
                throw new IllegalStateException("already recording");
            }
            int buffer = bufferFrames > 0 ? bufferFrames : Math.max(1, sampleRate / 10);
            long cap = maxFrames > 0 ? maxFrames : (long) DEFAULT_MAX_SECONDS * sampleRate;
            CaptureSource source = Objects.requireNonNull(factory.create(), "the capture source factory returned null");
            Session s = new Session(source, sampleRate, channels, cap);
            try {
                source.open(sampleRate, channels, buffer);
                source.start();
            } catch (AudioDeviceException | RuntimeException e) {
                source.close();
                throw e;
            }
            s.begin();
            session = s;
            state = RecorderState.RECORDING;
            s.thread.start();
        }
    }

    /**
     * Requests the current recording to stop; returns at once. The capture thread notices the request when its
     * current read returns (on a running device within one block).
     *
     * @return true if a recording was asked to stop, false if not recording
     */
    public boolean stop() {
        Session s = session;
        if (s == null || state != RecorderState.RECORDING || s.result != null) {
            return false;
        }
        s.stopRequested = true;
        return true;
    }

    /**
     * Requests a stop and waits for the capture thread to finish. If it does not finish within the timeout (for
     * example because it is blocked in a read and no more data arrives), the safety net is used once: the
     * source's {@code stop()} and {@code close()} are called from this thread (this wakes the read; the data
     * still in the buffer is lost), and the call waits again for the same timeout.
     *
     * <p>When nothing is recording, returns the result of the most recent recording (empty if there was none).
     * Called on the capture thread itself (from a listener) it does not wait.
     *
     * @param timeoutMillis the wait for each of the two steps, at least 0 (0 means do not wait)
     * @return the result, or empty if the capture thread still runs after both waits
     * @throws IllegalArgumentException if the timeout is negative
     */
    public Optional<RecordingResult> stopAndWait(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0: " + timeoutMillis);
        }
        Session s = session;
        if (s == null) {
            return Optional.empty();
        }
        s.stopRequested = true;
        if (Thread.currentThread() == s.thread) {
            return Optional.ofNullable(s.result);
        }
        if (!join(s.thread, timeoutMillis)) {
            s.escalated = true;
            quietly(s.source::stop);
            quietly(s.source::close);
            if (!join(s.thread, timeoutMillis)) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(s.result);
    }

    private static boolean join(Thread t, long timeoutMillis) {
        if (timeoutMillis > 0) {
            try {
                t.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return !t.isAlive();
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // the safety net must not throw
        }
    }

    /**
     * Stops any recording through the same sequence as {@link #stopAndWait} (with
     * {@link #CLOSE_TIMEOUT_MILLIS}), which delivers its result to the listeners; afterwards the recorder is
     * unusable ({@link #start} throws). Idempotent.
     */
    @Override
    public void close() {
        synchronized (control) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (state == RecorderState.RECORDING) {
            stopAndWait(CLOSE_TIMEOUT_MILLIS);
        }
    }

    /** @return the current state */
    public RecorderState state() {
        return state;
    }

    /**
     * @return frames READ by the current (or most recent) recording; the elapsed time is this divided by the
     *         sample rate (decision 3'). 0 before the first recording.
     */
    public long framesRecorded() {
        Session s = session;
        return s == null ? 0 : s.framesRead;
    }

    /**
     * Copies the peak magnitude per channel since the previous call into {@code out} and resets it. Lock-free;
     * intended for a single consumer (the UI meter). A second call without new data gives zeros.
     *
     * @param out receives one peak per channel of the current (or most recent) recording
     * @return the number of channels written (0 before the first recording)
     * @throws IllegalArgumentException if {@code out} is shorter than the channel count
     */
    public int pollPeaks(float[] out) {
        Objects.requireNonNull(out, "out");
        Session s = session;
        if (s == null) {
            return 0;
        }
        if (out.length < s.channels) {
            throw new IllegalArgumentException("out has " + out.length + " slots, need " + s.channels);
        }
        for (int c = 0; c < s.channels; c++) {
            out[c] = Float.intBitsToFloat(s.peakBits.getAndSet(c, 0));
        }
        return s.channels;
    }

    /** @return true while the sustained-clipping warning holds (decision 3') */
    public boolean clippingWarning() {
        Session s = session;
        return s != null && s.clipping;
    }

    /** @return true while the input has been digitally silent for at least 3 seconds */
    public boolean silentWarning() {
        Session s = session;
        return s != null && s.silent;
    }

    /** @return suspected overflow episodes in the current (or most recent) recording (D2'') */
    public int suspectedDropouts() {
        Session s = session;
        return s == null ? 0 : s.dropouts;
    }

    /** @return the largest loss estimate of the current (or most recent) recording, in frames (D2'') */
    public long estimatedLostFrames() {
        Session s = session;
        return s == null ? 0 : s.lost;
    }

    private void fireState(RecorderState st) {
        for (RecorderListener l : listeners) {
            try {
                l.stateChanged(st);
            } catch (RuntimeException ignored) {
                // a faulty listener must not break recording
            }
        }
    }

    private void fireFinished(RecordingResult r) {
        for (RecorderListener l : listeners) {
            try {
                l.finished(r);
            } catch (RuntimeException ignored) {
                // a faulty listener must not break recording
            }
        }
    }

    /** One recording: its source, buffers and the capture thread. */
    private final class Session {

        final CaptureSource source;
        final int rate;
        final int channels;
        final int frameBytes;
        final long cap;
        final byte[] bytes;
        final float[][] planar;
        final ChunkedSampleSource.Builder builder;
        final ClipWatch clipWatch;
        final SilenceWatch silenceWatch;
        final AtomicIntegerArray peakBits;
        final Thread thread;

        volatile boolean stopRequested;
        volatile boolean escalated;
        volatile long framesRead;
        volatile int dropouts;
        volatile long lost;
        volatile boolean clipping;
        volatile boolean silent;
        volatile RecordingResult result;

        // capture-thread only
        int bufferBytes;
        boolean inEpisode;
        boolean haveFirstData;
        long tFirstData;

        Session(CaptureSource source, int rate, int channels, long cap) {
            this.source = source;
            this.rate = rate;
            this.channels = channels;
            this.frameBytes = channels * FRAME_BYTES_PER_CHANNEL;
            this.cap = cap;
            this.bytes = new byte[BLOCK_FRAMES * frameBytes];
            this.planar = new float[channels][BLOCK_FRAMES];
            this.builder = new ChunkedSampleSource.Builder(rate, channels, chunkFrames, chunkFactory);
            this.clipWatch = new ClipWatch(rate, channels);
            this.silenceWatch = new SilenceWatch(rate);
            this.peakBits = new AtomicIntegerArray(channels);
            this.thread = new Thread(this::run, "timbre-capture");
            this.thread.setDaemon(true);
        }

        /** Called after the source is open, before the thread starts. */
        void begin() {
            this.bufferBytes = Math.max(1, source.bufferFrames()) * frameBytes;
        }

        void run() {
            fireState(RecorderState.RECORDING);
            StopReason reason;
            String error = null;
            try {
                reason = loop();
            } catch (OutOfMemoryError e) {
                reason = StopReason.OUT_OF_MEMORY;
            } catch (RuntimeException | Error e) {
                if (escalated && e instanceof RuntimeException) {
                    reason = StopReason.USER;
                } else {
                    reason = StopReason.ERROR;
                    error = describe(e);
                }
            }
            quietly(source::stop);
            quietly(source::close);
            long frames = builder.frameCount();
            ChunkedSampleSource built = frames == 0 ? null : builder.build();
            RecordingResult r = new RecordingResult(built, frames, reason, dropouts, lost,
                    clipWatch.everClipped(), silenceWatch.everSilentWarning(), error);
            result = r;
            state = RecorderState.IDLE;
            fireState(RecorderState.IDLE);
            fireFinished(r);
        }

        private String describe(Throwable e) {
            String m = e.getMessage();
            return "The input device failed: " + (m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
        }

        private StopReason loop() {
            while (true) {
                if (framesRead >= cap) {
                    return StopReason.LENGTH_LIMIT;
                }
                if (stopRequested) {
                    if (!escalated) {
                        drain();
                    }
                    return StopReason.USER;
                }
                checkOverflow(source.availableBytes());
                int n = source.read(bytes, 0, bytes.length);
                if (n == 0) {
                    if (stopRequested) {
                        continue;
                    }
                    throw new IllegalStateException("the input stopped delivering audio");
                }
                checkCount(n, bytes.length);
                firstData();
                process(n / frameBytes);
            }
        }

        /** D1': read what is available while the line still runs; never asks for more than is available. */
        private void drain() {
            int target = source.availableBytes() / frameBytes * frameBytes;
            while (framesRead < cap) {
                int avail = source.availableBytes() / frameBytes * frameBytes;
                int want = Math.min(Math.min(target, bytes.length), avail);
                if (want <= 0) {
                    return;
                }
                int n = source.read(bytes, 0, want);
                if (n <= 0) {
                    return;
                }
                checkCount(n, want);
                firstData();
                process(n / frameBytes);
                target -= n;
            }
        }

        private void checkCount(int n, int asked) {
            if (n < 0 || n > asked || n % frameBytes != 0) {
                throw new IllegalStateException("the input returned an invalid byte count " + n + " for " + asked);
            }
        }

        private void firstData() {
            if (!haveFirstData) {
                haveFirstData = true;
                tFirstData = nanoClock.getAsLong();
            }
        }

        private void checkOverflow(int availableBytes) {
            if (availableBytes >= bufferBytes - frameBytes) {
                if (!inEpisode) {
                    inEpisode = true;
                    dropouts++;
                }
                if (haveFirstData) {
                    long wallFrames = Math.round((nanoClock.getAsLong() - tFirstData) * (double) rate / 1e9);
                    long estimate = wallFrames - framesRead - availableBytes / frameBytes;
                    if (estimate > lost) {
                        lost = estimate;
                    }
                }
            } else if (inEpisode && availableBytes < bufferBytes / 2) {
                inEpisode = false;
            }
        }

        private void process(int readFrames) {
            int frames = readFrames;
            if (framesRead + frames > cap) {
                frames = (int) (cap - framesRead);
            }
            SampleCodec.decode(bytes, frames, channels, WavEncoding.PCM_16, planar, 0);
            builder.append(planar, frames);
            float blockPeak = 0f;
            int full = 0;
            for (int c = 0; c < channels; c++) {
                float[] ch = planar[c];
                float peak = 0f;
                for (int i = 0; i < frames; i++) {
                    float a = Math.abs(ch[i]);
                    if (a > peak) {
                        peak = a;
                    }
                    if (a >= ClipWatch.FULL_SCALE) {
                        full++;
                    }
                }
                publishPeak(c, peak);
                if (peak > blockPeak) {
                    blockPeak = peak;
                }
            }
            clipWatch.add(frames, full);
            silenceWatch.add(frames, blockPeak);
            clipping = clipWatch.warning();
            silent = silenceWatch.warning();
            framesRead += frames;
        }

        private void publishPeak(int c, float peak) {
            while (true) {
                int cur = peakBits.get(c);
                if (Float.intBitsToFloat(cur) >= peak) {
                    return;
                }
                if (peakBits.compareAndSet(c, cur, Float.floatToRawIntBits(peak))) {
                    return;
                }
            }
        }
    }
}
