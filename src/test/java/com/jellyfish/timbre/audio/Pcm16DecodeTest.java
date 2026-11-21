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
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The capture path decodes 16-bit signed little-endian PCM with the existing {@link SampleCodec} (the same code
 * the WAV reader uses). Expected values are hand-written literals.
 */
class Pcm16DecodeTest {

    /** Little-endian bytes of the given 16-bit values, written by hand-rolled shifts in the test. */
    private static byte[] le(int... values) {
        byte[] b = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            b[2 * i] = (byte) (values[i] & 0xFF);
            b[2 * i + 1] = (byte) ((values[i] >> 8) & 0xFF);
        }
        return b;
    }

    @Test
    void singleValuesMapByDivisionBy32768() {
        int[] in = {-32768, 32767, 0, 1, -1, 16384, -16384, 256, -257};
        float[] expected = {-1.0f, 0.999969482421875f, 0f, 0.000030517578125f, -0.000030517578125f, 0.5f, -0.5f,
            0.0078125f, -0.007843017578125f};
        float[][] dest = new float[1][in.length];
        SampleCodec.decode(le(in), in.length, 1, WavEncoding.PCM_16, dest, 0);
        assertArrayEquals(expected, dest[0]);
    }

    @Test
    void explicitBytesDecodeWithSignExtensionOfTheHighByte() {
        // 0x00 0x80 = -32768; 0xFF 0x7F = 32767; 0xFF 0xFF = -1; 0x01 0x00 = 1; 0x00 0xC0 = -16384
        byte[] raw = {0x00, (byte) 0x80, (byte) 0xFF, 0x7F, (byte) 0xFF, (byte) 0xFF, 0x01, 0x00, 0x00, (byte) 0xC0};
        float[][] dest = new float[1][5];
        SampleCodec.decode(raw, 5, 1, WavEncoding.PCM_16, dest, 0);
        assertArrayEquals(new float[] {-1.0f, 0.999969482421875f, -0.000030517578125f, 0.000030517578125f, -0.5f},
                dest[0]);
    }

    @Test
    void fourFrameStereoBlockDeinterleaves() {
        // frames: (L, R) = (100, -200), (16384, -16384), (-32768, 32767), (3, -7)
        byte[] raw = le(100, -200, 16384, -16384, -32768, 32767, 3, -7);
        float[][] dest = new float[2][6];
        SampleCodec.decode(raw, 4, 2, WavEncoding.PCM_16, dest, 1);
        assertArrayEquals(new float[] {0f, 0.0030517578125f, 0.5f, -1.0f, 0.000091552734375f, 0f}, dest[0]);
        assertArrayEquals(new float[] {0f, -0.006103515625f, -0.5f, 0.999969482421875f, -0.000213623046875f, 0f},
                dest[1]);
    }

    @Test
    void monoBlockDecodesInOrder() {
        byte[] raw = le(-1000, 2000, -3000);
        float[][] dest = new float[1][3];
        SampleCodec.decode(raw, 3, 1, WavEncoding.PCM_16, dest, 0);
        assertEquals(-0.030517578125f, dest[0][0]);
        assertEquals(0.06103515625f, dest[0][1]);
        assertEquals(-0.091552734375f, dest[0][2]);
    }
}
