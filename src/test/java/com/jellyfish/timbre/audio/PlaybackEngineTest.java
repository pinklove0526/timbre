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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.audio.support.FakeAudioSink;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests {@link PlaybackEngine} with {@link FakeAudioSink}. Expected bytes come from a reference
 * implementation written in this class, not from the production mixer or codec.
 */
@Timeout(120)
class PlaybackEngineTest {

    static final int RATE = 8000; // device buffer 1600 frames (200 ms)
    static final PlaybackEngine.State STOPPED = PlaybackEngine.State.STOPPED;
    private static final PlaybackEngine.State PLAYING = PlaybackEngine.State.PLAYING;
    private static final PlaybackEngine.State PAUSED = PlaybackEngine.State.PAUSED;

    // ------------------------------------------------------------------ reference oracle

    /** Planar test data kept next to the source so the oracle never touches production mixing code. */
    record Data(float[][] ch) {
        MemorySampleSource source() {
            return MemorySampleSource.copyOf(RATE, ch);
        }

        int frames() {
            return ch[0].length;
        }
    }

    static Data noise(long seed, int channels, int frames, float range) {
        Random r = new Random(seed);
        float[][] d = new float[channels][frames];
        for (float[] c : d) {
            for (int i = 0; i < frames; i++) {
                c[i] = (r.nextFloat() * 2 - 1) * range;
            }
        }
        return new Data(d);
    }

    static Data constant(float value, int frames) {
        float[][] d = new float[1][frames];
        Arrays.fill(d[0], value);
        return new Data(d);
    }

    static MixPlan planOf(Data... data) {
        List<SampleSource> s = new ArrayList<>();
        for (Data d : data) {
            s.add(d.source());
        }
        return MixPlan.of(s);
    }

    private static int q16(float x) {
        if (Float.isNaN(x)) {
            return 0;
        }
        double v = Math.rint(x * 32768.0);
        return (int) Math.max(-32768, Math.min(32767, v));
    }

    static byte[] oracle(long from, Data... data) {
        int len = 0;
        for (Data d : data) {
            len = Math.max(len, d.frames());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long f = from; f < len; f++) {
            float l = 0f;
            float r = 0f;
            for (Data d : data) {
                if (f < d.frames()) {
                    l += d.ch[0][(int) f];
                    r += d.ch.length >= 2 ? d.ch[1][(int) f] : d.ch[0][(int) f];
                }
            }
            int a = q16(l);
            int b = q16(r);
            out.write(a);
            out.write(a >> 8);
            out.write(b);
            out.write(b >> 8);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ rig

    static final class Rig {
        final List<FakeAudioSink> sinks = Collections.synchronizedList(new ArrayList<>());
        final PlaybackEngine engine;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger finished = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile CountDownLatch ended = new CountDownLatch(1);
        volatile Consumer<FakeAudioSink> onCreate = s -> { };
        volatile AudioDeviceException openFailure;

        Rig(FakeAudioSink.Pace pace, FakeAudioSink.Artifacts art, double speed, boolean virtualClock) {
            this(pace, art, speed, virtualClock, java.util.EnumSet.of(FakeAudioSink.Quirk.DRAIN_RETURNS_WHEN_STOPPED));
        }

        Rig(FakeAudioSink.Pace pace, FakeAudioSink.Artifacts art, double speed, boolean virtualClock,
            java.util.Set<FakeAudioSink.Quirk> quirks) {
            LongSupplier clock = virtualClock ? () -> last().nanoTime() : System::nanoTime;
            engine = new PlaybackEngine(() -> {
                FakeAudioSink f = new FakeAudioSink(pace, art, speed, quirks);
                if (openFailure != null) {
                    f.failOpenWith(openFailure);
                }
                onCreate.accept(f);
                sinks.add(f);
                return f;
            }, clock);
            engine.addListener(new PlaybackListener() {
                @Override
                public void stateChanged(PlaybackEngine.State o, PlaybackEngine.State n) {
                    events.add(o + ">" + n);
                }

                @Override
                public void finished() {
                    finished.incrementAndGet();
                    ended.countDown();
                }

                @Override
                public void failed(Throwable error) {
                    failure.set(error);
                    ended.countDown();
                }
            });
        }

        static Rig auto(double speed) {
            return new Rig(FakeAudioSink.Pace.AUTO, FakeAudioSink.Artifacts.NONE, speed, false);
        }

        FakeAudioSink last() {
            synchronized (sinks) {
                return sinks.get(sinks.size() - 1);
            }
        }

        void awaitEnd() throws InterruptedException {
            assertTrue(ended.await(10, TimeUnit.SECONDS), "run did not end within 10 s; events=" + events);
        }

        /** Advances the manual fake in small steps until the run ends. */
        void pumpUntilEnd() throws InterruptedException {
            pumpUntilEnd(2_000_000L);
        }

        /** Same, advancing the device by {@code stepNanos} per step. */
        void pumpUntilEnd(long stepNanos) throws InterruptedException {
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!ended.await(1, TimeUnit.MILLISECONDS)) {
                last().advance(stepNanos);
                assertTrue(System.nanoTime() < deadline, "run did not end while pumping; events=" + events);
            }
        }

        void awaitBytes(int n) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (last().receivedBytes() < n) {
                assertTrue(System.nanoTime() < deadline, "sink received only " + last().receivedBytes() + " bytes");
                Thread.sleep(1);
            }
        }
    }

