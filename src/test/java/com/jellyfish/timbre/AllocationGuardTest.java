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
package com.jellyfish.timbre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Track;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/** Proves that steady-state reads allocate nothing on the calling thread. */
class AllocationGuardTest {

    private static final int WARMUP = 30_000;
    private static final int MEASURED = 20_000;

    private static com.sun.management.ThreadMXBean bean() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no allocation counters on this JVM");
        com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) b;
        assumeTrue(sun.isThreadAllocatedMemorySupported(), "thread allocation counters unsupported");
        sun.setThreadAllocatedMemoryEnabled(true);
        return sun;
    }

    private static void assertNoAllocation(String what, SampleSource source, long start, int frames) {
        com.sun.management.ThreadMXBean bean = bean();
        long tid = Thread.currentThread().getId();
        float[][] dest = new float[source.channelCount()][frames + 8];
        for (int i = 0; i < WARMUP; i++) {
            source.read(start, frames, dest, 4);
        }
        long overheadA = bean.getThreadAllocatedBytes(tid);
        long overheadB = bean.getThreadAllocatedBytes(tid);
        long overhead = overheadB - overheadA;
        long before = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURED; i++) {
            source.read(start, frames, dest, 4);
        }
        long after = bean.getThreadAllocatedBytes(tid);
        long allocated = after - before - overhead;
        System.out.println("ALLOCATION-GUARD " + what + ": " + (after - before) + " bytes over " + MEASURED
                + " reads (measurement overhead " + overhead + ")");
        assertEquals(0, allocated, what + " allocated " + allocated + " bytes over " + MEASURED + " reads");
    }

    @Test
    void memorySampleSourceReadDoesNotAllocate() {
        float[][] data = new float[2][4096];
        MemorySampleSource s = MemorySampleSource.copyOf(44100, data);
        assertNoAllocation("MemorySampleSource.read", s, 100, 1024);
    }

    @Test
    void trackSnapshotReadDoesNotAllocate() {
        MemorySampleSource s = MemorySampleSource.copyOf(44100, new float[2][4096]);
        Track t = new Track("t", 44100, 2);
        t.addClip(new Clip(s, 0, 1000, 0));
        t.addClip(new Clip(s, 0, 1000, 1500)); // gap in between
        t.addClip(new Clip(s, 100, 1000, 2500));
        assertNoAllocation("TrackSnapshot.read", t.snapshot(), 500, 2500);
    }
}
