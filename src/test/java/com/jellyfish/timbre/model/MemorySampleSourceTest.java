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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MemorySampleSourceTest {

    @Test
    void validatesArguments() {
        float[][] one = {{1f, 2f}};
        assertThrows(IllegalArgumentException.class, () -> MemorySampleSource.copyOf(0, one));
        assertThrows(IllegalArgumentException.class, () -> MemorySampleSource.copyOf(-5, one));
        assertThrows(IllegalArgumentException.class, () -> MemorySampleSource.copyOf(8000, new float[0][]));
        assertThrows(IllegalArgumentException.class,
                () -> MemorySampleSource.copyOf(8000, new float[][] {{1f}, {1f, 2f}}));
        assertThrows(NullPointerException.class, () -> MemorySampleSource.copyOf(8000, null));
        assertThrows(NullPointerException.class, () -> MemorySampleSource.adopt(8000, new float[][] {null}));
    }

    @Test
    void emptySourceIsAllowed() {
        MemorySampleSource s = MemorySampleSource.copyOf(8000, new float[2][0]);
        assertEquals(0, s.frameCount());
        assertEquals(2, s.channelCount());
        s.read(0, 0, new float[2][0], 0);
    }

    @Test
    void copyOfIsDefensive() {
        float[][] data = {{1f, 2f, 3f}};
        MemorySampleSource s = MemorySampleSource.copyOf(8000, data);
        data[0][0] = 99f;
        float[][] out = new float[1][3];
        s.read(0, 3, out, 0);
        assertArrayEquals(new float[] {1f, 2f, 3f}, out[0]);
    }

    @Test
    void adoptTakesOwnershipWithoutCopying() {
        float[][] data = {{1f, 2f, 3f}};
        MemorySampleSource s = MemorySampleSource.adopt(8000, data);
        data[0][0] = 99f; // documented as forbidden; proves no copy was made
        float[][] out = new float[1][3];
        s.read(0, 3, out, 0);
        assertEquals(99f, out[0][0]);
        assertSame(MemorySampleSource.class, s.getClass());
    }

    @Test
    void readsWithOffsetsAndLeavesOtherSamplesUntouched() {
        MemorySampleSource s = MemorySampleSource.copyOf(8000, new float[][] {{1f, 2f, 3f, 4f}, {5f, 6f, 7f, 8f}});
        float[][] out = {{-1f, -1f, -1f, -1f, -1f}, {-1f, -1f, -1f, -1f, -1f}};
        s.read(1, 2, out, 2);
        assertArrayEquals(new float[] {-1f, -1f, 2f, 3f, -1f}, out[0]);
        assertArrayEquals(new float[] {-1f, -1f, 6f, 7f, -1f}, out[1]);
    }

    @Test
    void outOfRangeReadsThrowIndexOutOfBounds() {
        MemorySampleSource s = MemorySampleSource.copyOf(8000, new float[][] {{1f, 2f, 3f}});
        float[][] out = new float[1][10];
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(-1, 1, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(0, 4, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(3, 1, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(0, -1, out, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> s.read(Long.MAX_VALUE, 1, out, 0));
        s.read(3, 0, out, 0); // zero-frame read at the end is legal
    }

    @Test
    void badDestinationShapeThrowsIllegalArgument() {
        MemorySampleSource s = MemorySampleSource.copyOf(8000, new float[][] {{1f, 2f}, {3f, 4f}});
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, null, 0));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, new float[1][2], 0));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, new float[2][1], 0));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, new float[2][3], 2));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, new float[2][2], -1));
        assertThrows(IllegalArgumentException.class, () -> s.read(0, 2, new float[][] {new float[2], null}, 0));
    }
}
