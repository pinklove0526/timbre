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
package com.jellyfish.timbre.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** Known-answer tests for the float to 16-bit stereo conversion used by the engine (via {@link SampleCodec}). */
class Pcm16StereoConversionTest {

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private static byte[] convert(float[] left, float[] right) {
        byte[] out = new byte[left.length * 4];
        SampleCodec.encode(new float[][] {left, right}, 0, left.length, 2, WavEncoding.PCM_16, out);
        return out;
    }

    @Test
    void asymmetricValuesGoLeftThenRightLittleEndian() {
        // frame 0: L = 0x1234 (4660), R = 0x0102 (258); frame 1: L = 0xFEDC (-292), R = 0xFDFC (-516)
        byte[] out = convert(new float[] {4660 / 32768f, -292 / 32768f}, new float[] {258 / 32768f, -516 / 32768f});
        assertArrayEquals(b(0x34, 0x12, 0x02, 0x01, 0xDC, 0xFE, 0xFC, 0xFD), out);
    }

    @Test
    void fullScaleAndOutOfRangeValuesAreClamped() {
        float[] l = {1.0f, -1.0f, 2.0f, -2.0f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        float[] r = {-1.0f, 1.0f, -2.0f, 2.0f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY};
        byte[] out = convert(l, r);
        assertArrayEquals(b(
                0xFF, 0x7F, 0x00, 0x80,   // +1.0 -> 32767, -1.0 -> -32768
                0x00, 0x80, 0xFF, 0x7F,
                0xFF, 0x7F, 0x00, 0x80,   // +2.0 clamped, -2.0 clamped
                0x00, 0x80, 0xFF, 0x7F,
                0xFF, 0x7F, 0x00, 0x80,   // +Inf, -Inf
                0x00, 0x80, 0xFF, 0x7F), out);
    }

    @Test
    void nanBecomesZero() {
        assertArrayEquals(b(0, 0, 0x34, 0x12), convert(new float[] {Float.NaN}, new float[] {4660 / 32768f}));
    }
}
