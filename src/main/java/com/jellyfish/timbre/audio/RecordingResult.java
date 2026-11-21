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

import com.jellyfish.timbre.model.ChunkedSampleSource;
import java.util.Objects;

/**
 * The outcome of one recording.
 *
 * <p><b>Threading:</b> an immutable record; the source is immutable and thread-safe.
 *
 * @param source              the recorded audio, or null when 0 frames were recorded
 * @param framesRecorded      frames in {@code source} (0 when it is null)
 * @param reason              why the recording ended
 * @param suspectedDropouts   suspected buffer overflows, one per episode (design D2'')
 * @param estimatedLostFrames the largest loss estimate of design D2'' (0 if none)
 * @param everClipped         the sustained-clipping warning was raised at least once
 * @param silentWarningRaised the silent-input warning was raised at least once
 * @param errorMessage        what failed when {@code reason} is {@link StopReason#ERROR}, else null
 */
public record RecordingResult(ChunkedSampleSource source, long framesRecorded, StopReason reason,
        int suspectedDropouts, long estimatedLostFrames, boolean everClipped, boolean silentWarningRaised,
        String errorMessage) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException     if {@code reason} is null, or ERROR comes without a message
     * @throws IllegalArgumentException if the frame count does not match the source, or a count is negative
     */
    public RecordingResult {
        Objects.requireNonNull(reason, "reason");
        long frames = source == null ? 0 : source.frameCount();
        if (framesRecorded != frames) {
            throw new IllegalArgumentException("framesRecorded " + framesRecorded + " != source frames " + frames);
        }
        if (source != null && frames == 0) {
            throw new IllegalArgumentException("an empty recording has no source");
        }
        if (suspectedDropouts < 0 || estimatedLostFrames < 0) {
            throw new IllegalArgumentException("negative dropout numbers");
        }
        if (reason == StopReason.ERROR) {
            Objects.requireNonNull(errorMessage, "errorMessage");
        } else if (errorMessage != null) {
            throw new IllegalArgumentException("errorMessage is only for ERROR");
        }
    }
}
