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
package com.jellyfish.timbre.ui.viewmodel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-computed columns at 10 frames per pixel: column(f) = floor((f - scroll) / 10); start line at
 * column(start), end line at max(column(end) - 1, column(start)); width 100 unless stated.
 */
class ClipBoundariesTest {

    private static final MemorySampleSource SRC = MemorySampleSource.copyOf(8000, new float[][] {new float[5000]});
    private static final TimeAxis AXIS = new TimeAxis(8000, 10.0, 0);

    private static Clip clip(long start, long end) {
        return new Clip(SRC, 0, end - start, start);
    }

    @Test
    void twoClipsWithAGap() {
        // [0,100): 0 and 9 ; [150,300): 15 and 29
        assertArrayEquals(new int[] {0, 9, 15, 29},
                ClipBoundaries.columns(List.of(clip(0, 100), clip(150, 300)), AXIS, 100));
    }

    @Test
    void adjacentClipsShareAColumnOnlyWhenTheLinesCoincide() {
        // a narrow first clip [100,105): start 10, end max(10 - 1, 10) = 10; the next clip starts at column 10 too
        assertArrayEquals(new int[] {10, 29},
                ClipBoundaries.columns(List.of(clip(100, 105), clip(105, 300)), AXIS, 100));
        // touching at an aligned frame: the end line of the first (9) and the start of the second (10) are neighbours
        assertArrayEquals(new int[] {0, 9, 10, 24},
                ClipBoundaries.columns(List.of(clip(0, 100), clip(100, 250)), AXIS, 100));
    }

    @Test
    void narrowClips() {
        // narrower than 1 px: [203,207) -> both in column 20
        assertArrayEquals(new int[] {20}, ClipBoundaries.columns(List.of(clip(203, 207)), AXIS, 100));
        // exactly 1.5 px, aligned: [200,215) -> start 20, end max(21 - 1, 20) = 20: one line
        assertArrayEquals(new int[] {20}, ClipBoundaries.columns(List.of(clip(200, 215)), AXIS, 100));
        // exactly 1.5 px, offset: [205,220) -> start 20, end 22 - 1 = 21: two lines
        assertArrayEquals(new int[] {20, 21}, ClipBoundaries.columns(List.of(clip(205, 220)), AXIS, 100));
    }

    @Test
    void clipsPartlyOutsideTheView() {
        TimeAxis scrolled = new TimeAxis(8000, 10.0, 500); // column(f) = floor((f - 500) / 10)
        // [0,800): start -50 (outside), end 30 - 1 = 29 ; [1400,2000): start 90, end 150 - 1 = 149 (outside)
        assertArrayEquals(new int[] {29, 90},
                ClipBoundaries.columns(List.of(clip(0, 800), clip(1400, 2000)), scrolled, 100));
        // a clip entirely left of the view
        assertArrayEquals(new int[] {}, ClipBoundaries.columns(List.of(clip(0, 300)), scrolled, 100));
        // the end line exactly at the last column 99: [1490,1500) -> start 99, end max(100 - 1, 99) = 99
        assertArrayEquals(new int[] {99}, ClipBoundaries.columns(List.of(clip(1490, 1500)), scrolled, 100));
    }

    @Test
    void anEmptyListAndAZoomWhereStartAndEndCoincide() {
        assertArrayEquals(new int[] {}, ClipBoundaries.columns(List.of(), AXIS, 100));
        TimeAxis far = new TimeAxis(8000, 1000.0, 0); // 1000 frames per pixel
        // [0,800) and [2000,2700): starts 0 and 2, ends max(0 - 1, 0) = 0 and max(2 - 1, 2) = 2
        assertArrayEquals(new int[] {0, 2},
                ClipBoundaries.columns(List.of(clip(0, 800), clip(2000, 2700)), far, 100));
    }

    @Test
    void zoomedInPastOneFramePerPixel() {
        TimeAxis near = new TimeAxis(8000, 0.25, 0); // 4 px per frame
        // [3,7): start 12, end 28 - 1 = 27 ; [10,11): start 40, end 44 - 1 = 43
        assertArrayEquals(new int[] {12, 27, 40, 43},
                ClipBoundaries.columns(List.of(clip(3, 7), clip(10, 11)), near, 100));
        assertThrows(IllegalArgumentException.class, () -> ClipBoundaries.columns(List.of(), near, -1));
    }
}
