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
 * The horizontal mapping between timeline frames and pixel columns: which frame is at the left edge and how
 * many frames one pixel spans.
 *
 * <p>Pixel {@code x} (measured from the left edge of the view) shows frame
 * {@code scrollFrame + floor(x * framesPerPixel)}. Zoom and scroll produce new axes; an axis never changes.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param sampleRate     sample rate of the project in Hz, greater than zero
 * @param framesPerPixel frames spanned by one pixel, positive and finite (below 1 the view is zoomed in past
 *                       single samples)
 * @param scrollFrame    the frame at the left edge, at least 0
 */
public record TimeAxis(int sampleRate, double framesPerPixel, long scrollFrame) {

    /** Smallest allowed frames per pixel: 32 pixels per sample. */
    public static final double MIN_FRAMES_PER_PIXEL = 1.0 / 32.0;
    /** Pixels per second of the axis of an empty project. */
    public static final double EMPTY_PROJECT_PIXELS_PER_SECOND = 100.0;

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if a component is out of range
     */
    public TimeAxis {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
        }
        if (!(framesPerPixel > 0) || Double.isInfinite(framesPerPixel)) {
            throw new IllegalArgumentException("framesPerPixel must be positive and finite: " + framesPerPixel);
        }
        if (scrollFrame < 0) {
            throw new IllegalArgumentException("scrollFrame must be >= 0: " + scrollFrame);
        }
    }

    /**
     * The zoom and scroll limits for one project in one window.
     *
     * <p><b>Threading:</b> immutable; safe to share between threads.
     *
     * @param sampleRate    sample rate in Hz, greater than zero
     * @param projectFrames project length in frames, at least 0 (0 for an empty project)
     * @param widthPx       window width in pixels, greater than zero
     */
    public record Limits(int sampleRate, long projectFrames, int widthPx) {

        /**
         * Validates the components.
         *
         * @throws IllegalArgumentException if a component is out of range
         */
        public Limits {
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be > 0: " + sampleRate);
            }
            if (projectFrames < 0) {
                throw new IllegalArgumentException("projectFrames must be >= 0: " + projectFrames);
            }
            if (widthPx <= 0) {
                throw new IllegalArgumentException("widthPx must be > 0: " + widthPx);
            }
        }

        /** @return the smallest frames per pixel, {@link TimeAxis#MIN_FRAMES_PER_PIXEL} */
        public double minFramesPerPixel() {
            return MIN_FRAMES_PER_PIXEL;
        }

        /** @return the largest frames per pixel: 1.25 times the fit value, at least the minimum */
        public double maxFramesPerPixel() {
            return Math.max(fitFramesPerPixel(sampleRate, projectFrames, widthPx) * 1.25, MIN_FRAMES_PER_PIXEL);
        }

        /**
         * Returns the largest allowed scroll frame at a zoom: {@code max(0, projectFrames - visible / 10)},
         * and exactly 0 when the whole project fits in the window.
         *
         * @param framesPerPixel the zoom
         * @return the largest scroll frame, at least 0
         */
        public long maxScrollFrame(double framesPerPixel) {
            long visible = Math.round(widthPx * framesPerPixel);
            if (projectFrames <= visible) {
                return 0;
            }
            return Math.max(0, projectFrames - visible / 10);
        }

        /**
         * Limits a zoom to the allowed range.
         *
         * @param framesPerPixel any positive value
         * @return the value inside {@code [minFramesPerPixel(), maxFramesPerPixel()]}
         */
        public double clampFramesPerPixel(double framesPerPixel) {
            return Math.min(maxFramesPerPixel(), Math.max(MIN_FRAMES_PER_PIXEL, framesPerPixel));
        }
    }

    private static double fitFramesPerPixel(int sampleRate, long projectFrames, int widthPx) {
        if (projectFrames <= 0) {
            return sampleRate / EMPTY_PROJECT_PIXELS_PER_SECOND;
        }
        return Math.max(MIN_FRAMES_PER_PIXEL, (double) projectFrames / widthPx);
    }

    /**
     * Creates the axis that shows the whole project from frame 0 across the window; an empty project gets
     * 100 pixels per second.
     *
     * @param sampleRate    sample rate in Hz, greater than zero
     * @param projectFrames project length in frames, at least 0
     * @param widthPx       window width in pixels, greater than zero
     * @return the fitted axis (never more zoomed in than 32 pixels per sample)
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static TimeAxis fit(int sampleRate, long projectFrames, int widthPx) {
        Limits check = new Limits(sampleRate, projectFrames, widthPx);
        return new TimeAxis(check.sampleRate(), fitFramesPerPixel(sampleRate, projectFrames, widthPx), 0);
    }

    /**
     * Returns the frame shown at a pixel column.
     *
     * @param x pixel position from the left edge (may be negative or beyond the width)
     * @return {@code scrollFrame + floor(x * framesPerPixel)}
     */
    public long frameAtX(double x) {
        return scrollFrame + (long) Math.floor(x * framesPerPixel);
    }

    /**
     * Returns the pixel position of a frame.
     *
     * @param frame a timeline frame
     * @return {@code (frame - scrollFrame) / framesPerPixel}
     */
    public double xAtFrame(long frame) {
        return (frame - scrollFrame) / framesPerPixel;
    }

    /**
     * Returns how many frames a window of the given width shows.
     *
     * @param widthPx window width in pixels, at least 0
     * @return the width times frames per pixel, rounded to the nearest frame
     * @throws IllegalArgumentException if {@code widthPx} is negative
     */
    public long visibleFrames(int widthPx) {
        if (widthPx < 0) {
            throw new IllegalArgumentException("widthPx must be >= 0: " + widthPx);
        }
        return Math.round(widthPx * framesPerPixel);
    }

    /**
     * Zooms around a pixel column. The time under {@code anchorX} stays under it, so the view does not jump;
     * the new zoom is {@code framesPerPixel / factor} limited to the allowed range, and the new scroll
     * position is limited to the allowed range at that zoom.
     *
     * <p>Because the scroll position is a whole frame, the anchor keeps its pixel to within one pixel when
     * the new zoom is 0.5 frames per pixel or coarser, and to within half a frame (that is
     * {@code 0.5 / framesPerPixel} pixels) when zoomed further in. If the scroll position hits its limit the
     * anchor moves as far as needed.
     *
     * @param factor  zoom factor greater than 0; above 1 zooms in, below 1 zooms out
     * @param anchorX pixel column that keeps its time
     * @param limits  the limits for the project and window
     * @return the zoomed axis
     * @throws IllegalArgumentException if {@code factor} is not positive and finite or the sample rate of
     *                                  {@code limits} differs from this axis
     */
    public TimeAxis zoomedAt(double factor, double anchorX, Limits limits) {
        if (!(factor > 0) || Double.isInfinite(factor)) {
            throw new IllegalArgumentException("factor must be positive and finite: " + factor);
        }
        requireSameRate(limits);
        double anchorFrame = scrollFrame + anchorX * framesPerPixel;
        double newFpp = limits.clampFramesPerPixel(framesPerPixel / factor);
        long wanted = Math.round(anchorFrame - anchorX * newFpp);
        long scroll = Math.min(Math.max(0, wanted), limits.maxScrollFrame(newFpp));
        return new TimeAxis(sampleRate, newFpp, scroll);
    }

    /**
     * Returns this zoom scrolled to a frame at the left edge, limited to the allowed scroll range.
     *
     * @param frame  the wanted frame at the left edge (any value)
     * @param limits the limits for the project and window
     * @return the scrolled axis with the same zoom
     * @throws IllegalArgumentException if the sample rate of {@code limits} differs from this axis
     */
    public TimeAxis scrolledTo(long frame, Limits limits) {
        requireSameRate(limits);
        long scroll = Math.min(Math.max(0, frame), limits.maxScrollFrame(framesPerPixel));
        return new TimeAxis(sampleRate, framesPerPixel, scroll);
    }

    private void requireSameRate(Limits limits) {
        if (limits.sampleRate() != sampleRate) {
            throw new IllegalArgumentException("limits are for " + limits.sampleRate() + " Hz, axis is "
                    + sampleRate + " Hz");
        }
    }
}
