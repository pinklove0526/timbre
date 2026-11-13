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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Track;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Known-answer and property tests for {@link StereoMixer}. Expected values are written in the test. */
class StereoMixerTest {

    private static MemorySampleSource src(int rate, float[]... channels) {
        return MemorySampleSource.copyOf(rate, channels);
    }

    private static float[][] mix(MixPlan plan, long start, int frames) {
        StereoMixer m = new StereoMixer(plan, 16);
        float[] l = new float[16];
        float[] r = new float[16];
        java.util.Arrays.fill(l, 9f);
        java.util.Arrays.fill(r, 9f);
        m.mix(start, frames, l, r);
        return new float[][] {l, r};
    }

    @Test
    void monoIsDuplicatedToBothChannelsAtFullLevel() {
        float[][] out = mix(MixPlan.of(List.of(src(8000, new float[] {0.25f, -0.5f, 0.75f}))), 0, 3);
        assertArrayEquals(new float[] {0.25f, -0.5f, 0.75f, 9f}, java.util.Arrays.copyOf(out[0], 4), 0f);
        assertArrayEquals(new float[] {0.25f, -0.5f, 0.75f, 9f}, java.util.Arrays.copyOf(out[1], 4), 0f);
    }

    @Test
    void stereoPassesThroughWithoutCrossing() {
        float[][] out = mix(MixPlan.of(List.of(src(8000, new float[] {0.125f, 0.25f, 0.375f},
                new float[] {-0.5f, -0.625f, -0.75f}))), 0, 3);
        assertArrayEquals(new float[] {0.125f, 0.25f, 0.375f}, java.util.Arrays.copyOf(out[0], 3), 0f);
        assertArrayEquals(new float[] {-0.5f, -0.625f, -0.75f}, java.util.Arrays.copyOf(out[1], 3), 0f);
    }

    @Test
    void threeAndSixChannelSourcesPlayChannelsOneAndTwoOnly() {
        float[][] out3 = mix(MixPlan.of(List.of(src(8000, new float[] {0.0625f, 0.125f}, new float[] {0.25f, 0.5f},
                new float[] {0.75f, 0.875f}))), 0, 2);
        assertArrayEquals(new float[] {0.0625f, 0.125f}, java.util.Arrays.copyOf(out3[0], 2), 0f);
        assertArrayEquals(new float[] {0.25f, 0.5f}, java.util.Arrays.copyOf(out3[1], 2), 0f);
        float[][] six = new float[6][2];
        for (int c = 0; c < 6; c++) {
            six[c][0] = (c + 1) / 16f;
            six[c][1] = -(c + 1) / 16f;
        }
        float[][] out6 = mix(MixPlan.of(List.of(src(8000, six))), 0, 2);
        assertArrayEquals(new float[] {1 / 16f, -1 / 16f}, java.util.Arrays.copyOf(out6[0], 2), 0f);
        assertArrayEquals(new float[] {2 / 16f, -2 / 16f}, java.util.Arrays.copyOf(out6[1], 2), 0f);
    }

    @Test
    void twoAndThreeTracksSumAtUnityGain() {
        MemorySampleSource a = src(8000, new float[] {0.125f, 0.25f}, new float[] {0.5f, -0.25f});
        MemorySampleSource b = src(8000, new float[] {0.0625f, -0.5f});
        MemorySampleSource c = src(8000, new float[] {0.25f, 0.25f}, new float[] {0.125f, 0.0625f});
        float[][] two = mix(MixPlan.of(List.of(a, b)), 0, 2);
        assertArrayEquals(new float[] {0.125f + 0.0625f, 0.25f - 0.5f}, java.util.Arrays.copyOf(two[0], 2), 0f);
        assertArrayEquals(new float[] {0.5f + 0.0625f, -0.25f - 0.5f}, java.util.Arrays.copyOf(two[1], 2), 0f);
        float[][] three = mix(MixPlan.of(List.of(a, b, c)), 0, 2);
        assertArrayEquals(new float[] {0.125f + 0.0625f + 0.25f, 0.25f - 0.5f + 0.25f},
                java.util.Arrays.copyOf(three[0], 2), 0f);
        assertArrayEquals(new float[] {0.5f + 0.0625f + 0.125f, -0.25f - 0.5f + 0.0625f},
                java.util.Arrays.copyOf(three[1], 2), 0f);
    }

    @Test
    void differentLengthsGiveZerosAfterTheShorterSourceEnds() {
        MemorySampleSource longer = src(8000, new float[] {0.5f, 0.5f, 0.5f, 0.5f, 0.5f});
        MemorySampleSource shorter = src(8000, new float[] {0.25f, 0.25f, 0.25f});
        MixPlan plan = MixPlan.of(List.of(longer, shorter));
        assertEquals(5, plan.length());
        float[][] out = mix(plan, 0, 8);
        assertArrayEquals(new float[] {0.75f, 0.75f, 0.75f, 0.5f, 0.5f, 0f, 0f, 0f},
                java.util.Arrays.copyOf(out[0], 8), 0f);
    }

