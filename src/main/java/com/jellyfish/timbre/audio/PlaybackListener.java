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
 * Receives playback events. All methods have empty defaults.
 *
 * <p><b>Threading:</b> {@link #stateChanged} is called on the thread that made the change: the
 * caller's thread for play, pause, resume and stop, the audio thread when a run ends by itself.
 * {@link #finished()} and {@link #failed(Throwable)} are called on the audio thread. Implementations
 * must return quickly and must not call back into blocking engine methods; a JavaFX UI should hand
 * over with {@code Platform.runLater}.
 */
public interface PlaybackListener {

    /**
     * The transport state changed.
     *
     * @param oldState previous state
     * @param newState new state
     */
    default void stateChanged(PlaybackEngine.State oldState, PlaybackEngine.State newState) {
    }

    /** A run reached the end of the project, drained and stopped (not called for {@code stop()}). */
    default void finished() {
    }

    /**
     * A run ended because of an error; the engine is STOPPED and the sink closed.
     *
     * @param error the cause
     */
    default void failed(Throwable error) {
    }
}
