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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jellyfish.timbre.audio.support.FakeCaptureSource;
import com.jellyfish.timbre.audio.support.FakeCaptureSource.Call;
import com.jellyfish.timbre.model.ChunkedSampleSource;
import com.jellyfish.timbre.model.SampleSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link Recorder} against {@link FakeCaptureSource} and a test-controlled clock. No real device is opened. Waits
 * are bounded polls or latches; nothing synchronises with a sleep.
 */
@Timeout(60)
class RecorderTest {

    private static final int RATE = 44100;
    private static final long T0 = 5_000_000_000L;
    private static final long WAIT_MS = 5000;

    private final AtomicLong nanos = new AtomicLong(T0);
    private final Deque<FakeCaptureSource> fakes = new ArrayDeque<>();
    private final List<FakeCaptureSource> created = new ArrayList<>();
    private final Events events = new Events();
    private Recorder recorder;

    /** Records every callback with its thread. */
    private static final class Events implements RecorderListener {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        final List<RecordingResult> results = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch finished = new CountDownLatch(1);

        @Override
        public void stateChanged(RecorderState state) {
            log.add("state:" + state);
            threads.add(Thread.currentThread().getName());
        }

        @Override
        public void finished(RecordingResult result) {
            log.add("finished");
            threads.add(Thread.currentThread().getName());
            results.add(result);
            finished.countDown();
        }

        RecordingResult awaitFinished() throws InterruptedException {
            assertTrue(finished.await(WAIT_MS, TimeUnit.MILLISECONDS), "finished(...) must arrive");
            return results.get(results.size() - 1);
        }
    }

    private Recorder recorder(int bufferFrames, long maxFrames, int chunkFrames,
            ChunkedSampleSource.ChunkFactory chunkFactory) {
        recorder = new Recorder(() -> {
            FakeCaptureSource f = fakes.isEmpty() ? new FakeCaptureSource() : fakes.poll();
            created.add(f);
            return f;
        }, bufferFrames, maxFrames, nanos::get, chunkFrames, chunkFactory);
        recorder.addListener(events);
        return recorder;
    }

    private Recorder recorder(int bufferFrames) {
        return recorder(bufferFrames, 0, 4096, ChunkedSampleSource.ChunkFactory.DEFAULT);
    }

    private FakeCaptureSource fake() {
        return created.get(created.size() - 1);
    }

