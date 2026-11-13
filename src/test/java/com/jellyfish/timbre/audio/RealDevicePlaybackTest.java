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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opt-in test through the real default output. Runs only with {@code -Dtimbre.audioDevice=true}, which must
 * only be set after the user agreed to a few seconds of quiet tone (about -24 dBFS, 20 ms fades, about 2 s
 * in total per test class run of about 4.5 s). Otherwise it is skipped.
 */
@Timeout(60)
class RealDevicePlaybackTest {

    private static final int RATE = 44100;

    private static MixPlan oneSecondTone() {
        return oneSecondTone(RATE);
    }

    private static MixPlan oneSecondTone(int rate) {
        float[] tone = new float[rate];
        int fade = rate * 20 / 1000;
        double amp = Math.pow(10.0, -24.0 / 20.0);
        for (int i = 0; i < tone.length; i++) {
            double env = i < fade ? (double) i / fade : i >= tone.length - fade ? (double) (tone.length - 1 - i) / fade
                    : 1.0;
            tone[i] = (float) (Math.sin(2 * Math.PI * 440.0 * i / rate) * amp * env);
        }
        return MixPlan.of(List.of(MemorySampleSource.copyOf(rate, new float[][] {tone})));
    }

    private static boolean audioThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("timbre-audio") && t.isAlive());
    }

    @Test
    void playsAPausesAndStopsCleanlyThroughTheRealDevice() throws Exception {
        assumeTrue("true".equals(System.getProperty("timbre.audioDevice")),
                "real-device test is opt-in: run with -Dtimbre.audioDevice=true after agreeing to a few quiet tones");
        PlaybackEngine engine = new PlaybackEngine();
        CountDownLatch finished = new CountDownLatch(1);
        engine.addListener(new PlaybackListener() {
            @Override
            public void finished() {
                finished.countDown();
            }
        });
        engine.setPlan(oneSecondTone());

        long t0 = System.nanoTime();
        assertTrue(engine.play(0), "play must start");
        assertEquals(PlaybackEngine.State.PLAYING, engine.state());
        assertTrue(System.nanoTime() - t0 < 500_000_000L, "PLAYING within 500 ms");

        // first 400 ms: monotonic, never ahead of wall time by more than 30 ms
        long last = 0;
        long worstAheadFrames = Long.MIN_VALUE;
        while (System.nanoTime() - t0 < 400_000_000L) {
            long pos = engine.positionFrames();
            long elapsedFrames = (long) ((System.nanoTime() - t0) * 1e-9 * RATE);
            assertTrue(pos >= last, "position decreased: " + last + " -> " + pos);
            worstAheadFrames = Math.max(worstAheadFrames, pos - elapsedFrames);
            last = pos;
            Thread.sleep(10);
        }
        System.out.printf("REAL-DEVICE first 400 ms: position at most %.1f ms ahead of wall time%n",
                worstAheadFrames * 1000.0 / RATE);
        assertTrue(worstAheadFrames <= 30L * RATE / 1000, "position ahead of wall time by "
                + worstAheadFrames * 1000.0 / RATE + " ms");

        engine.pause();
        assertEquals(PlaybackEngine.State.PAUSED, engine.state());
        long frozen = engine.positionFrames();
        Thread.sleep(300);
        assertEquals(frozen, engine.positionFrames(), "position frozen while paused");
        engine.resume();
        assertEquals(PlaybackEngine.State.PLAYING, engine.state());

        assertTrue(finished.await(1000 + 300 + 1000, TimeUnit.MILLISECONDS), "finished() within duration + 1 s");
        assertEquals(PlaybackEngine.State.STOPPED, engine.state());
        assertEquals(0, engine.positionFrames(), "cursor returns to the start frame");

        for (int i = 0; i < 2; i++) { // two mid-play stops
            assertTrue(engine.play(0));
            Thread.sleep(200);
            engine.stop();
            assertEquals(PlaybackEngine.State.STOPPED, engine.state());
            assertEquals(0, engine.positionFrames());
        }
        engine.close();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (audioThreadAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse(audioThreadAlive(), "no audio thread may be left");
    }

    private static PlaybackEngine engineWithFinishLatch(CountDownLatch[] latch) {
        PlaybackEngine engine = new PlaybackEngine();
        engine.addListener(new PlaybackListener() {
            @Override
            public void finished() {
                latch[0].countDown();
            }
        });
        return engine;
    }

    @Test
    void aPauseJustBeforeTheEndStillPlaysTheTailAndFinishes() throws Exception {
        assumeTrue("true".equals(System.getProperty("timbre.audioDevice")),
                "real-device test is opt-in: run with -Dtimbre.audioDevice=true after agreeing to a few quiet tones");
        CountDownLatch[] latch = {new CountDownLatch(1)};
        PlaybackEngine engine = engineWithFinishLatch(latch);
        engine.setPlan(oneSecondTone());
        assertTrue(engine.play(0));
        long deadline = System.nanoTime() + 5_000_000_000L;
        long target = RATE - RATE / 4; // 250 ms before the end
        while (engine.positionFrames() < target) {
            assertTrue(System.nanoTime() < deadline, "position never reached 750 ms");
            Thread.sleep(2);
        }
        engine.pause();
        assertEquals(PlaybackEngine.State.PAUSED, engine.state());
        Thread.sleep(300);
        assertEquals(1, latch[0].getCount(), "finished() must not arrive while paused");
        long resumedAt = System.nanoTime();
        engine.resume();
        assertTrue(latch[0].await(3, TimeUnit.SECONDS), "finished() must arrive after the resume");
        System.out.printf("REAL-DEVICE tail pause: finished %.0f ms after resume%n",
                (System.nanoTime() - resumedAt) / 1e6);
        assertEquals(PlaybackEngine.State.STOPPED, engine.state());
        assertEquals(0, engine.positionFrames(), "cursor returns to the start frame");
        engine.close();
    }

    @Test
    void oneEnginePlaysA48kHzProjectAndThenA44kHzProject() throws Exception {
        assumeTrue("true".equals(System.getProperty("timbre.audioDevice")),
                "real-device test is opt-in: run with -Dtimbre.audioDevice=true after agreeing to a few quiet tones");
        CountDownLatch[] latch = {new CountDownLatch(1)};
        PlaybackEngine engine = engineWithFinishLatch(latch);
        for (int rate : new int[] {48000, 44100}) {
            latch[0] = new CountDownLatch(1);
            engine.setPlan(oneSecondTone(rate));
            long t0 = System.nanoTime();
            assertTrue(engine.play(0), "play at " + rate + " Hz");
            assertTrue(latch[0].await(4, TimeUnit.SECONDS), "finished() at " + rate + " Hz");
            double seconds = (System.nanoTime() - t0) / 1e9;
            System.out.printf("REAL-DEVICE %d Hz: played to the end in %.2f s%n", rate, seconds);
            assertTrue(seconds >= 0.95, "finished too early at " + rate + " Hz: " + seconds + " s");
            assertEquals(PlaybackEngine.State.STOPPED, engine.state());
            assertEquals(0, engine.positionFrames());
        }
        engine.close();
    }
}