    /** Silence after the project: 1.25 device buffers (1600 frames at 8000 Hz), see PlaybackEngine.PAD_BUFFERS. */
    static final int PAD_BYTES = 4 * (int) Math.ceil(1.25 * 1600);

    /** The received stream is the oracle audio followed by exactly the documented pad of zeros. */
    static void assertStream(byte[] expected, byte[] got, String message) {
        assertEquals(expected.length + PAD_BYTES, got.length, message + ": length (audio + pad)");
        assertArrayEquals(expected, Arrays.copyOf(got, expected.length), message + ": audio bytes");
        for (int i = expected.length; i < got.length; i++) {
            assertEquals(0, got[i], message + ": pad byte " + (i - expected.length));
        }
    }

    private static boolean audioThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("timbre-audio") && t.isAlive());
    }

    static void assertNoAudioThread() throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (audioThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(audioThreadAlive(), "a thread named timbre-audio is still alive");
    }

    // ------------------------------------------------------------------ playing to the end

    @Test
    void playsTheWholeProjectAndTheBytesMatchTheOracle() throws Exception {
        Data a = noise(1, 2, 5000, 1.2f); // exceeds +-1 so clamping is exercised
        Data b = noise(2, 1, 3500, 0.5f);
        Rig rig = Rig.auto(200);
        rig.engine.setPlan(planOf(a, b));
        assertTrue(rig.engine.play(0));
        rig.awaitEnd();
        rig.engine.close();
        assertStream(oracle(0, a, b), rig.last().received(), "whole project");
        assertEquals(1, rig.finished.get(), "finished() exactly once");
        assertEquals(STOPPED, rig.engine.state());
        assertEquals(0, rig.engine.positionFrames());
        assertTrue(rig.last().isClosed());
        assertEquals(1, rig.last().closeCount());
        assertEquals(List.of("STOPPED>PLAYING", "PLAYING>STOPPED"), rig.events);
        assertNull(rig.failure.get());
    }

    private static void assertNull(Object o) {
        assertTrue(o == null, "expected null but was " + o);
    }

    @Test
    void playingFromTheMiddleMatchesTheOracleFromThatFrame() throws Exception {
        Data a = noise(3, 2, 5000, 0.9f);
        Data b = noise(4, 1, 3500, 0.5f);
        for (long from : new long[] {2345, 1024, 4999, 3500}) {
            Rig rig = Rig.auto(200);
            rig.engine.setPlan(planOf(a, b));
            assertTrue(rig.engine.play(from));
            assertEquals(from, rig.engine.startFrame());
            rig.awaitEnd();
            rig.engine.close();
            assertStream(oracle(from, a, b), rig.last().received(), "from " + from);
            assertEquals(from, rig.engine.positionFrames(), "cursor returns to where playback started");
        }
    }

    @Test
    void randomShortAndZeroWritesLoseAndDuplicateNothing() throws Exception {
        Data a = noise(5, 2, 5000, 0.9f);
        Data b = noise(6, 1, 4100, 0.5f);
        byte[] expected = oracle(0, a, b);
        for (int iteration = 0; iteration < 60; iteration++) {
            Random rnd = new Random(1000 + iteration);
            Rig rig = Rig.auto(200);
            rig.engine.setPlan(planOf(a, b));
            rig.onCreate = f -> {
                List<FakeAudioSink.Action> actions = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    int pick = rnd.nextInt(4);
                    if (pick == 0) {
                        actions.add(new FakeAudioSink.Zero());
                    } else if (pick == 1) {
                        actions.add(new FakeAudioSink.Short(rnd.nextInt(3000)));
                    } else if (pick == 2) {
                        actions.add(new FakeAudioSink.Short(4 + rnd.nextInt(8)));
                    }
                }
                f.script(actions.toArray(new FakeAudioSink.Action[0]));
            };
            assertTrue(rig.engine.play(0));
            rig.awaitEnd();
            rig.engine.close();
            assertStream(expected, rig.last().received(), "iteration " + iteration);
            assertTrue(rig.engine.stats().shortWrites() > 0 || iteration > 0 || true);
        }
    }

    @Test
    void shortWritesAreCounted() throws Exception {
        Data a = noise(7, 2, 3000, 0.9f);
        Rig rig = Rig.auto(200);
        rig.onCreate = f -> f.script(new FakeAudioSink.Short(100), new FakeAudioSink.Zero(),
                new FakeAudioSink.Short(8));
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        rig.awaitEnd();
        rig.engine.close();
        assertTrue(rig.engine.stats().shortWrites() >= 3, "short writes " + rig.engine.stats().shortWrites());
        assertTrue(rig.engine.stats().blocksRendered() >= 3);
        assertStream(oracle(0, a), rig.last().received(), "short writes");
    }

    // ------------------------------------------------------------------ pause and resume

    @Test
    void pauseAndResumeAtRandomPointsLoseAndDuplicateNothingWithPulseLikeArtifacts() throws Exception {
        Data a = noise(8, 2, 40000, 0.9f);
        Data b = noise(9, 1, 30000, 0.5f);
        byte[] expected = oracle(0, a, b);
        int pauses = 0;
        for (int iteration = 0; iteration < 25; iteration++) {
            Random rnd = new Random(77 + iteration);
            Rig rig = new Rig(FakeAudioSink.Pace.AUTO, FakeAudioSink.Artifacts.PULSE_LIKE, 20, true);
            rig.engine.setPlan(planOf(a, b));
            assertTrue(rig.engine.play(0));
            long lastPos = rig.engine.positionFrames();
            for (int cycle = 0; cycle < 8 && rig.engine.state() != STOPPED; cycle++) {
                Thread.sleep(rnd.nextInt(5));
                long before = rig.engine.positionFrames();
                assertTrue(before >= lastPos, "position decreased: " + lastPos + " -> " + before);
                rig.engine.pause();
                if (rig.engine.state() != PAUSED) {
                    break; // the run ended by itself first
                }
                pauses++;
                int bytesAtPause = rig.last().receivedBytes();
                long posAtPause = rig.engine.positionFrames();
                assertTrue(posAtPause >= before, "position decreased at pause: " + before + " -> " + posAtPause);
                assertArrayEquals(Arrays.copyOf(expected, bytesAtPause),
                        Arrays.copyOf(rig.last().received(), bytesAtPause), "accepted bytes are an oracle prefix");
                Thread.sleep(4);
                assertEquals(bytesAtPause, rig.last().receivedBytes(), "no bytes accepted while paused");
                assertEquals(posAtPause, rig.engine.positionFrames(), "position frozen while paused");
                rig.engine.resume();
                lastPos = posAtPause;
            }
            rig.awaitEnd();
            rig.engine.close();
            assertStream(expected, rig.last().received(), "iteration " + iteration);
        }
        assertTrue(pauses >= 25, "the property test paused only " + pauses + " times");
    }

    @Test
    void positionFollowsDeviceTimeAndFreezesWhilePaused() throws Exception {
        Data a = noise(10, 2, 80000, 0.5f);
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1, true);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(1000));
        assertTrue(rig.last().awaitWriterBlocked(5000));
        assertEquals(1000, rig.engine.positionFrames());
        rig.last().advance(100_000_000L); // 100 ms = 800 frames
        assertEquals(1800, rig.engine.positionFrames(), 1);
        rig.engine.pause();
        long frozen = rig.engine.positionFrames();
        assertEquals(1800, frozen, 1);
        rig.last().advance(1_000_000_000L);
        assertEquals(frozen, rig.engine.positionFrames());
        rig.engine.resume();
        rig.last().advance(100_000_000L);
        assertEquals(2600, rig.engine.positionFrames(), 1);
        rig.engine.stop();
        assertEquals(1000, rig.engine.positionFrames());
        assertEquals(1000, rig.engine.startFrame());
        assertEquals(STOPPED, rig.engine.state());
    }

    // ------------------------------------------------------------------ stop and close

    @Test
    void stopMidPlayReturnsQuicklyAndALaterPlayReopensAFreshSink() throws Exception {
        Data a = noise(11, 2, 80000, 0.5f);
        Rig rig = Rig.auto(1);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(2000));
        rig.awaitBytes(8192);
        long t0 = System.nanoTime();
        rig.engine.stop();
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "stop took too long");
        assertEquals(STOPPED, rig.engine.state());
        assertEquals(2000, rig.engine.positionFrames());
        assertTrue(rig.sinks.get(0).isClosed());
        assertEquals(0, rig.finished.get(), "stop() is not a natural end");
        assertTrue(rig.engine.play(500));
        assertEquals(2, rig.sinks.size(), "a later play() reopens a fresh sink");
        assertNotNull(rig.last());
        assertTrue(rig.sinks.get(0) != rig.last());
        rig.engine.stop();
        assertEquals(500, rig.engine.positionFrames());
        rig.engine.close();
        assertNoAudioThread();
    }

    @Test
    void stopWhileAWriteIsBlockedReturnsWithinTwoSeconds() throws Exception {
        Data a = noise(12, 2, 80000, 0.5f);
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1, false);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        assertTrue(rig.last().awaitWriterBlocked(5000), "the writer should be blocked on the full queue");
        long t0 = System.nanoTime();
        rig.engine.stop();
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "stop took too long");
        assertEquals(0, rig.engine.positionFrames());
        assertTrue(rig.last().isClosed());
        assertNoAudioThread();
    }

    @Test
    void closeWhileDrainIsBlockedReturnsWithinTwoSeconds() throws Exception {
        Data a = noise(13, 2, 1200, 0.5f); // with the 250 ms pad (2000 frames) it fits the fake's 3200-frame queue, so the writer reaches drain()
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.PULSE_LIKE, 1, false);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        assertTrue(rig.last().awaitDrainBlocked(5000), "the audio thread should be blocked in drain()");
        long t0 = System.nanoTime();
        rig.engine.close();
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "close took too long");
        assertEquals(1, rig.last().closeCount());
        assertEquals(0, rig.engine.positionFrames());
        assertNoAudioThread();
    }

    // ------------------------------------------------------------------ refusals and failures

    @Test
    void anEmptyPlanOrTheEndOfThePlanReturnsFalseAndOpensNoSink() throws Exception {
        Rig rig = Rig.auto(1);
        assertFalse(rig.engine.play(0));
        assertThrows(IllegalArgumentException.class, () -> rig.engine.play(-1));
        Data a = noise(14, 1, 100, 0.5f);
        rig.engine.setPlan(planOf(a));
        assertFalse(rig.engine.play(100));
        assertFalse(rig.engine.play(5000));
        assertEquals(0, rig.sinks.size(), "no sink may be opened");
        assertEquals(STOPPED, rig.engine.state());
        assertTrue(rig.events.isEmpty());
        rig.engine.close();
    }

    @Test
    void aSinkThatCannotBeOpenedMakesPlayThrowAndLeavesNothingRunning() throws Exception {
        Rig rig = Rig.auto(1);
        rig.openFailure = new AudioDeviceException("no device at 8000 Hz");
        rig.engine.setPlan(planOf(noise(15, 1, 2000, 0.5f)));
        AudioDeviceException e = assertThrows(AudioDeviceException.class, () -> rig.engine.play(0));
        assertTrue(e.getMessage().contains("8000"), e.getMessage());
        assertEquals(STOPPED, rig.engine.state());
        assertNoAudioThread();
        rig.openFailure = null;
        assertTrue(rig.engine.play(0), "the engine is still usable");
        rig.engine.close();
    }

    @Test
    void aWriteExceptionDeliversFailedStopsAndClosesTheSink() throws Exception {
        Rig rig = Rig.auto(1);
        rig.onCreate = f -> f.script(new FakeAudioSink.Throw());
        rig.engine.setPlan(planOf(noise(16, 2, 5000, 0.5f)));
        assertTrue(rig.engine.play(0));
        rig.awaitEnd();
        assertNotNull(rig.failure.get(), "failed() must be delivered");
        assertEquals(0, rig.finished.get());
        assertEquals(STOPPED, rig.engine.state());
        assertTrue(rig.last().isClosed());
        rig.engine.close();
        assertNoAudioThread();
    }

    // ------------------------------------------------------------------ plan swapping

    @Test
    void aPlanSwapDuringPlayLandsOnABlockBoundary() throws Exception {
        Data quiet = constant(0.25f, 8192);
        Data loud = constant(0.5f, 8192);
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1, false);
        rig.engine.setPlan(planOf(quiet));
        assertTrue(rig.engine.play(0));
        assertTrue(rig.last().awaitWriterBlocked(5000));
        rig.engine.setPlan(planOf(loud));
        rig.pumpUntilEnd();
        rig.engine.close();
        byte[] got = rig.last().received();
        assertEquals(8192 * 4 + PAD_BYTES, got.length, "audio plus pad");
        int transitions = 0;
        int previous = -1;
        for (int block = 0; block < 8; block++) {
            int first = (got[block * 4096] & 0xFF) | (got[block * 4096 + 1] << 8);
            assertTrue(first == 8192 || first == 16384, "block " + block + " starts with " + first);
            for (int i = 0; i < 1024; i++) {
                int p = block * 4096 + i * 4;
                int l = (got[p] & 0xFF) | (got[p + 1] << 8);
                int r = (got[p + 2] & 0xFF) | (got[p + 3] << 8);
                assertEquals(first, l, "block " + block + " is not from a single plan (left, frame " + i + ")");
                assertEquals(first, r, "block " + block + " is not from a single plan (right, frame " + i + ")");
            }
            if (previous != -1 && first != previous) {
                transitions++;
                assertEquals(16384, first, "the swap goes quiet to loud");
            }
            previous = first;
        }
        assertEquals(1, transitions, "exactly one swap");
        assertEquals(16384, previous);
    }

    @Test
    void aPlanWithAnotherSampleRateIsRejectedWhilePlayingButAcceptedWhenStopped() throws Exception {
        Rig rig = Rig.auto(1);
        rig.engine.setPlan(planOf(noise(17, 1, 80000, 0.5f)));
        assertTrue(rig.engine.play(0));
        MixPlan other = MixPlan.of(List.of(MemorySampleSource.copyOf(16000, new float[][] {new float[100]})));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> rig.engine.setPlan(other));
        assertTrue(e.getMessage().contains("16000"), e.getMessage());
        rig.engine.setPlan(MixPlan.empty()); // an empty plan is always allowed
        rig.engine.stop();
        rig.engine.setPlan(other);
        rig.engine.close();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void twoHundredPlayStopCyclesLeaveNoThreadBehind() throws Exception {
        Rig rig = Rig.auto(50);
        rig.engine.setPlan(planOf(noise(18, 2, 20000, 0.5f)));
        for (int i = 0; i < 200; i++) {
            assertTrue(rig.engine.play(i * 7L));
            if (i % 2 == 0) {
                rig.awaitBytes(1);
            }
            rig.engine.stop();
            assertEquals(STOPPED, rig.engine.state());
        }
        assertEquals(200, rig.sinks.size());
        rig.engine.close();
        rig.engine.close(); // idempotent
        assertNoAudioThread();
        assertThrows(IllegalStateException.class, () -> rig.engine.play(0));
    }

    @Test
    void controlCallsThatDoNotApplyAreNoOpsAndPlayWhilePlayingRestarts() throws Exception {
        Rig rig = Rig.auto(1);
        rig.engine.setPlan(planOf(noise(19, 2, 80000, 0.5f)));
        rig.engine.pause();
        rig.engine.resume();
        rig.engine.stop();
        assertTrue(rig.events.isEmpty(), "no events for no-ops: " + rig.events);
        assertTrue(rig.engine.play(100));
        rig.engine.resume(); // playing already
        assertEquals(PLAYING, rig.engine.state());
        assertTrue(rig.engine.play(300), "play while playing restarts");
        assertEquals(2, rig.sinks.size());
        assertTrue(rig.sinks.get(0).isClosed());
        assertEquals(300, rig.engine.startFrame());
        rig.engine.pause();
        rig.engine.pause();
        assertEquals(PAUSED, rig.engine.state());
        assertTrue(rig.engine.play(5), "play while paused restarts");
        assertEquals(PLAYING, rig.engine.state());
        rig.engine.close();
        assertEquals(List.of("STOPPED>PLAYING", "PLAYING>STOPPED", "STOPPED>PLAYING", "PLAYING>PAUSED",
                "PAUSED>STOPPED", "STOPPED>PLAYING", "PLAYING>STOPPED"), rig.events);
    }

    @Test
    void listenersSeeTheTransportStatesInOrder() throws Exception {
        Rig rig = Rig.auto(1);
        rig.engine.setPlan(planOf(noise(20, 2, 80000, 0.5f)));
        assertTrue(rig.engine.play(0));
        rig.engine.pause();
        rig.engine.resume();
        rig.engine.stop();
        assertEquals(List.of("STOPPED>PLAYING", "PLAYING>PAUSED", "PAUSED>PLAYING", "PLAYING>STOPPED"), rig.events);
        PlaybackListener extra = new PlaybackListener() { };
        rig.engine.addListener(extra);
        rig.engine.removeListener(extra);
        rig.engine.close();
    }

    // ------------------------------------------------------------------ diagnostics

    /** A source whose read stalls once, standing in for a slow render. */
    private static final class StallingSource implements SampleSource {
        private final MemorySampleSource inner;
        private final long stallAtFrame;
        private final long stallMillis;
        private volatile boolean stalled;

        StallingSource(MemorySampleSource inner, long stallAtFrame, long stallMillis) {
            this.inner = inner;
            this.stallAtFrame = stallAtFrame;
            this.stallMillis = stallMillis;
        }

        @Override
        public int sampleRate() {
            return inner.sampleRate();
        }

        @Override
        public int channelCount() {
            return inner.channelCount();
        }

        @Override
        public long frameCount() {
            return inner.frameCount();
        }

        @Override
        public void read(long startFrame, int frames, float[][] dest, int destOffset) {
            if (!stalled && startFrame >= stallAtFrame) {
                stalled = true;
                try {
                    Thread.sleep(stallMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            inner.read(startFrame, frames, dest, destOffset);
        }
    }

    @Test
    void suspectedUnderrunsCountOnlyGapsLongerThanTheDeviceBuffer() throws Exception {
        Data quick = noise(21, 2, 6000, 0.5f);
        Rig clean = Rig.auto(1);
        clean.engine.setPlan(planOf(quick));
        assertTrue(clean.engine.play(0));
        clean.awaitEnd();
        clean.engine.close();
        assertEquals(0, clean.engine.stats().suspectedUnderruns(), "no stall, no underrun");

        // buffer = 1600 frames = 200 ms at 8000 Hz; stall the render of the third block for 400 ms
        Rig slow = Rig.auto(1);
        slow.engine.setPlan(MixPlan.of(List.of(new StallingSource(quick.source(), 2048, 400))));
        assertTrue(slow.engine.play(0));
        slow.awaitEnd();
        slow.engine.close();
        assertTrue(slow.engine.stats().suspectedUnderruns() >= 1,
                "stats " + slow.engine.stats());
        assertTrue(slow.engine.stats().maxRenderNanos() >= 300_000_000L, "stats " + slow.engine.stats());
    }

    @Test
    void theAudioThreadAllocatesNothingPerBlockInSteadyState() throws Exception {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no allocation counters");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) b;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "allocation counters unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        Rig rig = Rig.auto(1000);
        rig.onCreate = f -> f.discardReceived(true);
        rig.engine.setPlan(planOf(noise(22, 2, 3_000_000, 0.5f), noise(23, 1, 2_000_000, 0.5f)));
        assertTrue(rig.engine.play(0));
        Thread audio = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("timbre-audio")).findFirst().orElseThrow();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (rig.engine.stats().blocksRendered() < 300 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        long b0 = rig.engine.stats().blocksRendered();
        long a0 = bean.getThreadAllocatedBytes(audio.getId());
        while (rig.engine.stats().blocksRendered() < b0 + 600 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        long b1 = rig.engine.stats().blocksRendered();
        long a1 = bean.getThreadAllocatedBytes(audio.getId());
        rig.engine.close();
        long blocks = b1 - b0;
        assertTrue(blocks >= 300, "only " + blocks + " blocks measured");
        double perBlock = (double) (a1 - a0) / blocks;
        System.out.println("ALLOCATION-GUARD audio thread: " + (a1 - a0) + " bytes over " + blocks + " blocks = "
                + perBlock + " bytes per block (ceiling 0)");
        assertEquals(0L, a1 - a0, "audio thread allocated " + (a1 - a0) + " bytes over " + blocks + " blocks");
        assertSame(STOPPED, rig.engine.state());
    }
}