    @Test
    void startFrameBeyondSomeSourcesMixesTheRest() {
        MemorySampleSource longer = src(8000, new float[] {0f, 0f, 0f, 0f, 0.5f});
        MemorySampleSource shorter = src(8000, new float[] {0.25f, 0.25f, 0.25f});
        float[][] out = mix(MixPlan.of(List.of(longer, shorter)), 4, 4);
        assertArrayEquals(new float[] {0.5f, 0f, 0f, 0f}, java.util.Arrays.copyOf(out[0], 4), 0f);
        float[][] beyond = mix(MixPlan.of(List.of(longer, shorter)), 100, 4);
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f}, java.util.Arrays.copyOf(beyond[0], 4), 0f);
    }

    @Test
    void aGapInsideATrackIsSilence() {
        MemorySampleSource s = src(8000, new float[] {0.5f, 0.5f, 0.5f, 0.5f});
        Track t = new Track("t", 8000, 1);
        t.addClip(new Clip(s, 0, 2, 0));
        t.addClip(new Clip(s, 2, 2, 5)); // timeline frames 2..4 are a gap
        float[][] out = mix(MixPlan.of(List.of(t.snapshot())), 0, 7);
        assertArrayEquals(new float[] {0.5f, 0.5f, 0f, 0f, 0f, 0.5f, 0.5f}, java.util.Arrays.copyOf(out[0], 7), 0f);
    }

    @Test
    void readsAtBlockBoundaryOffsetsAreExact() {
        int n = 5000;
        float[] ramp = new float[n];
        for (int i = 0; i < n; i++) {
            ramp[i] = i / 8192f; // exactly representable
        }
        MixPlan plan = MixPlan.of(List.of(src(8000, ramp)));
        StereoMixer m = new StereoMixer(plan, 1024);
        float[] l = new float[1024];
        float[] r = new float[1024];
        for (long start : new long[] {1023, 1024, 1025, 2048, 4095, 4096}) {
            int frames = (int) Math.min(1024, n - start);
            m.mix(start, frames, l, r);
            for (int i = 0; i < frames; i++) {
                assertEquals((start + i) / 8192f, l[i], 0f, "left at start " + start + " + " + i);
                assertEquals((start + i) / 8192f, r[i], 0f, "right at start " + start + " + " + i);
            }
        }
    }

    @Test
    void emptyPlanMixesSilence() {
        MixPlan plan = MixPlan.empty();
        assertEquals(0, plan.length());
        float[][] out = mix(plan, 0, 4);
        assertArrayEquals(new float[] {0f, 0f, 0f, 0f, 9f}, java.util.Arrays.copyOf(out[0], 5), 0f);
    }

    @Test
    void mismatchedSampleRatesAreRejectedNamingBothRates() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MixPlan.of(List.of(src(44100, new float[] {0f}), src(48000, new float[] {0f}))));
        assertTrue(e.getMessage().contains("44100") && e.getMessage().contains("48000"), e.getMessage());
    }

    @Test
    void argumentsAreValidated() {
        StereoMixer m = new StereoMixer(MixPlan.empty(), 8);
        float[] a = new float[8];
        assertThrows(IllegalArgumentException.class, () -> m.mix(-1, 1, a, a));
        assertThrows(IllegalArgumentException.class, () -> m.mix(0, 9, a, a));
        assertThrows(IllegalArgumentException.class, () -> m.mix(0, 4, new float[2], a));
        assertThrows(IllegalArgumentException.class, () -> new StereoMixer(MixPlan.empty(), 0));
    }

    @Test
    void mixDoesNotAllocateAfterWarmUp() {
        java.lang.management.ThreadMXBean b = ManagementFactory.getThreadMXBean();
        assumeTrue(b instanceof com.sun.management.ThreadMXBean, "no allocation counters");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) b;
        assumeTrue(bean.isThreadAllocatedMemorySupported(), "allocation counters unsupported");
        bean.setThreadAllocatedMemoryEnabled(true);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(src(8000, new float[4096], new float[4096]), 0, 2048, 0));
        t.addClip(new Clip(src(8000, new float[4096], new float[4096]), 0, 1024, 3000));
        List<SampleSource> sources = List.of(t.snapshot(), src(8000, new float[4096]),
                src(8000, new float[4096], new float[4096], new float[4096]));
        StereoMixer m = new StereoMixer(MixPlan.of(sources), 1024);
        float[] l = new float[1024];
        float[] r = new float[1024];
        for (int i = 0; i < 20_000; i++) {
            m.mix(i % 3000, 1024, l, r);
        }
        long tid = Thread.currentThread().getId();
        long overheadA = bean.getThreadAllocatedBytes(tid);
        long overheadB = bean.getThreadAllocatedBytes(tid);
        long before = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 20_000; i++) {
            m.mix(i % 3000, 1024, l, r);
        }
        long allocated = bean.getThreadAllocatedBytes(tid) - before - (overheadB - overheadA);
        System.out.println("ALLOCATION-GUARD StereoMixer.mix: " + allocated + " bytes over 20000 mixes");
        assertEquals(0, allocated, "StereoMixer.mix allocated " + allocated + " bytes over 20000 calls");
    }
}
