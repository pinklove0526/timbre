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

import java.util.List;
import org.junit.jupiter.api.Test;

class TrackTest {

    private static MemorySampleSource src(int rate, int channels, int frames) {
        return MemorySampleSource.copyOf(rate, new float[channels][frames]);
    }

    @Test
    void constructorValidates() {
        assertThrows(NullPointerException.class, () -> new Track(null, 8000, 1));
        assertThrows(IllegalArgumentException.class, () -> new Track("t", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Track("t", 8000, 0));
        Track t = new Track("lead", 44100, 2);
        assertEquals("lead", t.name());
        assertEquals(44100, t.sampleRate());
        assertEquals(2, t.channelCount());
        assertEquals(0, t.snapshot().frameCount());
    }

    @Test
    void ofCreatesOneClipAtZero() {
        MemorySampleSource s = src(8000, 2, 50);
        Track t = Track.of("t", s);
        assertEquals(1, t.clips().size());
        Clip c = t.clips().get(0);
        assertEquals(0, c.timelineStart());
        assertEquals(50, c.frameCount());
        assertEquals(0, c.sourceStart());
        assertEquals(2, t.channelCount());
    }

    @Test
    void ofRejectsEmptySource() {
        assertThrows(IllegalArgumentException.class, () -> Track.of("t", src(8000, 1, 0)));
    }

    @Test
    void addClipRejectsRateAndChannelMismatch() {
        Track t = new Track("t", 8000, 1);
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(src(44100, 1, 10), 0, 10, 0)));
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(src(8000, 2, 10), 0, 10, 0)));
        assertEquals(0, t.clips().size());
    }

    @Test
    void addClipRejectsOverlap() {
        MemorySampleSource s = src(8000, 1, 100);
        Track t = new Track("t", 8000, 1);
        t.addClip(new Clip(s, 0, 10, 10)); // [10, 20)
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(s, 0, 10, 15))); // overlaps tail
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(s, 0, 10, 5)));  // overlaps head
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(s, 0, 2, 12)));  // inside
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(s, 0, 30, 0)));  // contains
        assertThrows(IllegalArgumentException.class, () -> t.addClip(new Clip(s, 0, 10, 10))); // identical
        assertEquals(1, t.clips().size());
    }

    @Test
    void addClipAllowsAdjacentClipsAndKeepsThemSorted() {
        MemorySampleSource s = src(8000, 1, 100);
        Track t = new Track("t", 8000, 1);
        t.addClip(new Clip(s, 0, 10, 40));
        t.addClip(new Clip(s, 0, 10, 0));
        t.addClip(new Clip(s, 0, 10, 10)); // adjacent to the previous one
        t.addClip(new Clip(s, 0, 10, 20));
        List<Clip> clips = t.clips();
        assertEquals(List.of(0L, 10L, 20L, 40L), clips.stream().map(Clip::timelineStart).toList());
    }

    @Test
    void clipsReturnsAnUnmodifiableCopy() {
        MemorySampleSource s = src(8000, 1, 100);
        Track t = new Track("t", 8000, 1);
        t.addClip(new Clip(s, 0, 10, 0));
        List<Clip> copy = t.clips();
        assertThrows(UnsupportedOperationException.class, () -> copy.add(new Clip(s, 0, 10, 50)));
        assertThrows(UnsupportedOperationException.class, copy::clear);
        t.addClip(new Clip(s, 0, 10, 50));
        assertEquals(1, copy.size()); // a copy, not a live view
        assertEquals(2, t.clips().size());
    }
}
