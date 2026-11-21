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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.model.ChunkedSampleSource;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Opt-in test through the real default input. Runs only with {@code -Dtimbre.captureDevice=true}, which must
 * only be set after the user agreed to the microphone being opened (two recordings of about 2 s). Otherwise it
 * is skipped. Nothing is stored: the recordings live in memory for the duration of the test, and only frame
 * counts, timings, flags and level aggregates (peak and RMS in dBFS) are printed.
 */
@Timeout(60)
class RealCaptureTest {

    private static final int RATE = 44100;
    private static final long RECORD_NANOS = 2_000_000_000L;

    /** Delegates to the real source and counts close calls. */
    private static final class Counting implements CaptureSource {
        final CaptureSource real = new JavaSoundCaptureSource();
        final AtomicInteger closes = new AtomicInteger();

        @Override
        public void open(int sampleRate, int channels, int bufferFrames) throws AudioDeviceException {
            real.open(sampleRate, channels, bufferFrames);
        }

        @Override
        public void start() {
            real.start();
        }

        @Override
        public int read(byte[] dst, int offset, int length) {
            return real.read(dst, offset, length);
        }

        @Override
        public int availableBytes() {
            return real.availableBytes();
        }

        @Override
        public void stop() {
            real.stop();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            real.close();
        }

        @Override
        public int bufferFrames() {
            return real.bufferFrames();
        }

        @Override
        public int channels() {
            return real.channels();
        }

        @Override
        public int sampleRate() {
            return real.sampleRate();
        }
    }

    private static String db(double v) {
        return v <= 0 ? "-inf" : String.format(Locale.ROOT, "%.1f", 20 * Math.log10(v));
    }

    private static void recordOnce(Recorder rec, Counting[] last, int run) throws Exception {
        rec.start(RATE, 2);
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < RECORD_NANOS) {
            LockSupport.parkNanos(10_000_000L);
        }
        long tStop = System.nanoTime();
        RecordingResult r = rec.stopAndWait(500).orElseThrow();
        long stopMs = (System.nanoTime() - tStop) / 1_000_000L;
        double elapsed = r.framesRecorded() / (double) RATE;
        ChunkedSampleSource s = r.source();
        double peak = 0;
        double sumSq = 0;
        float[][] block = new float[2][4096];
        for (long f = 0; f < s.frameCount(); f += 4096) {
            int n = (int) Math.min(4096, s.frameCount() - f);
            s.read(f, n, block, 0);
            for (int c = 0; c < 2; c++) {
                for (int i = 0; i < n; i++) {
                    double v = Math.abs(block[c][i]);
                    peak = Math.max(peak, v);
                    sumSq += v * v;
                }
            }
        }
        double rms = Math.sqrt(sumSq / (2.0 * s.frameCount()));
        System.out.println(String.format(Locale.ROOT,
                "RealCapture run=%d frames=%d elapsed=%.3fs stopMs=%d reason=%s buffer=%d dropouts=%d lostFrames=%d"
                        + " peakDbfs=%s rmsDbfs=%s clipping=%b silent=%b closes=%d",
                run, r.framesRecorded(), elapsed, stopMs, r.reason(), last[0].bufferFrames(), r.suspectedDropouts(),
                r.estimatedLostFrames(), db(peak), db(rms), r.everClipped(), r.silentWarningRaised(),
                last[0].closes.get()));
        assertNotEquals(StopReason.ERROR, r.reason(), String.valueOf(r.errorMessage()));
        assertEquals(StopReason.USER, r.reason());
        assertTrue(elapsed >= 1.7 && elapsed <= 2.3, "elapsed " + elapsed + " s of frames read");
        assertTrue(stopMs < 500, "stopAndWait took " + stopMs + " ms");
        assertTrue(last[0].closes.get() >= 1, "the source was closed");
    }

    @Test
    void recordsAboutTwoSecondsTwiceFromTheDefaultInput() throws Exception {
        assumeTrue("true".equals(System.getProperty("timbre.captureDevice")),
                "real-capture test is opt-in: run with -Dtimbre.captureDevice=true after the user agreed");
        Counting[] last = new Counting[1];
        try (Recorder rec = new Recorder(() -> {
            last[0] = new Counting();
            return last[0];
        })) {
            recordOnce(rec, last, 1);
            Counting first = last[0];
            recordOnce(rec, last, 2);
            assertTrue(first != last[0], "the second recording used a fresh source");
        }
    }
}
