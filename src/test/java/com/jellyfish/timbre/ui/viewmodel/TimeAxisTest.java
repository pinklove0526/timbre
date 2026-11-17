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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TimeAxisTest {

    @Test
    void mappingKnownAnswers() {
        TimeAxis a = new TimeAxis(44100, 441, 44100);
        assertEquals(88200, a.frameAtX(100));
        assertEquals(100.0, a.xAtFrame(88200));
        assertEquals(44100, a.frameAtX(0));
        assertEquals(44100 + 436, a.frameAtX(0.99));
        assertEquals(44100 - 441, a.frameAtX(-1));
        assertEquals(441000, a.visibleFrames(1000));
        TimeAxis z = new TimeAxis(48000, 0.5, 1000);
        assertEquals(1001, z.frameAtX(3));
        assertEquals(10.0, z.xAtFrame(1005));
        assertEquals(-2.0, z.xAtFrame(999));
        assertEquals(3, new TimeAxis(44100, 0.3, 0).visibleFrames(10));
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis(44100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis(44100, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis(44100, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis(44100, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis.Limits(44100, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new TimeAxis.Limits(44100, 10, 0));
    }

    @Test
    void fitKnownAnswers() {
        TimeAxis a = TimeAxis.fit(44100, 441000, 1000);
        assertEquals(441.0, a.framesPerPixel());
        assertEquals(0, a.scrollFrame());
        assertEquals(44100, a.sampleRate());
        assertEquals(441.0, TimeAxis.fit(44100, 0, 800).framesPerPixel(), "empty project: 100 px per second");
        assertEquals(1.0 / 32, TimeAxis.fit(44100, 5, 1000).framesPerPixel(), "never beyond 32 px per sample");
        assertEquals(960.0, TimeAxis.fit(96000, 96000, 100).framesPerPixel());
    }

    @Test
    void limitsKnownAnswers() {
        TimeAxis.Limits l = new TimeAxis.Limits(44100, 441000, 1000);
        assertEquals(1.0 / 32, l.minFramesPerPixel());
        assertEquals(551.25, l.maxFramesPerPixel());
        assertEquals(0, l.maxScrollFrame(441), "the whole project fits");
        assertEquals(0, l.maxScrollFrame(1000));
        assertEquals(431000, l.maxScrollFrame(100)); // 441000 - 100000 / 10
        assertEquals(440950, l.maxScrollFrame(0.5)); // 441000 - 500 / 10
        assertEquals(551.25, l.clampFramesPerPixel(1e9));
        assertEquals(1.0 / 32, l.clampFramesPerPixel(1e-9));
        assertEquals(7.5, l.clampFramesPerPixel(7.5));

        TimeAxis.Limits empty = new TimeAxis.Limits(44100, 0, 800);
        assertEquals(551.25, empty.maxFramesPerPixel());
        assertEquals(0, empty.maxScrollFrame(10));

        TimeAxis.Limits tiny = new TimeAxis.Limits(44100, 5, 1000);
        assertEquals(1.25 / 32, tiny.maxFramesPerPixel());
        assertEquals(0, tiny.maxScrollFrame(1.0 / 32));
    }

    @Test
    void scrolledToClamps() {
        TimeAxis.Limits l = new TimeAxis.Limits(44100, 441000, 1000);
        TimeAxis a = new TimeAxis(44100, 100, 0);
        assertEquals(5000, a.scrolledTo(5000, l).scrollFrame());
        assertEquals(431000, a.scrolledTo(Long.MAX_VALUE, l).scrollFrame());
        assertEquals(0, a.scrolledTo(-77, l).scrollFrame());
        assertEquals(100.0, a.scrolledTo(5000, l).framesPerPixel());
        TimeAxis fits = new TimeAxis(44100, 441, 0);
        assertEquals(0, fits.scrolledTo(12345, l).scrollFrame(), "exactly 0 when the project fits");
        assertThrows(IllegalArgumentException.class, () -> a.scrolledTo(1, new TimeAxis.Limits(48000, 10, 10)));
    }

    @Test
    void zoomedAtKnownAnswers() {
        TimeAxis.Limits l = new TimeAxis.Limits(44100, 4410000, 1000);
        TimeAxis a = new TimeAxis(44100, 441, 0);
        // the frame under pixel 500 is 220500; after zooming in 2x it must still be under pixel 500
        TimeAxis in = a.zoomedAt(2, 500, l);
        assertEquals(220.5, in.framesPerPixel());
        assertEquals(110250, in.scrollFrame());
        assertEquals(220500, in.frameAtX(500));
        TimeAxis out = in.zoomedAt(0.5, 500, l);
        assertEquals(441.0, out.framesPerPixel());
        assertEquals(0, out.scrollFrame());
        assertEquals(1.0 / 32, a.zoomedAt(1e9, 10, l).framesPerPixel(), "clamped to 32 px per sample");
        assertEquals(5512.5, a.zoomedAt(1e-9, 10, l).framesPerPixel(), "clamped to the fit plus margin");
        assertEquals(0, a.zoomedAt(1e-9, 10, l).scrollFrame());
        assertThrows(IllegalArgumentException.class, () -> a.zoomedAt(0, 0, l));
        assertThrows(IllegalArgumentException.class, () -> a.zoomedAt(Double.NaN, 0, l));
    }

    @Test
    void zoomKeepsTheAnchorUnderThePointer() {
        Random r = new Random(4242);
        int interior = 0;
        for (int iter = 0; iter < 20000; iter++) {
            int rate = 44100;
            long project = 1000 + (long) (r.nextDouble() * 5e7);
            int width = 300 + r.nextInt(1700);
            TimeAxis.Limits l = new TimeAxis.Limits(rate, project, width);
            double fpp = l.clampFramesPerPixel(Math.exp(Math.log(l.minFramesPerPixel())
                    + r.nextDouble() * (Math.log(l.maxFramesPerPixel()) - Math.log(l.minFramesPerPixel()))));
            TimeAxis a = new TimeAxis(rate, fpp, (long) (r.nextDouble() * l.maxScrollFrame(fpp)));
            double anchorX = r.nextDouble() * width;
            double factor = Math.exp((r.nextDouble() - 0.5) * 6);
            TimeAxis b = a.zoomedAt(factor, anchorX, l);

            assertTrue(b.framesPerPixel() >= l.minFramesPerPixel() && b.framesPerPixel() <= l.maxFramesPerPixel());
            assertTrue(b.scrollFrame() >= 0 && b.scrollFrame() <= l.maxScrollFrame(b.framesPerPixel()));
            double before = a.scrollFrame() + anchorX * a.framesPerPixel();
            double after = b.scrollFrame() + anchorX * b.framesPerPixel();
            boolean clamped = b.scrollFrame() == 0 || b.scrollFrame() == l.maxScrollFrame(b.framesPerPixel());
            if (!clamped) {
                interior++;
                // a whole-frame scroll moves the anchor by at most half a frame, which is at most one pixel
                // from 0.5 frames per pixel up and 0.5 / framesPerPixel pixels below that
                double pixels = Math.abs(after - before) / b.framesPerPixel();
                double bound = Math.max(1.0, 0.5 / b.framesPerPixel());
                assertTrue(pixels <= bound + 1e-9, "anchor moved " + pixels + " px (bound " + bound + ") at fpp "
                        + b.framesPerPixel());
            }
        }
        assertTrue(interior > 5000, "too few unclamped cases: " + interior);
    }

    @Test
    void zoomingOutAtTheEdgesStaysInRange() {
        TimeAxis.Limits l = new TimeAxis.Limits(44100, 441000, 1000);
        TimeAxis a = new TimeAxis(44100, 10, 400000);
        for (int i = 0; i < 40; i++) {
            a = a.zoomedAt(0.7, 999, l);
            assertTrue(a.scrollFrame() <= l.maxScrollFrame(a.framesPerPixel()));
        }
        assertEquals(l.maxFramesPerPixel(), a.framesPerPixel());
        assertEquals(0, a.scrollFrame());
    }
}
