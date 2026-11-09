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

/**
 * Conversion between little-endian interleaved sample bytes and planar float samples.
 * The inner loops do not allocate.
 *
 * <p><b>Threading:</b> stateless; thread-safe.
 *
 * <p>32-bit PCM is not lossless through float32 (24-bit mantissa); 8, 16 and 24-bit PCM and
 * float32 round-trip exactly.
 */
final class SampleCodec {

    private SampleCodec() {
    }

    static int bytesPerSample(WavEncoding encoding) {
        return encoding.bitsPerSample() / 8;
    }

    /** Decodes {@code frames} interleaved frames from {@code b} (starting at 0) into {@code dest}. */
    static void decode(byte[] b, int frames, int channels, WavEncoding encoding, float[][] dest, int destOffset) {
        int p = 0;
        switch (encoding) {
            case PCM_8:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        dest[c][destOffset + f] = ((b[p++] & 0xFF) - 128) / 128f;
                    }
                }
                break;
            case PCM_16:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = (b[p] & 0xFF) | (b[p + 1] << 8);
                        p += 2;
                        dest[c][destOffset + f] = v / 32768f;
                    }
                }
                break;
            case PCM_24:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] << 16);
                        p += 3;
                        dest[c][destOffset + f] = v / 8388608f;
                    }
                }
                break;
            case PCM_32:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = int32(b, p);
                        p += 4;
                        dest[c][destOffset + f] = (float) v / 2147483648f;
                    }
                }
                break;
            case FLOAT_32:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        dest[c][destOffset + f] = Float.intBitsToFloat(int32(b, p));
                        p += 4;
                    }
                }
                break;
            default:
                throw new IllegalStateException("unhandled encoding " + encoding);
        }
    }

    /** Encodes {@code frames} planar frames from {@code src} (starting at {@code srcOffset}) into {@code out}. */
    static void encode(float[][] src, int srcOffset, int frames, int channels, WavEncoding encoding, byte[] out) {
        int p = 0;
        switch (encoding) {
            case PCM_8:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        out[p++] = (byte) (quantize(src[c][srcOffset + f], 128.0, -128L, 127L) + 128);
                    }
                }
                break;
            case PCM_16:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = (int) quantize(src[c][srcOffset + f], 32768.0, -32768L, 32767L);
                        out[p++] = (byte) v;
                        out[p++] = (byte) (v >> 8);
                    }
                }
                break;
            case PCM_24:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = (int) quantize(src[c][srcOffset + f], 8388608.0, -8388608L, 8388607L);
                        out[p++] = (byte) v;
                        out[p++] = (byte) (v >> 8);
                        out[p++] = (byte) (v >> 16);
                    }
                }
                break;
            case PCM_32:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        int v = (int) quantize(src[c][srcOffset + f], 2147483648.0, -2147483648L, 2147483647L);
                        p = putInt32(out, p, v);
                    }
                }
                break;
            case FLOAT_32:
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        p = putInt32(out, p, Float.floatToRawIntBits(src[c][srcOffset + f]));
                    }
                }
                break;
            default:
                throw new IllegalStateException("unhandled encoding " + encoding);
        }
    }

    private static int int32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | (b[p + 3] << 24);
    }

    private static int putInt32(byte[] out, int p, int v) {
        out[p] = (byte) v;
        out[p + 1] = (byte) (v >> 8);
        out[p + 2] = (byte) (v >> 16);
        out[p + 3] = (byte) (v >> 24);
        return p + 4;
    }

    /** Scales in double, rounds half-even, clamps; NaN becomes 0. */
    static long quantize(float sample, double scale, long min, long max) {
        if (Float.isNaN(sample)) {
            return 0L;
        }
        double v = Math.rint(sample * scale);
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return (long) v;
    }
}
