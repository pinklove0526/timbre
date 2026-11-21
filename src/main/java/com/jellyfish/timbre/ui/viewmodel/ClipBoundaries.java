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

import com.jellyfish.timbre.model.Clip;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The pixel columns of the clip border lines of one lane.
 *
 * <p>The column of a frame {@code f} is {@code floor(axis.xAtFrame(f))}. A clip's start line is at
 * {@code column(start)}; its end line at {@code column(end) - 1}, but never left of the start line (a clip
 * narrower than 2 px may get a single line). Only columns in {@code [0, width)} are returned; lines that
 * coincide (for example the end of a very narrow clip and the start of the next one) are returned once.
 * Note that two touching clips usually give two neighbouring columns (the end line of the first at
 * {@code c - 1}, the start line of the second at {@code c}).
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class ClipBoundaries {

    private ClipBoundaries() {
    }

    /**
     * Computes the border columns.
     *
     * @param clips   the clips of the track
     * @param axis    the time axis
     * @param widthPx the lane width in pixels, at least 0
     * @return the sorted, unique columns inside {@code [0, widthPx)}
     * @throws IllegalArgumentException if {@code widthPx} is negative
     */
    public static int[] columns(List<Clip> clips, TimeAxis axis, int widthPx) {
        Objects.requireNonNull(clips, "clips");
        Objects.requireNonNull(axis, "axis");
        if (widthPx < 0) {
            throw new IllegalArgumentException("widthPx must be >= 0: " + widthPx);
        }
        TreeSet<Long> cols = new TreeSet<>();
        for (Clip c : clips) {
            long start = column(axis, c.timelineStart());
            long end = Math.max(column(axis, c.timelineEnd()) - 1, start);
            if (start >= 0 && start < widthPx) {
                cols.add(start);
            }
            if (end >= 0 && end < widthPx) {
                cols.add(end);
            }
        }
        int[] out = new int[cols.size()];
        int i = 0;
        for (long x : cols) {
            out[i++] = (int) x;
        }
        return out;
    }

    private static long column(TimeAxis axis, long frame) {
        return (long) Math.floor(axis.xAtFrame(frame));
    }
}
