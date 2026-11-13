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
 * The audio output device could not be opened or used. The message names the requested sample
 * rate and format and the underlying problem.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 */
public final class AudioDeviceException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed, including the requested rate and format
     */
    public AudioDeviceException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param message what failed, including the requested rate and format
     * @param cause   the underlying error
     */
    public AudioDeviceException(String message, Throwable cause) {
        super(message, cause);
    }
}
