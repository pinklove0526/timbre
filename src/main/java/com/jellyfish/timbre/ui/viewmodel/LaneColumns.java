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

/**
 * The result of sampling one channel lane: for every pixel column its minimum, maximum and RMS, or, when
 * zoomed in past one frame per pixel, the sample values themselves.
 *
 * <p>Instances are reusable: {@link LaneSampler} overwrites the arrays on every call and only grows them. The
 * arrays are valid for indices below {@link #width()}; elements beyond are stale.
 *
 * <p><b>Threading:</b> not thread-safe. It is written by the thread that calls {@link LaneSampler#sample}; hand
 * it to another thread only after that call has returned and stop reusing it.
 */
public final class LaneColumns {

    /** How the columns were computed. */
    public enum Mode {
        /** Zoomed in past one frame per pixel: {@link #samples()} holds one value per frame. */
        SAMPLE_LINES,
        /** Aggregated from the peak pyramid: block-granular, a superset of the column's frames. */
        PEAKS,
        /** Aggregated exactly from the samples. */
        DIRECT,
        /** The pyramid is not ready and the zoom is too wide to read samples: all columns are zero. */
        PLACEHOLDER
    }

    private Mode mode = Mode.PLACEHOLDER;
    private int width;
    private float[] min = new float[0];
    private float[] max = new float[0];
    private float[] rms = new float[0];
    private float[] samples = new float[0];
    private long spanStart;
    private int spanCount;
    private int maxBlocksPerColumn;

    /** Creates an empty result. */
    public LaneColumns() {
        // arrays are sized by the sampler
    }

    void reset(Mode newMode, int newWidth) {
        mode = newMode;
        width = newWidth;
        if (min.length < newWidth) {
            min = new float[newWidth];
            max = new float[newWidth];
            rms = new float[newWidth];
        }
        spanStart = 0;
        spanCount = 0;
        maxBlocksPerColumn = 0;
    }

    float[] samplesFor(long start, int count) {
        if (samples.length < count) {
            samples = new float[count];
        }
        spanStart = start;
        spanCount = count;
        return samples;
    }

    float[] minArray() {
        return min;
    }

    float[] maxArray() {
        return max;
    }

    float[] rmsArray() {
        return rms;
    }

    void noteBlocks(int blocks) {
        if (blocks > maxBlocksPerColumn) {
            maxBlocksPerColumn = blocks;
        }
    }

    /** @return how the columns were computed */
    public Mode mode() {
        return mode;
    }

    /** @return the number of valid columns */
    public int width() {
        return width;
    }

    /** @return true if the pyramid was not ready and the columns are only a flat placeholder */
    public boolean isPlaceholder() {
        return mode == Mode.PLACEHOLDER;
    }

    /**
     * Returns the minimum of a column (in {@link Mode#SAMPLE_LINES} the value of the sample at the column's
     * left edge).
     *
     * @param column column index, {@code 0 <= column < width()}
     * @return the minimum
     * @throws IndexOutOfBoundsException if the column is out of range
     */
    public float min(int column) {
        checkColumn(column);
        return min[column];
    }

    /**
     * Returns the maximum of a column.
     *
     * @param column column index, {@code 0 <= column < width()}
     * @return the maximum
     * @throws IndexOutOfBoundsException if the column is out of range
     */
    public float max(int column) {
        checkColumn(column);
        return max[column];
    }

    /**
     * Returns the RMS of a column: the square root of the mean of the squares over the column's frames
     * (gaps count as zeros).
     *
     * @param column column index, {@code 0 <= column < width()}
     * @return the RMS, at least 0
     * @throws IndexOutOfBoundsException if the column is out of range
     */
    public float rms(int column) {
        checkColumn(column);
        return rms[column];
    }

    /** @return in {@link Mode#SAMPLE_LINES} the timeline frame of {@code sample(0)}, else 0 */
    public long spanStart() {
        return spanStart;
    }

    /**
     * @return in {@link Mode#SAMPLE_LINES} the number of sample values (the visible frames plus one more, so a
     *         line can be drawn to the right edge); else 0
     */
    public int spanCount() {
        return spanCount;
    }

    /**
     * Returns one sample value of the span (zero outside clips).
     *
     * @param index {@code 0 <= index < spanCount()}; the value belongs to timeline frame
     *              {@code spanStart() + index}
     * @return the sample
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public float sample(int index) {
        if (index < 0 || index >= spanCount) {
            throw new IndexOutOfBoundsException("sample " + index + " not in [0, " + spanCount + ")");
        }
        return samples[index];
    }

    float[] samples() {
        return samples;
    }

    /**
     * @return the largest number of pyramid blocks aggregated for one column in the last {@link Mode#PEAKS}
     *         call (a diagnostic; 0 in other modes)
     */
    public int maxBlocksPerColumn() {
        return maxBlocksPerColumn;
    }

    private void checkColumn(int column) {
        if (column < 0 || column >= width) {
            throw new IndexOutOfBoundsException("column " + column + " not in [0, " + width + ")");
        }
    }
}
