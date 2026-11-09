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

import java.io.IOException;

/**
 * Thrown for audio files (or requests) that are unsupported or malformed. The message states
 * what was found and what is supported. Real I/O failures use a plain {@link IOException}.
 */
public final class UnsupportedAudioFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what was found and what is supported
     */
    public UnsupportedAudioFormatException(String message) {
        super(message);
    }
}
