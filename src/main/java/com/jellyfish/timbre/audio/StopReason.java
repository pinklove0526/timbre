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
 * Why a recording ended.
 *
 * <p><b>Threading:</b> an immutable enum.
 */
public enum StopReason {
    /** A stop was requested ({@link Recorder#stop()}, {@link Recorder#stopAndWait} or {@link Recorder#close()}). */
    USER,
    /** The length cap was reached; the recording holds exactly the cap. */
    LENGTH_LIMIT,
    /** Allocating the next chunk failed; the recording holds everything stored before. */
    OUT_OF_MEMORY,
    /** The capture device failed; the recording holds the frames read before the failure. */
    ERROR
}
