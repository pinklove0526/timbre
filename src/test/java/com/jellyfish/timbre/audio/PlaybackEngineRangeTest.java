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

import static com.jellyfish.timbre.audio.PlaybackEngineTest.PAD_BYTES;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.STOPPED;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.assertNoAudioThread;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.assertStream;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.noise;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.oracle;
import static com.jellyfish.timbre.audio.PlaybackEngineTest.planOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.PlaybackEngineTest.Data;
import com.jellyfish.timbre.audio.PlaybackEngineTest.Rig;
import com.jellyfish.timbre.audio.support.FakeAudioSink;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Range playback ({@code play(from, to)}, Milestone 3 Phase A) with the fake sink. */
@Timeout(120)
class PlaybackEngineRangeTest {

    private static Rig rig(FakeAudioSink.Pace pace, double speed) {
        return new Rig(pace, FakeAudioSink.Artifacts.PULSE_LIKE, speed, true,
                EnumSet.of(FakeAudioSink.Quirk.DRAIN_RETURNS_WHEN_STOPPED, FakeAudioSink.Quirk.RESTART_NEEDS_DEPTH));
    }

    /** Independent oracle for {@code [from, to)}: the whole-project oracle cut to the range. */
    private static byte[] rangeOracle(long from, long to, Data... data) {
        byte[] all = oracle(from, data);
        return Arrays.copyOf(all, (int) Math.min(all.length, (to - from) * 4));
    }

    @Test
    void aRangeDeliversExactlyTheOracleBytesOfTheRangeThenThePad() throws Exception {
        Data a = noise(61, 2, 5000, 0.9f);
        Data b = noise(62, 1, 3500, 0.5f);
        long[][] ranges = {{1000, 3000}, {0, 1}, {4999, 5000}, {123, 4097}, {0, 5000}, {2048, 3072}, {3500, 4000}};
        for (long[] r : ranges) {
            Rig rig = Rig.auto(200);
            rig.engine.setPlan(planOf(a, b));
            assertTrue(rig.engine.play(r[0], r[1]), "range " + Arrays.toString(r));
            assertEquals(r[0], rig.engine.startFrame());
            assertEquals(r[1], rig.engine.endFrame());
            rig.awaitEnd();
            rig.engine.close();
            assertStream(rangeOracle(r[0], r[1], a, b), rig.last().received(), "range " + Arrays.toString(r));
            assertEquals(1, rig.finished.get(), "finished() exactly once");
            assertEquals(r[0], rig.engine.positionFrames(), "the cursor returns to the start frame");
            assertEquals(STOPPED, rig.engine.state());
            assertEquals(List.of("STOPPED>PLAYING", "PLAYING>STOPPED"), rig.events);
            assertEquals(1, rig.last().closeCount());
        }
    }

    @Test
    void anEndBeyondThePlanLengthIsClamped() throws Exception {
        Data a = noise(63, 2, 5000, 0.9f);
        for (long to : new long[] {5001, 1_000_000, Long.MAX_VALUE}) {
            Rig rig = Rig.auto(200);
            rig.engine.setPlan(planOf(a));
            assertTrue(rig.engine.play(2000, to));
            assertEquals(5000, rig.engine.endFrame(), "end clamped to the plan length for to=" + to);
            rig.awaitEnd();
            rig.engine.close();
            assertStream(oracle(2000, a), rig.last().received(), "to=" + to);
        }
    }

