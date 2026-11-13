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
 * Immutable snapshot of engine counters for the current or last run.
 *
 * <p><b>Threading:</b> immutable; safe to share between threads.
 *
 * @param blocksRendered     render blocks mixed and converted
 * @param shortWrites        write calls that accepted fewer bytes than offered
 * @param maxRenderNanos     longest time to render one block
 * @param suspectedUnderruns gaps between one write call returning and the next starting that exceeded
 *                           the device buffer duration (wall clock, pauses excluded)
 */
public record EngineStats(long blocksRendered, long shortWrites, long maxRenderNanos, long suspectedUnderruns) {
}
