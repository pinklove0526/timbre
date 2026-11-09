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

import com.jellyfish.timbre.model.MemorySampleSource;
import java.util.Objects;

/**
 * The result of reading a WAV file.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param source   the decoded audio
 * @param encoding the file's original encoding, so export can default to it
 */
public record WavFile(MemorySampleSource source, WavEncoding encoding) {

    /** Validates that both components are non-null. */
    public WavFile {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(encoding, "encoding");
    }
}
