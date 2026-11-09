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

/** Sample encodings supported for WAV files. */
public enum WavEncoding {
    /** 8-bit unsigned PCM. */
    PCM_8(8, false),
    /** 16-bit signed little-endian PCM. */
    PCM_16(16, false),
    /** 24-bit signed little-endian PCM. */
    PCM_24(24, false),
    /** 32-bit signed little-endian PCM (not lossless through float32). */
    PCM_32(32, false),
    /** 32-bit IEEE float. */
    FLOAT_32(32, true);

    private final int bitsPerSample;
    private final boolean isFloat;

    WavEncoding(int bitsPerSample, boolean isFloat) {
        this.bitsPerSample = bitsPerSample;
        this.isFloat = isFloat;
    }

    /** @return the container width of one sample in bits */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /** @return true for IEEE float encodings */
    public boolean isFloat() {
        return isFloat;
    }
}