    @Test
    void playFromOnlyStillMeansUpToTheEndOfThePlan() throws Exception {
        Data a = noise(64, 2, 4000, 0.9f);
        Rig rig = Rig.auto(200);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(1500));
        assertEquals(4000, rig.engine.endFrame());
        rig.awaitEnd();
        rig.engine.close();
        assertStream(oracle(1500, a), rig.last().received(), "play(from)");
    }

    @Test
    void anEmptyRangeReturnsFalseStaysStoppedAndOpensNoSink() throws Exception {
        Data a = noise(65, 2, 5000, 0.9f);
        Rig rig = Rig.auto(200);
        rig.engine.setPlan(planOf(a));
        assertFalse(rig.engine.play(100, 100));
        assertFalse(rig.engine.play(500, 100));
        assertFalse(rig.engine.play(0, 0));
        assertFalse(rig.engine.play(5000, 6000), "start at the end");
        assertFalse(rig.engine.play(7000, 9000), "start after the end");
        assertFalse(rig.engine.play(0, Long.MIN_VALUE));
        assertEquals(0, rig.sinks.size(), "no sink may be created");
        assertEquals(STOPPED, rig.engine.state());
        assertEquals(0, rig.engine.endFrame());
        assertThrows(IllegalArgumentException.class, () -> rig.engine.play(-1, 100));
        Rig emptyPlan = Rig.auto(200);
        assertFalse(emptyPlan.engine.play(0, 100));
        assertEquals(0, emptyPlan.sinks.size());
        rig.engine.close();
        emptyPlan.engine.close();
        assertNoAudioThread();
    }

    @Test
    void anEmptyRangeWhilePlayingStopsTheCurrentRunFirst() throws Exception {
        Data a = noise(66, 2, 40000, 0.9f);
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1, true);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0, 40000));
        assertEquals(PlaybackEngine.State.PLAYING, rig.engine.state());
        assertFalse(rig.engine.play(10, 10));
        assertEquals(STOPPED, rig.engine.state(), "play() always stops the previous run");
        rig.engine.close();
    }

    @Test
    void thePositionNeverLeavesTheRangeInPlayingPausedOrPad() throws Exception {
        Data a = noise(67, 2, 8000, 0.6f);
        long from = 1000;
        long to = 3000; // 250 ms of audio, then a 250 ms pad
        for (boolean withPause : new boolean[] {false, true}) {
            Rig rig = rig(FakeAudioSink.Pace.MANUAL, 1);
            rig.engine.setPlan(planOf(a));
            assertTrue(rig.engine.play(from, to));
            long last = from;
            long max = from;
            boolean paused = false;
            boolean resumed = false;
            long deadline = System.nanoTime() + 20_000_000_000L;
            int step = 0;
            while (!rig.ended.await(1, TimeUnit.MILLISECONDS)) {
                assertTrue(System.nanoTime() < deadline, "did not finish; events=" + rig.events);
                rig.last().advance(5_000_000L); // 5 ms of device time per step
                long pos = rig.engine.positionFrames();
                assertTrue(pos >= from && pos <= to, "position " + pos + " outside [" + from + ", " + to + "] at step "
                        + step + " state " + rig.engine.state());
                assertTrue(pos >= last || rig.engine.state() == STOPPED, "position went backwards: " + last + " -> "
                        + pos);
                last = pos;
                max = Math.max(max, pos);
                step++;
                if (withPause && !paused && pos >= to - 200) {
                    rig.engine.pause();
                    paused = true;
                    for (int i = 0; i < 20; i++) {
                        rig.last().advance(5_000_000L);
                        long frozen = rig.engine.positionFrames();
                        assertTrue(frozen <= to && frozen >= from, "paused position " + frozen);
                    }
                    rig.engine.resume();
                    resumed = true;
                }
            }
            rig.engine.close();
            assertEquals(1, rig.finished.get());
            assertTrue(max >= to - 400, "the poll never got near the end, max=" + max);
            assertEquals(withPause, paused && resumed);
            assertEquals(from, rig.engine.positionFrames());
            assertTrue(step > 60, "the pad should keep the run going for many steps, steps=" + step);
        }
    }

    @Test
    void pauseAndResumeAtRandomPointsInsideARangeDeliverTheWholeRangeOnce() throws Exception {
        Data a = noise(68, 2, 9000, 0.5f);
        long from = 1500;
        long to = 5500;
        byte[] expected = rangeOracle(from, to, a);
        int pausesDone = 0;
        for (int iteration = 0; iteration < 50; iteration++) {
            Random rnd = new Random(7000 + iteration);
            Rig rig = rig(FakeAudioSink.Pace.AUTO, 20);
            rig.engine.setPlan(planOf(a));
            assertTrue(rig.engine.play(from, to));
            long lastPos = from;
            int cycles = 1 + rnd.nextInt(4);
            for (int c = 0; c < cycles && rig.engine.state() != STOPPED; c++) {
                Thread.sleep(rnd.nextInt(12));
                rig.engine.pause();
                if (rig.engine.state() == PlaybackEngine.State.PAUSED) {
                    pausesDone++;
                    long frozen = rig.engine.positionFrames();
                    assertTrue(frozen >= lastPos && frozen <= to, "paused position " + frozen + " after " + lastPos);
                    Thread.sleep(rnd.nextInt(3));
                    assertEquals(0, rig.finished.get(), "finished() while paused");
                    rig.engine.resume();
                    lastPos = frozen;
                }
                long now = rig.engine.positionFrames();
                assertTrue(now <= to, "position " + now + " beyond the range end " + to);
            }
            rig.awaitEnd();
            rig.engine.close();
            byte[] got = rig.last().received();
            assertTrue(got.length >= expected.length, "iteration " + iteration + ": only " + got.length + " bytes");
            assertArrayEquals(expected, Arrays.copyOf(got, expected.length), "iteration " + iteration);
            for (int i = expected.length; i < got.length; i++) {
                assertEquals(0, got[i], "iteration " + iteration + ": non-zero pad byte");
            }
            assertTrue(got.length <= expected.length + PAD_BYTES, "more than the pad after the range");
            assertTrue(rig.last().consumedFrames() >= expected.length / 4, "real audio was not all played");
            assertEquals(1, rig.finished.get(), "iteration " + iteration + ": finished() exactly once");
            assertEquals(from, rig.engine.positionFrames());
        }
        assertTrue(pausesDone >= 50, "only " + pausesDone + " pauses landed");
    }

    @Test
    void aSecondPlayReplacesTheFirstRangeAndTheOldSinkIsClosed() throws Exception {
        Data a = noise(69, 2, 6000, 0.9f);
        Rig rig = new Rig(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE, 1, true);
        rig.engine.setPlan(planOf(a));
        assertTrue(rig.engine.play(0, 6000));
        FakeAudioSink first = rig.last();
        assertTrue(rig.engine.play(2000, 3000));
        assertTrue(first.isClosed());
        assertEquals(2000, rig.engine.startFrame());
        assertEquals(3000, rig.engine.endFrame());
        rig.engine.close();
        assertNoAudioThread();
    }
}
