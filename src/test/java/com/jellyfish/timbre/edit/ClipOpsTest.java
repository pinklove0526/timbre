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
package com.jellyfish.timbre.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-written known answers. Source A is 10 frames with samples 1..10, B is 4 frames 101..104, C is 5 frames
 * 201..205 (mono, 8000 Hz).
 */
class ClipOpsTest {

    private static final MemorySampleSource A = src(1, 10);
    private static final MemorySampleSource B = src(101, 4);
    private static final MemorySampleSource C = src(201, 5);

    private static MemorySampleSource src(int first, int frames) {
        float[] d = new float[frames];
        for (int i = 0; i < frames; i++) {
            d[i] = first + i;
        }
        return MemorySampleSource.copyOf(8000, new float[][] {d});
    }

    private static Clip clip(MemorySampleSource s, long sourceStart, long count, long at) {
        return new Clip(s, sourceStart, count, at);
    }

    /** Copy as an assertion: an exception (for example a broken fragment invariant) is a test failure. */
    private static Fragment copy(List<Clip> clips, long from, long to) {
        return assertDoesNotThrow(() -> ClipOps.copy(clips, from, to), "copy [" + from + ", " + to + ")");
    }

    private static final List<Clip> WHOLE_A = List.of(clip(A, 0, 10, 0));
    /** A[0,4) at 0, gap [4,6), B[0,3) at 6. */
    private static final List<Clip> A_GAP_B = List.of(clip(A, 0, 4, 0), clip(B, 0, 3, 6));
    /** A[0,4) at 0, B[0,3) at 4, C[0,5) at 7: three touching clips. */
    private static final List<Clip> ABC = List.of(clip(A, 0, 4, 0), clip(B, 0, 3, 4), clip(C, 0, 5, 7));

    // ------------------------------------------------------------------ the example of the prompt

    @Test
    void deleteInsideAClip() {
        List<Clip> r = ClipOps.delete(WHOLE_A, 3, 6);
        assertEquals(List.of(clip(A, 0, 3, 0), clip(A, 6, 4, 3)), r);
        assertArrayEquals(new float[] {1, 2, 3, 7, 8, 9, 10}, Oracle.mono(r));
    }

    @Test
    void insertAFourFrameFragmentAtTwo() {
        Fragment f = new Fragment(4, List.of(clip(B, 0, 4, 0)));
        List<Clip> r = ClipOps.insert(WHOLE_A, 2, f);
        assertEquals(List.of(clip(A, 0, 2, 0), clip(B, 0, 4, 2), clip(A, 2, 8, 6)), r);
        assertArrayEquals(new float[] {1, 2, 101, 102, 103, 104, 3, 4, 5, 6, 7, 8, 9, 10}, Oracle.mono(r));
    }

    @Test
    void copyTwoToEight() {
        Fragment f = copy(WHOLE_A, 2, 8);
        assertEquals(new Fragment(6, List.of(clip(A, 2, 6, 0))), f);
    }

    @Test
    void silenceInsideAClipLeavesAGap() {
        List<Clip> r = ClipOps.silence(WHOLE_A, 3, 6);
        assertEquals(List.of(clip(A, 0, 3, 0), clip(A, 6, 4, 6)), r);
        assertArrayEquals(new float[] {1, 2, 3, 0, 0, 0, 7, 8, 9, 10}, Oracle.mono(r));
    }

    @Test
    void trimKeepsTheRangeAtZero() {
        List<Clip> r = ClipOps.trim(WHOLE_A, 3, 6);
        assertEquals(List.of(clip(A, 3, 3, 0)), r);
        assertArrayEquals(new float[] {4, 5, 6}, Oracle.mono(r));
    }

