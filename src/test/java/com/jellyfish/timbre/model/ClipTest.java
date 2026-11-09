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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClipTest {

    private static MemorySampleSource src(int frames) {
        return MemorySampleSource.copyOf(8000, new float[1][frames]);
    }

    @Test
    void validatesEveryInvariant() {
        MemorySampleSource s = src(100);
        assertThrows(NullPointerException.class, () -> new Clip(null, 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, -1, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 0, -3, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 0, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 50, 51, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 101, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 0, 10, -1));
        assertThrows(IllegalArgumentException.class, () -> new Clip(s, 0, 10, Long.MAX_VALUE));
        new Clip(s, 90, 10, 0); // boundary is valid
        new Clip(s, 0, 100, 5);
    }

    @Test
    void timelineEndAndWithTimelineStart() {
        Clip c = new Clip(src(100), 10, 20, 5);
        assertEquals(25, c.timelineEnd());
        Clip moved = c.withTimelineStart(1000);
        assertEquals(1000, moved.timelineStart());
        assertEquals(1020, moved.timelineEnd());
        assertEquals(10, moved.sourceStart());
        assertEquals(20, moved.frameCount());
        assertSame(c.source(), moved.source());
        assertEquals(5, c.timelineStart());
        assertThrows(IllegalArgumentException.class, () -> c.withTimelineStart(-1));
    }

    @ParameterizedTest
    @ValueSource(longs = {101, 105, 119, 150, 199})
    void splitHalvesCoverTheOriginalExactly(long at) {
        Clip c = new Clip(src(1000), 300, 100, 100); // timeline [100, 200)
        List<Clip> halves = c.split(at);
        Clip left = halves.get(0);
        Clip right = halves.get(1);
        assertEquals(c.timelineStart(), left.timelineStart());
        assertEquals(at, left.timelineEnd());
        assertEquals(at, right.timelineStart());
        assertEquals(c.timelineEnd(), right.timelineEnd());
        assertEquals(c.sourceStart(), left.sourceStart());
        assertEquals(left.sourceStart() + left.frameCount(), right.sourceStart());
        assertEquals(c.sourceStart() + c.frameCount(), right.sourceStart() + right.frameCount());
        assertEquals(c.frameCount(), left.frameCount() + right.frameCount());
        assertSame(c.source(), left.source());
        assertSame(c.source(), right.source());
    }

    @ParameterizedTest
    @ValueSource(longs = {-5, 0, 99, 100, 200, 201, 5000})
    void splitOutsideTheClipIsRejected(long at) {
        Clip c = new Clip(src(1000), 0, 100, 100);
        assertThrows(IllegalArgumentException.class, () -> c.split(at));
    }
}
