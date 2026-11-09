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

/** Constants of the RIFF/WAVE format shared by the reader and the writer. */
final class WavConstants {

    static final int TAG_PCM = 1;
    static final int TAG_FLOAT = 3;
    static final int TAG_EXTENSIBLE = 0xFFFE;

    /** Bytes 4..15 of the standard subformat GUID (the first four bytes hold the format tag). */
    static final byte[] SUBFORMAT_SUFFIX = {
        0x00, 0x00, 0x10, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xAA, 0x00, 0x38, (byte) 0x9B, 0x71
    };

    static final int MAX_CHANNELS = 64;

    private WavConstants() {
    }
}
