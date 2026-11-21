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
 * Receives recorder events. Both methods have empty defaults.
 *
 * <p><b>Threading:</b> both methods are called on the capture thread "timbre-capture". Implementations must
 * return quickly and must not block on the recorder (no {@link Recorder#stopAndWait} from here); a JavaFX UI
 * hands over with {@code Platform.runLater}. A listener that throws a {@link RuntimeException} is ignored and
 * does not stop the recording.
 */
public interface RecorderListener {

    /**
     * The state changed: RECORDING when the capture thread begins, IDLE after the source is closed and just
     * before {@link #finished}.
     *
     * @param state the new state
     */
    default void stateChanged(RecorderState state) {
    }

    /**
     * A recording ended; called exactly once per recording, after {@code stateChanged(IDLE)}.
     *
     * @param result the outcome
     */
    default void finished(RecordingResult result) {
    }
}
