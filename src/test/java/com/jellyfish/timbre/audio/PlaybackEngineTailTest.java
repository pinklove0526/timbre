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

import static com.jellyfish.timbre.audio.PlaybackEngineTest.RATE;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.STOPPED;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.assertNoAudioThread;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.noise;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.oracle;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.planOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.PlaybackEngineTest.Data;
import com.jellyfish.timbre.audio.PlaybackEngineTest.Rig;
import com.jellyfish.timbre.audio.support.FakeAudioSink;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tail handling (Milestone 2 hardening): pause and resume during the final drain, with a fake sink that
 * reproduces the measured quirks (drain returns when stopped; a restart with a shallow queue does not run).
 */
@Timeout(120)
class PlaybackEngineTailTest {

    /** Device buffer at 8000 Hz: 200 ms. */
    private static final int BUFFER_FRAMES = 1600;

    private static Rig rig(FakeAudioSink.Pace pace, double speed) {
        return new Rig(pace, FakeAudioSink.Artifacts.PULSE_LIKE, speed, true,
                EnumSet.of(FakeAudioSink.Quirk.DRAIN_RETURNS_WHEN_STOPPED, FakeAudioSink.Quirk.RESTART_NEEDS_DEPTH));
    }

    /** The received stream is the oracle stream, optionally followed by silence (never anything else). */
    private static void assertDelivered(byte[] expected, byte[] actual, String message) {
        assertTrue(actual.length >= expected.length, message + ": only " + actual.length + " of " + expected.length
                + " bytes delivered");
        assertArrayEquals(expected, Arrays.copyOf(actual, expected.length), message + ": audio bytes");
        for (int i = expected.length; i < actual.length; i++) {
            if (actual[i] != 0) {
                throw new AssertionError(message + ": non-zero byte after the end of the project at " + i);
            }
        }
    }

