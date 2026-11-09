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
package com.jellyfish.timbre.model;

/** Shared argument validation for {@link SampleSource#read} implementations. */
final class ReadChecks {

    private ReadChecks() {
    }

    /**
     * Validates a read request. Allocation-free on the success path.
     *
     * @throws IndexOutOfBoundsException if the frame range is outside the source
     * @throws IllegalArgumentException  if the destination shape is wrong
     */
    static void check(long frameCount, int channelCount, long startFrame, int frames,
                      float[][] dest, int destOffset) {
        if (startFrame < 0 || frames < 0 || startFrame > frameCount - frames) {
            throw new IndexOutOfBoundsException("Read range [" + startFrame + ", " + startFrame
                    + "+" + frames + ") is outside [0, " + frameCount + "]");
        }
        if (dest == null) {
            throw new IllegalArgumentException("dest is null");
        }
        if (destOffset < 0) {
            throw new IllegalArgumentException("destOffset is negative: " + destOffset);
        }
        if (dest.length < channelCount) {
            throw new IllegalArgumentException("dest has " + dest.length + " channel arrays, need "
                    + channelCount);
        }
        for (int c = 0; c < channelCount; c++) {
            float[] ch = dest[c];
            if (ch == null || (long) ch.length < (long) destOffset + frames) {
                throw new IllegalArgumentException("dest[" + c + "] is null or too short for offset "
                        + destOffset + " + " + frames + " frames");
            }
        }
    }
}
