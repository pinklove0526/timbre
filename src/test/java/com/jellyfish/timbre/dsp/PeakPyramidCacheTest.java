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
package com.jellyfish.timbre.dsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PeakPyramidCacheTest {

    /** Counts builds (a build is the only caller that reads from frame 0 in 16384-frame chunks). */
    private static class CountingSource implements SampleSource {
        final MemorySampleSource inner = MemorySampleSource.adopt(44100, new float[][] {new float[5000]});
        final AtomicInteger builds = new AtomicInteger();

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
            if (startFrame == 0) {
                builds.incrementAndGet();
            }
            inner.read(startFrame, frames, dest, destOffset);
        }
    }

    private static boolean peakThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().equals("timbre-peaks"));
    }

    @Test
    void buildCompletesAndCallbackRunsOnceOnThePeakThread() throws Exception {
        try (PeakPyramidCache cache = new PeakPyramidCache()) {
            CountingSource src = new CountingSource();
            assertTrue(cache.getIfReady(src).isEmpty());
            CountDownLatch done = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> threadName = new AtomicReference<>();
            cache.request(src, () -> {
                calls.incrementAndGet();
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("timbre-peaks", threadName.get());
            assertTrue(cache.getIfReady(src).isPresent());
            assertEquals(5000, cache.getIfReady(src).get().frameCount());
            Thread.sleep(50);
            assertEquals(1, calls.get());
            assertEquals(1, src.builds.get());
            assertTrue(cache.lastError().isEmpty());
        }
    }

    @Test
    void secondRequestReusesThePyramidWithoutRebuild() throws Exception {
        try (PeakPyramidCache cache = new PeakPyramidCache()) {
            CountingSource src = new CountingSource();
            CountDownLatch first = new CountDownLatch(1);
            cache.request(src, first::countDown);
            assertTrue(first.await(10, TimeUnit.SECONDS));
            PeakPyramid p = cache.getIfReady(src).orElseThrow();

            AtomicReference<Thread> ran = new AtomicReference<>();
            cache.request(src, () -> ran.set(Thread.currentThread()));
            assertSame(Thread.currentThread(), ran.get(), "an already-built pyramid calls back immediately");
            assertSame(p, cache.getIfReady(src).orElseThrow());
            assertEquals(1, src.builds.get());
        }
    }

    @Test
    void twoRequestersBothGetCallbacksFromOneBuild() throws Exception {
        try (PeakPyramidCache cache = new PeakPyramidCache()) {
            CountDownLatch gate = new CountDownLatch(1);
            CountingSource src = new CountingSource() {
                @Override
                public void read(long startFrame, int frames, float[][] dest, int destOffset) {
                    try {
                        gate.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    super.read(startFrame, frames, dest, destOffset);
                }
            };
            CountDownLatch both = new CountDownLatch(2);
            AtomicInteger calls = new AtomicInteger();
            cache.request(src, () -> {
                calls.incrementAndGet();
                both.countDown();
            });
            cache.request(src, () -> {
                calls.incrementAndGet();
                both.countDown();
            });
            assertTrue(cache.getIfReady(src).isEmpty(), "build is blocked, not ready");
            gate.countDown();
            assertTrue(both.await(10, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals(1, src.builds.get());
        }
    }

    @Test
    void failingBuildIsRecordedAndCallbackSkipped() throws Exception {
        try (PeakPyramidCache cache = new PeakPyramidCache()) {
            SampleSource bad = new SampleSource() {
                @Override
                public int sampleRate() {
                    return 44100;
                }

                @Override
                public int channelCount() {
                    return 1;
                }

                @Override
                public long frameCount() {
                    return 1000;
                }

                @Override
                public void read(long startFrame, int frames, float[][] dest, int destOffset) {
                    throw new IllegalStateException("disk gone");
                }
            };
            AtomicInteger calls = new AtomicInteger();
            cache.request(bad, calls::incrementAndGet);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (cache.lastError().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(cache.lastError().isPresent());
            assertEquals("disk gone", cache.lastError().get().getMessage());
            Thread.sleep(50);
            assertEquals(0, calls.get());
            assertTrue(cache.getIfReady(bad).isEmpty());

            // the thread survives a failure and serves the next source
            CountDownLatch ok = new CountDownLatch(1);
            cache.request(new CountingSource(), ok::countDown);
            assertTrue(ok.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeIsIdempotentAndLeavesNoThread() throws Exception {
        PeakPyramidCache cache = new PeakPyramidCache();
        CountDownLatch done = new CountDownLatch(1);
        cache.request(new CountingSource(), done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(peakThreadAlive());
        cache.close();
        cache.close();
        assertFalse(peakThreadAlive());
        assertThrows(IllegalStateException.class, () -> cache.request(new CountingSource(), () -> { }));
        new PeakPyramidCache().close(); // never started: closing is fine too
    }

    @Test
    void closeAbandonsAnUnfinishedBuild() throws Exception {
        PeakPyramidCache cache = new PeakPyramidCache();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        CountingSource src = new CountingSource() {
            @Override
            public void read(long startFrame, int frames, float[][] dest, int destOffset) {
                started.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.read(startFrame, frames, dest, destOffset);
            }
        };
        cache.request(src, calls::incrementAndGet);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        cache.close(); // interrupts the blocked read, which then finishes; the result is discarded
        assertFalse(peakThreadAlive());
        assertEquals(0, calls.get(), "no callback after close");
    }

    @Test
    void cacheDoesNotKeepASourceReachable() throws Exception {
        try (PeakPyramidCache cache = new PeakPyramidCache()) {
            WeakReference<SampleSource> ref = buildOne(cache);
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (ref.get() != null && System.nanoTime() < deadline) {
                System.gc();
                Thread.sleep(20);
            }
            assertTrue(ref.get() == null, "the source stayed reachable: the cache holds it strongly");
            // a new request after collection still works (stale entries are purged)
            CountDownLatch ok = new CountDownLatch(1);
            cache.request(new CountingSource(), ok::countDown);
            assertTrue(ok.await(10, TimeUnit.SECONDS));
        }
    }

    private static WeakReference<SampleSource> buildOne(PeakPyramidCache cache) throws InterruptedException {
        CountingSource src = new CountingSource();
        CountDownLatch done = new CountDownLatch(1);
        cache.request(src, done::countDown);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(cache.getIfReady(src).orElse(null));
        return new WeakReference<>(src);
    }
}