    @Test
    void splitInsideAtAStartAndAtAnEnd() {
        assertEquals(List.of(clip(A, 0, 4, 0), clip(A, 4, 6, 4)), ClipOps.split(WHOLE_A, 4));
        assertEquals(WHOLE_A, ClipOps.split(WHOLE_A, 0), "at the clip start");
        assertEquals(WHOLE_A, ClipOps.split(WHOLE_A, 10), "at the clip end");
        assertEquals(A_GAP_B, ClipOps.split(A_GAP_B, 5), "in a gap");
        assertEquals(A_GAP_B, ClipOps.split(A_GAP_B, 50), "beyond the content");
        assertEquals(ABC, ClipOps.split(ABC, 4), "at the touching edge of two clips");
        assertEquals(List.of(clip(A, 0, 4, 0), clip(B, 0, 1, 4), clip(B, 1, 2, 5), clip(C, 0, 5, 7)),
                ClipOps.split(ABC, 5));
    }

    // ------------------------------------------------------------------ boundaries

    @Test
    void aRangeExactlyEqualToAClip() {
        assertEquals(List.of(clip(A, 0, 4, 0)), ClipOps.delete(A_GAP_B, 6, 9));
        assertEquals(List.of(clip(B, 0, 3, 2)), ClipOps.delete(A_GAP_B, 0, 4));
        assertEquals(List.of(clip(A, 0, 4, 0), clip(C, 0, 5, 4)), ClipOps.delete(ABC, 4, 7), "touching edges");
        assertEquals(List.of(clip(A, 0, 4, 0), clip(C, 0, 5, 7)), ClipOps.silence(ABC, 4, 7));
        assertEquals(List.of(clip(B, 0, 3, 0)), ClipOps.trim(ABC, 4, 7));
        assertEquals(new Fragment(3, List.of(clip(B, 0, 3, 0))), copy(ABC, 4, 7));
    }

    @Test
    void aRangeAcrossAGap() {
        List<Clip> r = ClipOps.delete(A_GAP_B, 3, 7);
        assertEquals(List.of(clip(A, 0, 3, 0), clip(B, 1, 2, 3)), r);
        assertArrayEquals(new float[] {1, 2, 3, 102, 103}, Oracle.mono(r));
        assertEquals(new Fragment(4, List.of(clip(A, 3, 1, 0), clip(B, 0, 1, 3))), copy(A_GAP_B, 3, 7));
        assertEquals(List.of(clip(A, 0, 3, 0), clip(B, 1, 2, 7)), ClipOps.silence(A_GAP_B, 3, 7));
    }

    @Test
    void aRangeAcrossThreeClips() {
        List<Clip> r = ClipOps.delete(ABC, 2, 9);
        assertEquals(List.of(clip(A, 0, 2, 0), clip(C, 2, 3, 2)), r);
        assertArrayEquals(new float[] {1, 2, 203, 204, 205}, Oracle.mono(r));
        assertEquals(List.of(clip(A, 2, 2, 0), clip(B, 0, 3, 2), clip(C, 0, 2, 5)), ClipOps.trim(ABC, 2, 9));
    }

    @Test
    void rangesBeforeZeroAreClampedAndRangesBeyondTheContentChangeNothing() {
        assertEquals(List.of(clip(A, 2, 8, 0)), ClipOps.delete(WHOLE_A, -5, 2));
        assertEquals(WHOLE_A, ClipOps.delete(WHOLE_A, 20, 30));
        assertEquals(WHOLE_A, ClipOps.silence(WHOLE_A, 20, 30));
        assertEquals(WHOLE_A, ClipOps.delete(WHOLE_A, -9, -1), "entirely before 0");
        assertEquals(List.of(), ClipOps.trim(WHOLE_A, 20, 30), "trim beyond the content keeps nothing");
        assertEquals(List.of(clip(A, 8, 2, 0)), ClipOps.delete(WHOLE_A, 0, 8));
        assertEquals(List.of(clip(A, 0, 8, 0)), ClipOps.delete(WHOLE_A, 8, 30), "partly beyond the content");
    }