    private static boolean captureThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("timbre-capture") && t.isAlive());
    }

    private static void awaitNoCaptureThread() {
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (captureThreadAlive()) {
            if (System.nanoTime() > deadline) {
                fail("a timbre-capture thread is still alive");
            }
            LockSupport.parkNanos(200_000);
        }
    }

    private void awaitFrames(long frames) {
        long deadline = System.nanoTime() + WAIT_MS * 1_000_000L;
        while (recorder.framesRecorded() < frames) {
            if (System.nanoTime() > deadline) {
                fail("expected " + frames + " frames read, got " + recorder.framesRecorded());
            }
            LockSupport.parkNanos(100_000);
        }
        assertEquals(frames, recorder.framesRecorded(), "frames read");
    }

    /**
     * Ends a recording with {@code lastFrames} more frames fed while the capture thread is held, so the stop is
     * seen right after the held read: the frames beyond that read come only through the drain step.
     */
    private RecordingResult finishWith(int lastFrames) throws InterruptedException {
        FakeCaptureSource f = fake();
        f.holdReads();
        f.feedPattern(lastFrames);
        assertTrue(f.awaitHeld(WAIT_MS), "the capture thread must reach the gate");
        assertTrue(recorder.stop());
        f.releaseReads();
        Optional<RecordingResult> r = recorder.stopAndWait(WAIT_MS);
        assertTrue(r.isPresent());
        return r.get();
    }

    private static void assertPattern(SampleSource s, long frames, int channels) {
        assertEquals(frames, s.frameCount());
        assertEquals(channels, s.channelCount());
        assertEquals(RATE, s.sampleRate());
        float[][] all = new float[channels][(int) frames];
        s.read(0, (int) frames, all, 0);
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < frames; i++) {
                float expected = FakeCaptureSource.pattern(i, c) / 32768f;
                if (all[c][i] != expected) {
                    fail("frame " + i + " channel " + c + ": expected " + expected + " but was " + all[c][i]);
                }
            }
        }
    }

    private static int count(List<Call> calls, Call c) {
        int n = 0;
        for (Call x : calls) {
            if (x == c) {
                n++;
            }
        }
        return n;
    }

    @AfterEach
    void tearDown() {
        if (recorder != null) {
            for (FakeCaptureSource f : created) {
                f.forceWake();
            }
            recorder.close();
        }
        awaitNoCaptureThread();
    }

    // ------------------------------------------------------------------ (a) happy path

    @Test
    void stereoHappyPathKeepsEveryFedFrameInOrderAndCallsBackOnTheCaptureThread() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        assertEquals(RecorderState.RECORDING, recorder.state());
        fake().feedPattern(4000);
        awaitFrames(4000);
        fake().feedPattern(3000);
        awaitFrames(7000);
        RecordingResult r = finishWith(2500);
        assertEquals(StopReason.USER, r.reason());
        assertEquals(9500, r.framesRecorded());
        assertPattern(r.source(), 9500, 2);
        assertEquals(0, r.suspectedDropouts());
        assertEquals(0, r.estimatedLostFrames());
        assertNull(r.errorMessage());
        assertEquals(RecorderState.IDLE, recorder.state());
        assertEquals(List.of("state:RECORDING", "state:IDLE", "finished"), events.log);
        assertEquals(List.of("timbre-capture", "timbre-capture", "timbre-capture"), events.threads);
        assertEquals(1, events.results.size());
    }

    @Test
    void monoHappyPath() throws Exception {
        recorder(4410);
        recorder.start(RATE, 1);
        fake().feedPattern(1500);
        awaitFrames(1500);
        RecordingResult r = finishWith(3001);
        assertEquals(StopReason.USER, r.reason());
        assertPattern(r.source(), 4501, 1);
        assertEquals(1, events.results.size());
        assertEquals(4410, fake().bufferFrames());
    }

    @Test
    void defaultBufferIsOneTenthOfTheRate() throws Exception {
        recorder(0);
        recorder.start(48000, 2);
        assertEquals(4800, fake().bufferFrames());
        assertTrue(recorder.stopAndWait(50).isPresent());
    }

    // ------------------------------------------------------------------ (b) D1'

    @Test
    void stopSequenceDrainsTheTailWhileRunningThenStopsAndClosesAndNeverRestarts() throws Exception {
        recorder(8192);
        recorder.start(RATE, 2);
        fake().feedPattern(2048);
        awaitFrames(2048);
        RecordingResult r = finishWith(3372); // 1024 by the held read, 2348 = 1024 + 1024 + 300 by the drain
        assertEquals(5420, r.framesRecorded());
        assertPattern(r.source(), 5420, 2);
        FakeCaptureSource f = fake();
        List<Call> calls = f.calls();
        assertEquals(Call.OPEN, calls.get(0));
        assertEquals(Call.START, calls.get(1));
        assertEquals(Call.STOP, calls.get(calls.size() - 2));
        assertEquals(Call.CLOSE, calls.get(calls.size() - 1));
        assertEquals(Call.AVAILABLE, calls.get(calls.size() - 3), "the drain checks available before stopping");
        assertEquals(1, count(calls, Call.START));
        assertEquals(1, count(calls, Call.STOP));
        assertEquals(1, count(calls, Call.CLOSE));
        assertTrue(calls.lastIndexOf(Call.READ) < calls.indexOf(Call.STOP), "no read after stop");
        assertEquals(0, f.pendingBytes(), "the drain emptied the buffer");
        for (int i = 2; i < calls.size(); i++) {
            assertEquals("timbre-capture", f.callThreads().get(i), "call " + i + " " + calls.get(i));
        }
        // frames fed after the stop are not recorded
        f.feedPattern(1000);
        assertEquals(4000, f.droppedIdleBytes());
        assertEquals(5420, recorder.stopAndWait(10).orElseThrow().framesRecorded());
    }

    // ------------------------------------------------------------------ (c) stop while blocked

    @Test
    void stopWhileBlockedWithNoDataEscalatesAndReturnsWhatWasFed() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        fake().feedPattern(2048);
        awaitFrames(2048);
        long t0 = System.nanoTime();
        Optional<RecordingResult> r = recorder.stopAndWait(150);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(r.isPresent());
        assertTrue(ms >= 140 && ms < 1500, "one timeout then the safety net: " + ms + " ms");
        assertEquals(StopReason.USER, r.get().reason());
        assertPattern(r.get().source(), 2048, 2);
        assertTrue(fake().isClosed());
        List<String> threads = fake().callThreads();
        List<Call> calls = fake().calls();
        int firstStop = calls.indexOf(Call.STOP);
        assertEquals(Thread.currentThread().getName(), threads.get(firstStop), "the safety net stops from here");
        assertTrue(calls.indexOf(Call.CLOSE) > firstStop);
    }

    @Test
    void escalationWakesAFakeThatIgnoresStopThroughClose() throws Exception {
        fakes.add(new FakeCaptureSource().wakes(false, true));
        recorder(4410);
        recorder.start(RATE, 2);
        fake().feedPattern(1024);
        awaitFrames(1024);
        Optional<RecordingResult> r = recorder.stopAndWait(100);
        assertTrue(r.isPresent());
        assertEquals(1024, r.get().framesRecorded());
        assertEquals(StopReason.USER, r.get().reason());
    }

    @Test
    void aSourceThatNeverWakesGivesAnEmptyResultInsteadOfHanging() throws Exception {
        fakes.add(new FakeCaptureSource().wakes(false, false));
        recorder(4410);
        recorder.start(RATE, 2);
        fake().feedPattern(1024);
        awaitFrames(1024); // the capture thread is now in (or entering) its next read, which nothing wakes
        long t0 = System.nanoTime();
        Optional<RecordingResult> r = recorder.stopAndWait(100);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(r.isEmpty(), "the thread still lives");
        assertTrue(ms >= 190 && ms < 2000, "two timeouts: " + ms + " ms");
        assertTrue(captureThreadAlive());
        fake().forceWake();
        RecordingResult done = events.awaitFinished();
        assertEquals(1024, done.framesRecorded());
        awaitNoCaptureThread();
    }

    // ------------------------------------------------------------------ (d) length cap

    @Test
    void lengthCapTrimsToExactlyTheCapAndEndsByItself() throws Exception {
        recorder(16384, 5000, 4096, ChunkedSampleSource.ChunkFactory.DEFAULT);
        recorder.start(RATE, 2);
        FakeCaptureSource f = fake();
        f.holdReads();
        f.feedPattern(1024);
        assertTrue(f.awaitHeld(WAIT_MS));
        f.feedPattern(7168);
        f.releaseReads();
        RecordingResult r = events.awaitFinished();
        assertEquals(StopReason.LENGTH_LIMIT, r.reason());
        assertEquals(5000, r.framesRecorded());
        assertPattern(r.source(), 5000, 2);
        awaitNoCaptureThread();
        assertEquals(RecorderState.IDLE, recorder.state());
        assertEquals(5000, recorder.framesRecorded());
        assertTrue(f.isClosed());
        f.feedPattern(500);
        assertEquals(2000, f.droppedIdleBytes(), "later feeding is ignored");
        assertFalse(recorder.stop(), "nothing to stop");
    }

    @Test
    void defaultCapIsThirtyMinutesOfFrames() throws Exception {
        // checked through a cap of 0 (default) and a tiny rate, so the cap is 30 * 60 * 1 = 1800 frames
        recorder(0, 0, 4096, ChunkedSampleSource.ChunkFactory.DEFAULT);
        recorder.start(1, 1);
        FakeCaptureSource f = fake();
        assertEquals(1, f.bufferFrames(), "rate / 10 rounds up to at least one frame");
        for (int i = 0; i < 1801; i++) {
            f.feedPattern(1);
            if (i < 1800) {
                awaitFrames(i + 1);
            }
        }
        RecordingResult r = events.awaitFinished();
        assertEquals(StopReason.LENGTH_LIMIT, r.reason());
        assertEquals(1800, r.framesRecorded());
    }

    // ------------------------------------------------------------------ (e) D2''

    /** Holds the capture thread after one more read, advances the clock, feeds a burst, releases. */
    private void stall(long advanceNanos, int burstFrames) throws InterruptedException {
        FakeCaptureSource f = fake();
        f.holdReads();
        f.feedPattern(1024);
        assertTrue(f.awaitHeld(WAIT_MS));
        nanos.addAndGet(advanceNanos);
        f.feedPattern(burstFrames);
        f.releaseReads();
    }

    @Test
    void dropoutEpisodesAndTheLossEstimateFollowDesignD2() throws Exception {
        recorder(4410); // bufferBytes 17640, one frame 4 bytes: overflow when available >= 17636
        recorder.start(RATE, 2);
        FakeCaptureSource f = fake();
        f.feedPattern(1024);
        awaitFrames(1024); // tFirstData = T0
        assertEquals(0, recorder.suspectedDropouts());

        // stall 1: 250 ms; the buffer fills (4410 kept, 5590 dropped)
        stall(250_000_000L, 10_000);
        awaitFrames(2048 + 4410);
        assertEquals(5590L * 4, f.droppedFullBytes());
        assertEquals(1, recorder.suspectedDropouts());
        // round(0.25 * 44100) = 11025; 11025 - 2048 read - 4410 waiting = 4567
        assertEquals(4567, recorder.estimatedLostFrames());

        // stall 2: another 500 ms; round(0.75 * 44100) = 33075; 33075 - 7482 - 4410 = 21183
        stall(500_000_000L, 10_000);
        awaitFrames(7482 + 4410);
        assertEquals(2, recorder.suspectedDropouts());
        assertEquals(21183, recorder.estimatedLostFrames());

        // stall 3: no time passes, an exact fill: 33075 - 12916 - 4410 = 15749 < 21183, the largest is kept
        stall(0, 4410);
        awaitFrames(12916 + 4410);
        assertEquals(3, recorder.suspectedDropouts());
        assertEquals(21183, recorder.estimatedLostFrames());

        // a burst one frame short of the threshold (4408 frames = 17632 bytes) is not an overflow
        stall(0, 4408);
        awaitFrames(17326 + 1024 + 4408);
        assertEquals(3, recorder.suspectedDropouts());

        // exactly bufferBytes - one frame (4409 frames = 17636 bytes) is
        stall(0, 4409);
        awaitFrames(22758 + 1024 + 4409);
        assertEquals(4, recorder.suspectedDropouts());

        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertEquals(4, r.suspectedDropouts());
        assertEquals(21183, r.estimatedLostFrames());
        assertEquals(28191, r.framesRecorded(), "no silence is inserted for lost frames");
    }

    @Test
    void aFullBufferWithoutLossDoesNotInflateTheEstimate() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        fake().feedPattern(1024);
        awaitFrames(1024);
        // 123219955 ns = 5434 frames of wall time; exactly 1024 + 4410 frames arrived: nothing was lost.
        // D2'' gives max(0, 5434 - 2048 - 4410) = 0; the D2' formula (5434 - 2048) would claim 3386.
        stall(123_219_955L, 4410);
        awaitFrames(2048 + 4410);
        assertEquals(0, fake().droppedFullBytes());
        assertEquals(1, recorder.suspectedDropouts(), "a full buffer is still a suspected overflow");
        assertEquals(0, recorder.estimatedLostFrames());
    }

    @Test
    void anOverflowWhileTheFirstReadIsHeldCountsButEstimatesNothing() throws Exception {
        recorder(2048);
        recorder.start(RATE, 2);
        FakeCaptureSource f = fake();
        f.holdReads();
        f.feedPattern(1024);
        assertTrue(f.awaitHeld(WAIT_MS));
        nanos.addAndGet(1_000_000_000L);
        f.feedPattern(5000);
        f.releaseReads();
        awaitFrames(1024 + 2048);
        // the held read already returned the first data, so the check after it has tFirstData = now
        assertEquals(1, recorder.suspectedDropouts());
        assertEquals(0, recorder.estimatedLostFrames());
    }

    @Test
    void noStallMeansNoDropouts() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        for (int i = 0; i < 20; i++) {
            fake().feedPattern(1024);
            nanos.addAndGet(23_219_955L);
            awaitFrames(1024L * (i + 1));
        }
        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertEquals(0, r.suspectedDropouts());
        assertEquals(0, r.estimatedLostFrames());
    }

    // ------------------------------------------------------------------ (f) clipping and silence

    @Test
    void clippingWarningRisesAfterSustainedFullScaleAndFallsAgain() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        FakeCaptureSource f = fake();
        long frames = 0;
        for (int b = 0; b < 44; b++) {
            f.feedFrames(1024, (i, c) -> 1000);
            awaitFrames(frames += 1024);
        }
        assertFalse(recorder.clippingWarning());
        // 21 full-scale samples per block (channel 0); over a 44-block window (90112 samples) 43 blocks are needed
        for (int b = 0; b < 43; b++) {
            f.feedFrames(1024, (i, c) -> c == 0 && i % 1024 < 21 ? 32767 : -1000);
            awaitFrames(frames += 1024);
            assertEquals(b == 42, recorder.clippingWarning(), "after " + (b + 1) + " clipped blocks");
        }
        f.feedFrames(1024, (i, c) -> 1000);
        awaitFrames(frames += 1024);
        assertTrue(recorder.clippingWarning(), "43 clipped blocks still in the window");
        f.feedFrames(1024, (i, c) -> 1000);
        awaitFrames(frames += 1024);
        assertFalse(recorder.clippingWarning(), "42 clipped blocks: 882 of 90112");
        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertTrue(r.everClipped());
        assertFalse(r.silentWarningRaised());
    }

    @Test
    void silentWarningAfterThreeSecondsOfZeros() throws Exception {
        recorder(4410);
        recorder.start(RATE, 1);
        FakeCaptureSource f = fake();
        long frames = 0;
        for (int b = 0; b < 129; b++) {
            f.feedFrames(1024, (i, c) -> 0);
            awaitFrames(frames += 1024);
        }
        assertFalse(recorder.silentWarning(), "132096 frames");
        f.feedFrames(203, (i, c) -> 0);
        awaitFrames(frames += 203);
        assertFalse(recorder.silentWarning(), "132299 frames");
        f.feedFrames(1, (i, c) -> 1); // one LSB is still below -90 dBFS
        awaitFrames(frames += 1);
        assertTrue(recorder.silentWarning(), "132300 frames = 3 s");
        f.feedFrames(1024, (i, c) -> 5000);
        awaitFrames(frames += 1024);
        assertFalse(recorder.silentWarning());
        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertTrue(r.silentWarningRaised());
        assertFalse(r.everClipped());
    }

    // ------------------------------------------------------------------ (g) open failure

    @Test
    void openFailureLeavesTheRecorderIdleWithoutAThreadAndAWorkingSourceRecordsNext() throws Exception {
        FakeCaptureSource broken = new FakeCaptureSource().failOpen("No usable input device for 16-bit stereo");
        fakes.add(broken);
        recorder(4410);
        assertFalse(recorder.stop(), "idle");
        AudioDeviceException e = assertThrows(AudioDeviceException.class, () -> recorder.start(RATE, 2));
        assertEquals("No usable input device for 16-bit stereo", e.getMessage());
        assertEquals(RecorderState.IDLE, recorder.state());
        assertFalse(captureThreadAlive());
        assertTrue(broken.isClosed(), "a failed source is closed");
        assertEquals(List.of(Call.OPEN, Call.CLOSE), broken.calls());
        assertTrue(events.log.isEmpty());

        recorder.start(RATE, 2);
        assertEquals(RecorderState.RECORDING, recorder.state());
        assertThrows(IllegalStateException.class, () -> recorder.start(RATE, 2));
        assertThrows(IllegalArgumentException.class, () -> new Recorder(FakeCaptureSource::new).start(RATE, 3));
        fake().feedPattern(1024);
        awaitFrames(1024);
        assertEquals(1024, recorder.stopAndWait(50).orElseThrow().framesRecorded());
        assertFalse(recorder.stop(), "idle again");
        assertEquals(2, created.size());
    }

    @Test
    void secondRecordingUsesAFreshSource() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        FakeCaptureSource first = fake();
        first.feedPattern(1024);
        awaitFrames(1024);
        recorder.stopAndWait(50).orElseThrow();
        events.finished = new CountDownLatch(1);
        recorder.start(RATE, 1);
        FakeCaptureSource second = fake();
        assertTrue(first != second);
        assertEquals(1, count(first.calls(), Call.START));
        second.feedPattern(700);
        awaitFrames(700);
        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertPattern(r.source(), 700, 1);
        assertEquals(2, events.results.size());
    }

    // ------------------------------------------------------------------ (h) read error

    @Test
    void readErrorEndsWithErrorAndKeepsTheFramesReadBefore() throws Exception {
        fakes.add(new FakeCaptureSource().failAfterBytes(3000L * 4, "test: device unplugged"));
        recorder(8192);
        recorder.start(RATE, 2);
        fake().feedPattern(4096);
        RecordingResult r = events.awaitFinished();
        assertEquals(StopReason.ERROR, r.reason());
        assertNotNull(r.errorMessage());
        assertTrue(r.errorMessage().contains("test: device unplugged"), r.errorMessage());
        assertPattern(r.source(), 3000, 2);
        assertTrue(fake().isClosed());
        awaitNoCaptureThread();
        assertEquals(RecorderState.IDLE, recorder.state());
    }

    // ------------------------------------------------------------------ (i) out of memory

    @Test
    void outOfMemoryOnTheThirdChunkKeepsWhatWasStored() throws Exception {
        int[] calls = {0};
        ChunkedSampleSource.ChunkFactory failsOnThird = (frames, channels) -> {
            if (++calls[0] == 3) {
                throw new OutOfMemoryError("test: third chunk");
            }
            return new float[channels][frames];
        };
        recorder(4410, 0, 1000, failsOnThird);
        recorder.start(RATE, 2);
        fake().feedPattern(1024); // chunks 1 and 2
        awaitFrames(1024);
        fake().feedPattern(1024); // needs chunk 3
        RecordingResult r = events.awaitFinished();
        assertEquals(StopReason.OUT_OF_MEMORY, r.reason());
        assertNull(r.errorMessage());
        assertPattern(r.source(), 1024, 2);
        assertTrue(fake().isClosed());
        assertEquals(1024, recorder.framesRecorded());
    }

    // ------------------------------------------------------------------ (j) peaks

    @Test
    void pollPeaksGivesThePeakPerChannelSinceTheLastPollThenResets() throws Exception {
        recorder(4410);
        float[] out = new float[2];
        assertEquals(0, recorder.pollPeaks(out), "nothing recorded yet");
        recorder.start(RATE, 2);
        FakeCaptureSource f = fake();
        f.feedFrames(1024, (i, c) -> c == 0 ? (i == 500 ? -20000 : 1000) : (i == 7 ? 12000 : -300));
        awaitFrames(1024);
        assertEquals(2, recorder.pollPeaks(out));
        assertEquals(0.6103515625f, out[0]);
        assertEquals(0.3662109375f, out[1]);
        recorder.pollPeaks(out);
        assertEquals(0f, out[0], "reset after the call");
        assertEquals(0f, out[1]);
        f.feedFrames(1024, (i, c) -> c == 0 ? (i == 1030 ? 5000 : 10) : (i == 2000 ? -7000 : 10));
        awaitFrames(2048);
        f.feedFrames(1024, (i, c) -> c == 0 ? (i == 3000 ? -9000 : 10) : 100);
        awaitFrames(3072);
        recorder.pollPeaks(out);
        assertEquals(0.274658203125f, out[0], "the maximum across both blocks");
        assertEquals(0.213623046875f, out[1]);
        assertThrows(IllegalArgumentException.class, () -> recorder.pollPeaks(new float[1]));
        recorder.stopAndWait(50).orElseThrow();
    }

    // ------------------------------------------------------------------ (k) frames recorded

    @Test
    void framesRecordedIsFramesReadAndGivesTheElapsedTime() throws Exception {
        recorder(4410);
        assertEquals(0, recorder.framesRecorded());
        recorder.start(RATE, 2);
        assertEquals(0, recorder.framesRecorded());
        fake().feedPattern(1000);
        awaitFrames(1000);
        fake().feedPattern(1205);
        awaitFrames(2205);
        assertEquals(0.05, recorder.framesRecorded() / (double) RATE, 1e-12, "2205 frames = 50 ms");
        nanos.addAndGet(10_000_000_000L); // wall time does not change the elapsed time
        assertEquals(2205, recorder.framesRecorded());
        RecordingResult r = finishWith(1100);
        assertEquals(3305, r.framesRecorded());
        assertEquals(3305, recorder.framesRecorded());
    }

    // ------------------------------------------------------------------ (l) close and listeners

    @Test
    void closeWhileRecordingDeliversTheResultAndEndsTheThread() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        fake().feedPattern(1500);
        awaitFrames(1500);
        recorder.close();
        assertEquals(1, events.results.size());
        assertEquals(1500, events.results.get(0).framesRecorded());
        awaitNoCaptureThread();
        recorder.close();
        assertEquals(1, events.results.size(), "close twice is harmless");
        assertThrows(IllegalStateException.class, () -> recorder.start(RATE, 2));
        assertFalse(recorder.stop());
    }

    @Test
    void aThrowingListenerDoesNotStopTheRecording() throws Exception {
        recorder = new Recorder(() -> {
            FakeCaptureSource f = new FakeCaptureSource();
            created.add(f);
            return f;
        }, 4410, 0, nanos::get, 4096, ChunkedSampleSource.ChunkFactory.DEFAULT);
        recorder.addListener(new RecorderListener() {
            @Override
            public void stateChanged(RecorderState state) {
                throw new IllegalStateException("listener fails on " + state);
            }

            @Override
            public void finished(RecordingResult result) {
                throw new IllegalStateException("listener fails on finished");
            }
        });
        recorder.addListener(events);
        recorder.start(RATE, 2);
        fake().feedPattern(2000);
        awaitFrames(2000);
        RecordingResult r = finishWith(1500);
        assertEquals(3500, r.framesRecorded());
        assertEquals(List.of("state:RECORDING", "state:IDLE", "finished"), events.log);
    }

    // ------------------------------------------------------------------ (m) zero frames

    @Test
    void aZeroFrameRecordingHasNoSource() throws Exception {
        recorder(4410);
        recorder.start(RATE, 2);
        RecordingResult r = recorder.stopAndWait(50).orElseThrow();
        assertNull(r.source());
        assertEquals(0, r.framesRecorded());
        assertEquals(StopReason.USER, r.reason());
        assertEquals(1, events.results.size());
    }
}
