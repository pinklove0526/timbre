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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.DeviceTrace;
import com.jellyfish.timbre.audio.support.FakeAudioSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Validates the PULSE_LIKE position model of {@link FakeAudioSink} against the recorded Phase A traces: the
 * fake is driven with the trace's own start, stop and write history (virtual time, zeros as audio) and its
 * reported position must stay within 40 ms of the recorded device position.
 */
@Timeout(30)
class FakeAudioSinkValidationTest {

    private static final double TOLERANCE_MS = 40.0;

    private static double replay(String name, int rate, boolean pauseTrace) throws Exception {
        DeviceTrace tr = DeviceTrace.load(name);
        int buffer = rate / 5;
        FakeAudioSink fake = new FakeAudioSink(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.PULSE_LIKE);
        fake.open(rate, buffer);
        double t0 = tr.event(pauseTrace ? "first-write" : "start-call");
        double stopAt = pauseTrace ? tr.event("stop-call") : Double.POSITIVE_INFINITY;
        double startAt = pauseTrace ? tr.event("start-call") : Double.POSITIVE_INFINITY;
        double end = tr.event("write-end");
        fake.start();
        boolean stopped = false;
        boolean restarted = false;
        double prevT = t0;
        long fakeWritten = 0;
        double maxDiffMs = 0;
        int compared = 0;
        for (DeviceTrace.Row r : tr.rows()) {
            double t = r.tMs();
            if (t < t0 || t > end) {
                continue;
            }
            if (!stopped && t >= stopAt) {
                fake.advance((long) ((stopAt - prevT) * 1e6));
                fake.stop();
                stopped = true;
                prevT = stopAt;
            }
            if (stopped && !restarted && t >= startAt) {
                fake.advance((long) ((startAt - prevT) * 1e6));
                fake.start();
                restarted = true;
                prevT = startAt;
            }
            fake.advance((long) ((t - prevT) * 1e6));
            prevT = t;
            long target = r.written();
            long free = 2L * buffer - fake.queuedFrames();
            long delta = Math.min(target - fakeWritten, free);
            if (delta > 0) {
                fake.write(new byte[(int) delta * 4], 0, (int) delta * 4);
                fakeWritten += delta;
            }
            double diffMs = (fake.positionFrames() - r.pos()) * 1000.0 / rate;
            maxDiffMs = Math.max(maxDiffMs, Math.abs(diffMs));
            compared++;
        }
        System.out.printf("FAKE-VALIDATION %s: %d rows compared, max |fake - recorded| = %.1f ms (tolerance %.0f ms)%n",
                name, compared, maxDiffMs, TOLERANCE_MS);
        assertTrue(compared > 400, "too few rows compared: " + compared);
        return maxDiffMs;
    }

    @Test
    void continuousPlaybackMatchesTheRecordedPositionAt44100() throws Exception {
        assertTrue(replay("clock-44100", 44100, false) <= TOLERANCE_MS);
    }

    @Test
    void continuousPlaybackMatchesTheRecordedPositionAt48000() throws Exception {
        assertTrue(replay("clock-48000", 48000, false) <= TOLERANCE_MS);
    }

    @Test
    void stopAndRestartReproduceTheRecordedLeadAndPlateau() throws Exception {
        assertTrue(replay("pause", 44100, true) <= TOLERANCE_MS);
    }

    @Test
    void stopLiftsThePositionByOneBufferAndRestartHoldsItFlat() throws Exception {
        FakeAudioSink fake = new FakeAudioSink(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.PULSE_LIKE);
        fake.open(44100, 8820);
        fake.start();
        fake.write(new byte[8820 * 4], 0, 8820 * 4);
        for (int i = 0; i < 8; i++) { // 800 ms of device time with a steady queue
            fake.advance(100_000_000L);
            fake.write(new byte[4410 * 4], 0, 4410 * 4);
        }
        long consumed = fake.consumedFrames();
        assertEquals(35280, consumed);
        long beforeStop = fake.positionFrames();
        assertTrue(beforeStop <= consumed + 8820 && beforeStop >= 35280 - 882, "before stop " + beforeStop);
        fake.stop();
        assertEquals(44100, fake.positionFrames(), "jump of one buffer at stop (Phase A: 35596 -> 44100)");
        fake.advance(500_000_000L);
        assertEquals(44100, fake.positionFrames(), "frozen while stopped");
        fake.start();
        fake.write(new byte[4410 * 4], 0, 4410 * 4);
        fake.advance(100_000_000L);
        assertEquals(44100, fake.positionFrames(), "flat after restart until true playback catches up");
        fake.advance(150_000_000L);
        assertTrue(fake.positionFrames() > 44100, "moves again after about one buffer");
    }

    @Test
    void writeBlocksWhenFullAndStopOrCloseUnblocksIt() throws Exception {
        for (boolean useClose : new boolean[] {false, true}) {
            FakeAudioSink fake = new FakeAudioSink(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.NONE);
            fake.open(44100, 1000);
            fake.start();
            int[] result = new int[1];
            Thread w = new Thread(() -> result[0] = fake.write(new byte[5000 * 4], 0, 5000 * 4));
            w.start();
            assertTrue(fake.awaitWriterBlocked(5000), "writer should block on a full queue");
            long t0 = System.nanoTime();
            if (useClose) {
                fake.close();
            } else {
                fake.stop();
            }
            w.join(2000);
            assertTrue(!w.isAlive(), "write must return after " + (useClose ? "close" : "stop"));
            assertEquals(1000 * 4, result[0], "only the queue capacity was accepted");
            assertTrue(System.nanoTime() - t0 < 2_000_000_000L);
        }
    }
}
