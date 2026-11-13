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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.DeviceTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Replays the recorded Phase A device traces through {@link PlaybackClock} and compares the estimate with
 * elapsed playing time (the wall-clock truth). Thresholds, stated up front: in the first window after each
 * start or resume the estimate is never ahead of elapsed time; in steady state p99 |estimate - elapsed| is
 * at most 50 ms, the maximum at most 70 ms, and the estimate is never more than 30 ms ahead; across pause
 * and resume it never jumps by more than 30 ms; it is always monotonic.
 *
 * <p>One refinement found by the replay: because the estimate never decreases, after a resume it holds the
 * frozen value, which can be ahead of elapsed time by the steady-state error at the moment of the pause
 * (6.25 ms in the recorded trace). The resume window is therefore checked against
 * {@code max(elapsed time, frozen value)}: the estimate must never advance faster than elapsed time, but
 * may hold. The start window (no pause) is checked strictly against elapsed time.
 */
class PlaybackClockReplayTest {

    private static final double P99_MS = 50;
    private static final double MAX_MS = 70;
    private static final double AHEAD_MS = 30;
    private static final double JUMP_MS = 30;

    /** Results of one replay. */
    private record Result(String name, double windowMaxAheadMs, double p99Ms, double maxMs, double maxAheadMs,
                          double maxJumpMs, boolean monotonic, int steadyRows) {
    }

    private static Result replay(String name, int rate, boolean withPause) {
        DeviceTrace tr = DeviceTrace.load(name);
        int buffer = rate / 5;
        PlaybackClock clock = new PlaybackClock(rate, buffer);
        double windowMs = clock.windowNanos() / 1e6;
        double t0 = tr.event(withPause ? "first-write" : "start-call");
        double stopAt = withPause ? tr.event("stop-call") : Double.NaN;
        double startAt = withPause ? tr.event("start-call") : Double.NaN;
        // for the pause trace the first start is the first write
        clock.onStart((long) (t0 * 1e6));
        double segStart = t0;
        double accumulated = 0;
        double frozenMs = 0;
        double holdMax = 0;
        boolean paused = false;
        boolean resumed = false;
        double endMs = tr.event("write-end");
        long prevEst = 0;
        double prevT = t0;
        boolean prevPlaying = true;
        double windowMax = Double.NEGATIVE_INFINITY;
        double maxJump = 0;
        boolean monotonic = true;
        List<Double> steady = new ArrayList<>();
        double maxAhead = Double.NEGATIVE_INFINITY;
        for (DeviceTrace.Row r : tr.rows()) {
            double t = r.tMs();
            if (t < t0 || t > endMs) {
                continue;
            }
            if (withPause && !paused && !resumed && t >= stopAt) {
                clock.onPause((long) (stopAt * 1e6));
                accumulated += stopAt - segStart;
                frozenMs = prevEst * 1000.0 / rate;
                paused = true;
            }
            if (withPause && paused && t >= startAt) {
                clock.onResume((long) (startAt * 1e6));
                segStart = startAt;
                paused = false;
                resumed = true;
            }
            long est = clock.estimate((long) (t * 1e6), r.pos(), r.written());
            double elapsedMs = paused ? accumulated : accumulated + (t - segStart);
            double diffMs = (est - elapsedMs * rate / 1000.0) * 1000.0 / rate;
            if (est < prevEst) {
                monotonic = false;
            }
            double expectedProgress = prevPlaying && !paused ? (t - prevT) : 0;
            double jumpMs = (est - prevEst) * 1000.0 / rate - expectedProgress;
            if (withPause && (Math.abs(t - stopAt) < 60 || Math.abs(t - startAt) < 60)) {
                maxJump = Math.max(maxJump, Math.abs(jumpMs));
            }
            if (!paused && t - segStart < windowMs) {
                double ref = resumed ? Math.max(elapsedMs, frozenMs) : elapsedMs;
                if (resumed) {
                    holdMax = Math.max(holdMax, frozenMs - elapsedMs);
                }
                windowMax = Math.max(windowMax, (est - ref * rate / 1000.0) * 1000.0 / rate);
            } else if (!paused) {
                steady.add(Math.abs(diffMs));
                maxAhead = Math.max(maxAhead, diffMs);
            }
            prevEst = est;
            prevT = t;
            prevPlaying = !paused;
        }
        Collections.sort(steady);
        double p99 = steady.get((int) Math.floor(0.99 * (steady.size() - 1)));
        double max = steady.get(steady.size() - 1);
        Result res = new Result(name, windowMax, p99, max, maxAhead, maxJump, monotonic, steady.size());
        System.out.printf("CLOCK-REPLAY %s: hold of frozen value ahead of elapsed after resume %.2f ms; window %.0f ms"
                        + " (max ahead of the reference in window %.2f ms); steady rows %d:"
                        + " p99 %.1f ms, max %.1f ms, max ahead %.1f ms; max jump near pause/resume %.1f ms;"
                        + " monotonic %b%n", name, holdMax, windowMs, res.windowMaxAheadMs, res.steadyRows, res.p99Ms,
                res.maxMs, res.maxAheadMs, res.maxJumpMs, res.monotonic);
        return res;
    }

    private static void check(Result r) {
        assertTrue(r.monotonic, r.name + " not monotonic");
        assertTrue(r.windowMaxAheadMs <= 0.05, r.name + " estimate ahead of elapsed in the window by "
                + r.windowMaxAheadMs + " ms");
        assertTrue(r.p99Ms <= P99_MS, r.name + " p99 " + r.p99Ms + " ms");
        assertTrue(r.maxMs <= MAX_MS, r.name + " max " + r.maxMs + " ms");
        assertTrue(r.maxAheadMs <= AHEAD_MS, r.name + " ahead by " + r.maxAheadMs + " ms");
        assertTrue(r.maxJumpMs <= JUMP_MS, r.name + " jump " + r.maxJumpMs + " ms");
    }

    @Test
    void continuousPlaybackAt44100() {
        check(replay("clock-44100", 44100, false));
    }

    @Test
    void continuousPlaybackAt48000() {
        check(replay("clock-48000", 48000, false));
    }

    @Test
    void continuousPlaybackAt96000() {
        check(replay("clock-96000", 96000, false));
    }

    @Test
    void pauseAndResumeTraceAt44100() {
        check(replay("pause", 44100, true));
    }
}
