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
package com.jellyfish.timbre.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TrackSnapshotTest {

    /** Source whose channel 0 holds the frame index + base and channel 1 its negation. */
    private static MemorySampleSource indexSource(int frames, int base) {
        float[][] d = new float[2][frames];
        for (int i = 0; i < frames; i++) {
            d[0][i] = base + i;
            d[1][i] = -(base + i);
        }
        return MemorySampleSource.copyOf(8000, d);
    }

    private static float[][] render(SampleSource s, long start, int frames) {
        float[][] out = new float[s.channelCount()][frames];
        for (float[] ch : out) {
            java.util.Arrays.fill(ch, 777f); // poison: every sample must be overwritten
        }
        s.read(start, frames, out, 0);
        return out;
    }

    @Test
    void snapshotIsUnaffectedByLaterTrackChanges() {
        MemorySampleSource s = indexSource(100, 1);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(s, 0, 10, 0));
        TrackSnapshot snap = t.snapshot();
        t.addClip(new Clip(s, 0, 50, 100));
        assertEquals(10, snap.frameCount());
        assertEquals(150, t.snapshot().frameCount());
        assertEquals(8000, snap.sampleRate());
        assertEquals(2, snap.channelCount());
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(0, 11, new float[2][11], 0));
    }

    @Test
    void emptyTrackSnapshotHasNoFrames() {
        TrackSnapshot snap = new Track("t", 8000, 2).snapshot();
        assertEquals(0, snap.frameCount());
        snap.read(0, 0, new float[2][0], 0);
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(0, 1, new float[2][1], 0));
    }

    @Test
    void gapsRenderAsSilence() {
        MemorySampleSource s = indexSource(10, 1);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(s, 0, 10, 0));   // [0, 10): 1..10
        t.addClip(new Clip(s, 0, 10, 20));  // [20, 30): 1..10
        float[][] out = render(t.snapshot(), 0, 30);
        for (int i = 0; i < 30; i++) {
            float expect = i < 10 ? i + 1 : i >= 20 ? i - 20 + 1 : 0f;
            assertEquals(expect, out[0][i], "ch0 frame " + i);
            assertEquals(expect == 0f ? 0f : -expect, out[1][i], "ch1 frame " + i);
        }
    }

    @Test
    void readsAcrossClipBoundariesAndUsesTheSourceStartOffset() {
        MemorySampleSource a = indexSource(100, 1000);
        MemorySampleSource b = indexSource(100, 5000);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(a, 30, 10, 0));  // timeline [0,10) -> 1030..1039
        t.addClip(new Clip(b, 7, 10, 10));  // timeline [10,20) -> 5007..5016
        float[][] out = render(t.snapshot(), 5, 10);
        assertArrayEquals(new float[] {1035, 1036, 1037, 1038, 1039, 5007, 5008, 5009, 5010, 5011}, out[0]);
        assertArrayEquals(new float[] {-1035, -1036, -1037, -1038, -1039, -5007, -5008, -5009, -5010, -5011}, out[1]);
    }

    @Test
    void readStartingInsideAGapAndEndingInsideAGap() {
        MemorySampleSource s = indexSource(10, 1);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(s, 0, 10, 10));  // [10, 20)
        t.addClip(new Clip(s, 0, 10, 30));  // [30, 40)
        float[][] out = render(t.snapshot(), 5, 30); // frames 5..34
        for (int i = 0; i < 30; i++) {
            long frame = 5 + i;
            float expect = frame >= 10 && frame < 20 ? frame - 10 + 1 : frame >= 30 && frame < 40 ? frame - 30 + 1 : 0f;
            assertEquals(expect, out[0][i], "frame " + frame);
        }
        float[][] gapOnly = render(t.snapshot(), 22, 5);
        assertArrayEquals(new float[5], gapOnly[0]);
    }

    @Test
    void zeroFrameReadsAreLegalAndWriteNothing() {
        MemorySampleSource s = indexSource(10, 1);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(s, 0, 10, 5));
        TrackSnapshot snap = t.snapshot();
        float[][] out = {{9f}, {9f}};
        for (long at : new long[] {0, 3, 5, 10, 15}) {
            snap.read(at, 0, out, 0);
        }
        assertEquals(9f, out[0][0]);
    }

    @Test
    void outOfRangeAndBadDestinationAreRejected() {
        MemorySampleSource s = indexSource(10, 1);
        Track t = new Track("t", 8000, 2);
        t.addClip(new Clip(s, 0, 10, 5)); // frameCount 15
        TrackSnapshot snap = t.snapshot();
        float[][] out = new float[2][20];
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(-1, 1, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(0, 16, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(15, 1, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> snap.read(16, 0, out, 0));
        assertThrows(IllegalArgumentException.class, () -> snap.read(0, 5, new float[1][5], 0));
        assertThrows(IllegalArgumentException.class, () -> snap.read(0, 5, new float[2][4], 0));
        snap.read(15, 0, out, 0); // end of timeline, zero frames: legal
    }

    @Test
    void sourceSplitIntoManyClipsRendersIdenticallyToTheOriginal() {
        int n = 2000;
        MemorySampleSource source = indexSource(n, 1);
        float[][] original = render(source, 0, n);
        SplittableRandom rnd = new SplittableRandom(20261109L);
        for (int iteration = 0; iteration < 400; iteration++) {
            List<Clip> clips = new ArrayList<>(List.of(new Clip(source, 0, n, 0)));
            int cuts = 1 + rnd.nextInt(12);
            for (int k = 0; k < cuts; k++) {
                long at = 1 + rnd.nextLong(n - 1);
                for (int i = 0; i < clips.size(); i++) {
                    Clip c = clips.get(i);
                    if (at > c.timelineStart() && at < c.timelineEnd()) {
                        Clip.Split halves = c.split(at);
                        clips.set(i, halves.left());
                        clips.add(i + 1, halves.right());
                        break;
                    }
                }
            }
            Collections.shuffle(clips, new java.util.Random(iteration));
            Track t = new Track("t", 8000, 2);
            clips.forEach(t::addClip);
            TrackSnapshot snap = t.snapshot();
            assertEquals(n, snap.frameCount());
            assertArrayEquals(original, render(snap, 0, n), "iteration " + iteration);
            int start = rnd.nextInt(n);
            int len = rnd.nextInt(n - start + 1);
            float[][] part = render(snap, start, len);
            for (int c = 0; c < 2; c++) {
                assertArrayEquals(java.util.Arrays.copyOfRange(original[c], start, start + len), part[c],
                        "iteration " + iteration + " range " + start + "+" + len);
            }
        }
    }

    @Test
    void readIntoAnOffsetOfTheDestination() {
        MemorySampleSource s = indexSource(10, 1);
        Track t = Track.of("t", s);
        float[][] out = new float[2][14];
        t.snapshot().read(2, 4, out, 6);
        assertArrayEquals(new float[] {0, 0, 0, 0, 0, 0, 3, 4, 5, 6, 0, 0, 0, 0}, out[0]);
    }
}
