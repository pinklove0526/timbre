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
package com.jellyfish.timbre.audio.support;

/** Builds a WAV file plus the float samples a correct decoder must produce, independently of production code. */
public record TestWav(byte[] file, float[][] expected, int sampleRate) {

    /**
     * Builds a test file.
     *
     * @param tag      1 (PCM) or 3 (float)
     * @param bits     8, 16, 24 or 32
     * @param channels channel count
     * @param frames   frames per channel
     * @param rate     sample rate
     * @param seed     seed for the pseudo-random content
     * @return the file and the expected decoded samples
     */
    public static TestWav build(int tag, int bits, int channels, int frames, int rate, long seed) {
        float[][] expected = new float[channels][];
        byte[] payload;
        if (tag == 3) {
            float[][] ch = new float[channels][];
            for (int c = 0; c < channels; c++) {
                ch[c] = Samples.noise(seed + c, frames);
                expected[c] = ch[c];
            }
            payload = Samples.packFloats(Samples.interleave(ch));
        } else {
            int[][] ints = new int[channels][];
            for (int c = 0; c < channels; c++) {
                ints[c] = Samples.intNoise(seed + c, frames, bits);
                expected[c] = new float[frames];
                for (int i = 0; i < frames; i++) {
                    if (bits == 8) {
                        ints[c][i] += 128; // 0..255 unsigned
                        expected[c][i] = (ints[c][i] - 128) / 128f;
                    } else {
                        expected[c][i] = Samples.toFloat(bits, ints[c][i]);
                    }
                }
            }
            int[] inter = Samples.interleave(ints);
            payload = bits == 8 ? Samples.pack8(inter) : Samples.packSigned(bits, inter);
        }
        byte[] file = RiffBuilder.wave().fmt(tag, channels, rate, bits).data(payload).build();
        return new TestWav(file, expected, rate);
    }
}
