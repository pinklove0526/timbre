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
 * Creates a fresh, unopened {@link CaptureSource} for each recording (sources are single-use).
 *
 * <p><b>Threading:</b> called on the thread that calls {@link Recorder#start}.
 */
@FunctionalInterface
public interface CaptureSourceFactory {

    /** @return a new, unopened source; never null */
    CaptureSource create();
}