    @Test
    void zeroLengthRangesChangeNothing() {
        assertEquals(WHOLE_A, ClipOps.delete(WHOLE_A, 5, 5));
        assertEquals(WHOLE_A, ClipOps.silence(WHOLE_A, 5, 5));
        assertEquals(WHOLE_A, ClipOps.trim(WHOLE_A, 5, 5));
        assertEquals(WHOLE_A, ClipOps.delete(WHOLE_A, 6, 2), "to before from");
        assertEquals(new Fragment(0, List.of()), copy(WHOLE_A, 5, 5));
        assertEquals(WHOLE_A, ClipOps.insert(WHOLE_A, 3, new Fragment(0, List.of())));
    }

    @Test
    void fragmentsKeepLeadingAndTrailingGaps() {
        List<Clip> late = List.of(clip(A, 0, 4, 6)); // A[0,4) at timeline 6
        assertEquals(new Fragment(4, List.of(clip(A, 0, 2, 2))), copy(late, 4, 8), "leading gap");
        assertEquals(new Fragment(6, List.of(clip(A, 8, 2, 0))), copy(WHOLE_A, 8, 14), "trailing gap");
        assertEquals(new Fragment(2, List.of()), copy(A_GAP_B, 4, 6), "only a gap");
        assertEquals(List.of(clip(A, 0, 3, 1)), ClipOps.trim(late, 5, 9), "trim keeps a leading gap");
    }

    @Test
    void insertAFragmentWithGapsAtAClipStartAndBeyondTheEnd() {
        Fragment gappy = new Fragment(6, List.of(clip(B, 0, 2, 1))); // gap, B 101 102, three frames of gap
        List<Clip> r = ClipOps.insert(WHOLE_A, 3, gappy);
        assertEquals(List.of(clip(A, 0, 3, 0), clip(B, 0, 2, 4), clip(A, 3, 7, 9)), r);
        assertArrayEquals(new float[] {1, 2, 3, 0, 101, 102, 0, 0, 0, 4, 5, 6, 7, 8, 9, 10}, Oracle.mono(r));
        assertEquals(List.of(clip(B, 0, 2, 1), clip(A, 0, 10, 6)), ClipOps.insert(WHOLE_A, 0, gappy));
        assertEquals(List.of(clip(A, 0, 10, 0), clip(B, 0, 2, 16)), ClipOps.insert(WHOLE_A, 15, gappy),
                "beyond the end: a gap before the pasted clips");
        assertEquals(List.of(clip(A, 0, 10, 0), clip(B, 0, 2, 11)), ClipOps.insert(WHOLE_A, 10, gappy),
                "exactly at the end");
        assertThrows(IllegalArgumentException.class, () -> ClipOps.insert(WHOLE_A, -1, gappy));
    }

    @Test
    void wellFormedness() {
        assertTrue(ClipOps.isWellFormed(List.of()));
        assertTrue(ClipOps.isWellFormed(ABC));
        assertFalse(ClipOps.isWellFormed(List.of(clip(A, 0, 4, 3), clip(B, 0, 3, 5))), "overlap");
        assertFalse(ClipOps.isWellFormed(List.of(clip(B, 0, 3, 6), clip(A, 0, 4, 0))), "unsorted");
        assertThrows(IllegalArgumentException.class, () -> new Fragment(3, List.of(clip(A, 0, 4, 0))),
                "a clip beyond the fragment length");
        assertThrows(IllegalArgumentException.class, () -> new Fragment(-1, List.of()));
    }

    @Test
    void inputsAreNeverModified() {
        List<Clip> before = List.copyOf(ABC);
        ClipOps.delete(ABC, 2, 9);
        ClipOps.insert(ABC, 5, new Fragment(4, List.of(clip(B, 0, 4, 0))));
        ClipOps.split(ABC, 5);
        assertEquals(before, ABC);
    }
}
