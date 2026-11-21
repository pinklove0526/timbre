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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrackReplaceClipsTest {

    private static MemorySampleSource mono(int rate, int frames) {
        return MemorySampleSource.copyOf(rate, new float[][] {new float[frames]});
    }

    private static Track trackWithOneClip(MemorySampleSource src) {
        Track t = new Track("t", 44100, 1);
        t.addClip(new Clip(src, 0, 10, 0));
        return t;
    }

    @Test
    void validReplaceTakesTheNewListAndCopiesIt() {
        MemorySampleSource src = mono(44100, 100);
        Track t = trackWithOneClip(src);
        List<Clip> fresh = new ArrayList<>(List.of(new Clip(src, 5, 7, 3), new Clip(src, 40, 13, 10),
                new Clip(src, 60, 2, 23)));
        t.replaceClips(fresh);
        fresh.clear(); // the track must hold its own copy
        assertEquals(List.of(new Clip(src, 5, 7, 3), new Clip(src, 40, 13, 10), new Clip(src, 60, 2, 23)),
                t.clips());
        t.replaceClips(List.of());
        assertEquals(List.of(), t.clips());
    }

    @Test
    void touchingClipsAreAllowed() {
        MemorySampleSource src = mono(44100, 100);
        Track t = trackWithOneClip(src);
        t.replaceClips(List.of(new Clip(src, 0, 4, 0), new Clip(src, 4, 6, 4)));
        assertEquals(2, t.clips().size());
    }

    @Test
    void everyRejectionLeavesTheTrackUnchanged() {
        MemorySampleSource src = mono(44100, 100);
        Track t = trackWithOneClip(src);
        List<Clip> before = t.clips();
        // unsorted
        assertThrows(IllegalArgumentException.class,
                () -> t.replaceClips(List.of(new Clip(src, 0, 3, 20), new Clip(src, 0, 3, 5))));
        // overlap by one frame
        assertThrows(IllegalArgumentException.class,
                () -> t.replaceClips(List.of(new Clip(src, 0, 6, 0), new Clip(src, 0, 3, 5))));
        // wrong channel count
        MemorySampleSource stereo = MemorySampleSource.copyOf(44100,
                new float[][] {new float[50], new float[50]});
        assertThrows(IllegalArgumentException.class,
                () -> t.replaceClips(List.of(new Clip(src, 0, 3, 0), new Clip(stereo, 0, 3, 10))));
        // wrong sample rate
        MemorySampleSource other = mono(48000, 50);
        assertThrows(IllegalArgumentException.class,
                () -> t.replaceClips(List.of(new Clip(other, 0, 3, 0))));
        // a null element
        assertThrows(NullPointerException.class,
                () -> t.replaceClips(Arrays.asList(new Clip(src, 0, 3, 0), null)));
        assertThrows(NullPointerException.class, () -> t.replaceClips(null));
        // zero length and negative start cannot even be built as clips
        assertThrows(IllegalArgumentException.class, () -> new Clip(src, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(src, 0, 3, -1));
        assertEquals(before, t.clips());
    }

    @Test
    void aSnapshotTakenBeforeReplaceIsNotChanged() {
        float[] data = new float[20];
        for (int i = 0; i < 20; i++) {
            data[i] = 100 + i;
        }
        MemorySampleSource src = MemorySampleSource.copyOf(44100, new float[][] {data});
        Track t = new Track("t", 44100, 1);
        t.addClip(new Clip(src, 0, 20, 0));
        TrackSnapshot snap = t.snapshot();
        t.replaceClips(List.of(new Clip(src, 15, 5, 2)));
        float[][] out = new float[1][20];
        snap.read(0, 20, out, 0);
        assertEquals(20, snap.frameCount());
        assertEquals(100f, out[0][0]);
        assertEquals(119f, out[0][19]);
        assertEquals(7, t.snapshot().frameCount());
    }
}
