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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes the tick marks of the time ruler for a {@link TimeAxis}.
 *
 * <p>Major ticks sit at exact multiples of a step taken from the 1-2-5 sequence (1, 2, 5, 10, ... seconds,
 * from 1 microsecond up to 1000 seconds, then 1 hour); the step is the smallest one that keeps major ticks
 * at least {@value #MIN_MAJOR_SPACING_PX} pixels apart (the largest step if none is wide enough). Minor
 * ticks divide a major step into 10 (steps starting with 1), 2 (steps starting with 2) or 5 (steps
 * starting with 5) parts and are only produced when they are at least {@value #MIN_MINOR_SPACING_PX} pixels
 * apart. Times are held in integer units of 100 nanoseconds so tick positions do not drift.
 *
 * <p><b>Safety cap:</b> generation stops as soon as the list holds {@code widthPx + 2} ticks. Any valid
 * result is far below that (majors are at least 80 px and minors at least 6 px apart, so at most
 * {@code widthPx / 6 + 1} ticks); the cap only bounds the work and the memory if the spacing rules were
 * ever broken. The generating loop itself stops, the list is not truncated afterwards.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class RulerTicks {

    /** Smallest distance between major ticks in pixels. */
    public static final double MIN_MAJOR_SPACING_PX = 80.0;
    /** Smallest distance between minor ticks in pixels. */
    public static final double MIN_MINOR_SPACING_PX = 6.0;

    private static final double EDGE_TOLERANCE_PX = 1e-6;
    private static final double UNITS_PER_SECOND = 1e7; // one unit = 100 ns
    private static final long[] MANTISSAS = {1, 2, 5};
    private static final long MICROSECOND_UNITS = 10;
    private static final long HOUR_UNITS = 3600L * 10_000_000L;

    private RulerTicks() {
    }

    /**
     * One tick mark.
     *
     * <p><b>Threading:</b> immutable; safe to share between threads.
     *
     * @param x     pixel position from the left edge of the view
     * @param frame the nearest timeline frame to the tick's time
     * @param major true for a labelled major tick
     * @param label the text of a major tick; empty for a minor tick
     */
    public record Tick(double x, long frame, boolean major, String label) {
    }

    /**
     * Generates the ticks that fall in {@code [0, widthPx]}, ordered by position.
     *
     * @param axis    the time axis
     * @param widthPx view width in pixels, greater than 0
     * @return an immutable list of ticks; major and minor ticks never coincide
     * @throws IllegalArgumentException if {@code widthPx} is not positive
     */
    public static List<Tick> generate(TimeAxis axis, int widthPx) {
        return generate(axis, widthPx, MIN_MAJOR_SPACING_PX, MIN_MINOR_SPACING_PX);
    }

    /**
     * Same as {@link #generate(TimeAxis, int)} with the two spacing rules as arguments. It exists only so a
     * test can force the pathological case (spacing 0) that the safety cap guards against; the public
     * method always passes the two constants.
     */
    static List<Tick> generate(TimeAxis axis, int widthPx, double minMajorSpacingPx, double minMinorSpacingPx) {
        Objects.requireNonNull(axis, "axis");
        if (widthPx <= 0) {
            throw new IllegalArgumentException("widthPx must be > 0: " + widthPx);
        }
        double framesPerUnit = axis.sampleRate() / UNITS_PER_SECOND;
        double pxPerUnit = framesPerUnit / axis.framesPerPixel();
        long majorUnits = chooseMajor(pxPerUnit, minMajorSpacingPx);
        long mantissa = majorUnits;
        while (mantissa >= 10) {
            mantissa /= 10;
        }
        long divisions = mantissa == 1 ? 10 : (mantissa == 2 ? 2 : 5);
        // an hour is 36 x 10^9 units: its leading digit is 3, treat it as divided in 6 (10-minute minor ticks)
        if (majorUnits == HOUR_UNITS) {
            divisions = 6;
        }
        long minorUnits = majorUnits / divisions;
        boolean withMinor = minorUnits * pxPerUnit >= minMinorSpacingPx;
        long stepUnits = withMinor ? minorUnits : majorUnits;
        long ratio = majorUnits / stepUnits;

        double firstFrame = axis.scrollFrame();
        double lastFrame = axis.scrollFrame() + widthPx * axis.framesPerPixel();
        long first = (long) Math.ceil(firstFrame / framesPerUnit / stepUnits - 1e-9);
        long last = (long) Math.floor(lastFrame / framesPerUnit / stepUnits + 1e-9);
        double majorSeconds = majorUnits / UNITS_PER_SECOND;
        List<Tick> out = new ArrayList<>();
        long cap = (long) widthPx + 2;
        for (long j = Math.max(0, first); j <= last && out.size() < cap; j++) {
            double frameAt = (double) (j * stepUnits) * framesPerUnit;
            double x = (frameAt - axis.scrollFrame()) / axis.framesPerPixel();
            if (x < -EDGE_TOLERANCE_PX || x > widthPx + EDGE_TOLERANCE_PX) {
                continue;
            }
            x = Math.min(Math.max(x, 0.0), widthPx); // rounding must not push an edge tick outside
            boolean major = j % ratio == 0;
            String label = major ? TimeFormat.ruler(j * stepUnits / UNITS_PER_SECOND, majorSeconds) : "";
            out.add(new Tick(x, Math.round(frameAt), major, label));
        }
        return List.copyOf(out);
    }

    private static long chooseMajor(double pxPerUnit, double minMajorSpacingPx) {
        long unit = MICROSECOND_UNITS;
        for (int decade = 0; decade <= 9; decade++) {
            for (long m : MANTISSAS) {
                long step = m * unit;
                if (step > 1000L * 10_000_000L) {
                    return HOUR_UNITS;
                }
                if (step * pxPerUnit >= minMajorSpacingPx) {
                    return step;
                }
            }
            unit *= 10;
        }
        return HOUR_UNITS;
    }
}