    /** Waits until the audio thread is blocked in drain() or in a write to a full queue. */
    private static void awaitTailBlocked(FakeAudioSink f) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!(f.awaitDrainBlocked(1) || f.awaitWriterBlocked(1))) {
            assertTrue(System.nanoTime() < deadline, "the audio thread never blocked at the end");
        }
    }

    /** All real audio was played by the device (the unplayed rest, if any, is only the silence pad). */
    private static void assertRealAudioPlayed(Rig rig, byte[] expected, String message) {
        assertTrue(rig.last().consumedFrames() >= expected.length / 4,
                message + ": only " + rig.last().consumedFrames() + " of " + expected.length / 4
                        + " real frames were played by the device");
    }

    /** Pumps device time until the run ends, then checks that all real audio was played. */
    private static void finishAndCheck(Rig rig, byte[] expected, String message) throws Exception {
        rig.pumpUntilEnd(20_000_000L);
        rig.engine.close();
        assertDelivered(expected, rig.last().received(), message);
        assertRealAudioPlayed(rig, expected, message);
        assertEquals(1, rig.finished.get(), message + ": finished() exactly once");
        assertEquals(STOPPED, rig.engine.state());
    }

    @Test
    void aPauseDuringTheFinalDrainKeepsPausedAndTheTailIsDeliveredAfterResume() throws Exception {
        Data a = noise(31, 2, 1200, 0.5f);
        Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        awaitTailBlocked(rig.last());
        rig.engine.pause();
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state());
        Thread.sleep(30);
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state(), "still paused");
        assertEquals(0, rig.finished.get(), "finished() must not fire while paused");
        assertFalse(rig.last().isClosed(), "the sink must stay open while paused");
        rig.engine.resume();
        finishAndCheck(rig, oracle(0, a), "pause during drain");
    }

    @Test
    void aPauseDuringTheDrainOfADeepQueueKeepsPausedAndFinishesComplete() throws Exception {
        Data a = noise(38, 2, 3000, 0.5f); // 375 ms queued: deep enough to restart even without a pad
        Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        awaitTailBlocked(rig.last());
        rig.engine.pause();
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state());
        Thread.sleep(30);
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state());
        assertEquals(0, rig.finished.get());
        assertFalse(rig.last().isClosed());
        rig.engine.resume();
        finishAndCheck(rig, oracle(0, a), "pause during the drain of a deep queue");
    }

    @Test
    void aPauseImmediatelyFollowedByAResumeDuringTheDrainDoesNotEndTheRunEarly() throws Exception {
        int early = 0;
        // 3000 frames: deep enough that a restart works even without a pad, so only the race can end the run early;
        // 1200 frames: with the pad the whole tail still fits the fake's queue, so the audio thread sits in drain()
        for (int frames : new int[] {3000, 1200}) {
            Data a = noise(32, 2, frames, 0.5f);
            byte[] expected = oracle(0, a);
            for (int i = 0; i < 100; i++) {
                Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
                rig.engine.setPlan(planOf(a));
                assertTrue(rig.engine.play(0));
                awaitTailBlocked(rig.last());
                rig.engine.pause();
                rig.engine.resume();
                Thread.sleep(2); // the device clock is manual: a legitimate end is impossible without advancing it
                if (rig.finished.get() != 0 || rig.last().isClosed()) {
                    early++;
                    rig.engine.close();
                    continue;
                }
                finishAndCheck(rig, expected, frames + " frames, iteration " + i);
            }
        }
        assertEquals(0, early, "the run ended early (tail cut off) in " + early + " of 200 pause+resume pairs");
    }

    @Test
    void resumingWithAShallowQueueRestartsTheLineAndThePlaybackFinishesComplete() throws Exception {
        Data a = noise(33, 2, 2400, 0.5f); // 300 ms
        Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        awaitTailBlocked(rig.last());
        rig.last().advance(200_000_000L); // 1600 frames played, 800 frames (100 ms) of real audio left
        rig.engine.pause();
        rig.last().advance(300_000_000L);
        rig.engine.resume();
        finishAndCheck(rig, oracle(0, a), "shallow resume");
    }

    @Test
    void randomPausesAndResumesInTheLast400MillisecondsAlwaysDeliverTheFullStream() throws Exception {
        Data a = noise(34, 2, 6000, 0.5f); // 750 ms
        byte[] expected = oracle(0, a);
        int pausesDone = 0;
        for (int iteration = 0; iteration < 60; iteration++) {
            Random rnd = new Random(5000 + iteration);
            Rig rig = rig(FakeAudioSink.Pace.AUTO, 20);
            rig.engine.setPlan(planOf(a));
            assertTrue(rig.engine.play(0));
            long deadline = System.nanoTime() + 5_000_000_000L;
            long startAt = 6000 - (200 + rnd.nextInt(3000)); // somewhere in the last 400 ms, down to the last 25 ms
            while (rig.engine.positionFrames() < startAt && rig.engine.state() != STOPPED) {
                assertTrue(System.nanoTime() < deadline, "never reached the last 400 ms");
                Thread.sleep(1);
            }
            long lastPos = rig.engine.positionFrames();
            int cycles = 1 + rnd.nextInt(4);
            for (int c = 0; c < cycles && rig.engine.state() != STOPPED; c++) {
                Thread.sleep(rnd.nextInt(4));
                rig.engine.pause();
                if (rig.engine.state() == PlaybackEngine.State.PAUSED) {
                    pausesDone++;
                    long frozen = rig.engine.positionFrames();
                    assertTrue(frozen >= lastPos, "position decreased: " + lastPos + " -> " + frozen);
                    Thread.sleep(rnd.nextInt(3));
                    assertEquals(0, rig.finished.get(), "finished() while paused");
                    rig.engine.resume();
                    lastPos = frozen;
                }
            }
            rig.awaitEnd();
            rig.engine.close();
            assertDelivered(expected, rig.last().received(), "iteration " + iteration);
            assertRealAudioPlayed(rig, expected, "iteration " + iteration);
            assertEquals(1, rig.finished.get(), "iteration " + iteration + ": finished() exactly once");
        }
        assertTrue(pausesDone >= 60, "only " + pausesDone + " pauses landed in the tail");
    }

    @Test
    void stopDuringAPausedDrainReturnsQuicklyAndDoesNotFinish() throws Exception {
        Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
        rig.engine.setPlan(planOf(noise(35, 2, 1200, 0.5f)));
        assertTrue(rig.engine.play(0));
        awaitTailBlocked(rig.last());
        rig.engine.pause();
        Thread.sleep(30); // the run must still be alive and paused, not already ended by the drain returning
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state(), "paused drain");
        assertEquals(0, rig.finished.get(), "finished() while paused");
        long t0 = System.nanoTime();
        rig.engine.stop();
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "stop took too long");
        assertEquals(STOPPED, rig.engine.state());
        assertEquals(0, rig.engine.positionFrames());
        assertTrue(rig.last().isClosed());
        assertEquals(0, rig.finished.get(), "stop() is not a natural end");
        rig.engine.close();
        assertNoAudioThread();
    }

    @Test
    void closeDuringAPausedDrainReturnsWithinTwoSeconds() throws Exception {
        Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
        rig.engine.setPlan(planOf(noise(36, 2, 1200, 0.5f)));
        assertTrue(rig.engine.play(0));
        awaitTailBlocked(rig.last());
        rig.engine.pause();
        Thread.sleep(30);
        assertEquals(PlaybackEngine.State.PAUSED, rig.engine.state(), "paused drain");
        assertEquals(0, rig.finished.get(), "finished() while paused");
        long t0 = System.nanoTime();
        rig.engine.close();
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "close took too long");
        assertEquals(1, rig.last().closeCount());
        assertEquals(0, rig.finished.get());
        assertNoAudioThread();
    }

    @Test
    void theSilencePadAfterTheEndHasTheDocumentedSizeAndIsOnlyZeros() throws Exception {
        Data a = noise(37, 2, 3000, 0.5f);
        Rig rig = new Rig(FakeAudioSink.Pace.AUTO, FakeAudioSink.Artifacts.NONE, 200, false);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0));
        rig.awaitEnd();
        rig.engine.close();
        int padFrames = (int) Math.ceil(1.25 * BUFFER_FRAMES); // 250 ms: 1.25 device buffers
        byte[] expected = oracle(0, a);
        byte[] got = rig.last().received();
        assertEquals(expected.length + padFrames * 4, got.length, "real audio plus a pad of 1.25 buffers");
        assertArrayEquals(expected, Arrays.copyOf(got, expected.length));
        for (int i = expected.length; i < got.length; i++) {
            assertEquals(0, got[i], "pad byte " + (i - expected.length));
        }
        assertEquals(RATE, 8000);
    }
}
