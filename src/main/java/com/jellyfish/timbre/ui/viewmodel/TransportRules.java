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
package com.jellyfish.timbre.ui.viewmodel;

import com.jellyfish.timbre.audio.PlaybackEngine.State;
import java.util.Objects;

/**
 * Which transport controls are available in each engine state and what Space and P do.
 *
 * <p>STOPPED: only Play is enabled, the selection is free, Space plays, P does nothing. PLAYING: Pause and
 * Stop are enabled, the selection is locked, Space stops, P pauses. PAUSED: like PLAYING, but the pause
 * button reads "Resume" and P resumes. Edits (cut, paste, delete, silence, trim, split, undo, redo, import,
 * remove track) are locked while PLAYING or PAUSED; copy is allowed in every state.
 *
 * <p><b>Threading:</b> stateless; safe to call from any thread.
 */
public final class TransportRules {

    /** What a key press does. */
    public enum Action {
        /** Start playback. */
        PLAY,
        /** Stop playback. */
        STOP,
        /** Pause playback. */
        PAUSE,
        /** Resume playback. */
        RESUME,
        /** Nothing. */
        NONE
    }

    private TransportRules() {
    }

    /**
     * @param state the engine state
     * @return true if the Play button is enabled (only when STOPPED)
     */
    public static boolean playEnabled(State state) {
        return Objects.requireNonNull(state, "state") == State.STOPPED;
    }

    /**
     * @param state the engine state
     * @return true if the Pause button is enabled (PLAYING or PAUSED)
     */
    public static boolean pauseEnabled(State state) {
        return Objects.requireNonNull(state, "state") != State.STOPPED;
    }

    /**
     * @param state the engine state
     * @return true if the Stop button is enabled (PLAYING or PAUSED)
     */
    public static boolean stopEnabled(State state) {
        return Objects.requireNonNull(state, "state") != State.STOPPED;
    }

    /**
     * @param state the engine state
     * @return true if the pause button reads "Resume" (only when PAUSED)
     */
    public static boolean pauseLabelIsResume(State state) {
        return Objects.requireNonNull(state, "state") == State.PAUSED;
    }

    /**
     * @param state the engine state
     * @return true if mouse and keyboard selection changes are ignored (PLAYING or PAUSED)
     */
    public static boolean selectionLocked(State state) {
        return Objects.requireNonNull(state, "state") != State.STOPPED;
    }

    /**
     * @param state the engine state
     * @return true if edits that change the project are ignored (PLAYING or PAUSED)
     */
    public static boolean editsLocked(State state) {
        return Objects.requireNonNull(state, "state") != State.STOPPED;
    }

    /**
     * @param state the engine state
     * @return true: copy changes only the clipboard and is allowed in every state
     */
    public static boolean copyAllowed(State state) {
        Objects.requireNonNull(state, "state");
        return true;
    }

    /**
     * @param state the engine state
     * @return what Space does: PLAY when STOPPED, STOP otherwise
     */
    public static Action spaceAction(State state) {
        return Objects.requireNonNull(state, "state") == State.STOPPED ? Action.PLAY : Action.STOP;
    }

    /**
     * @param state the engine state
     * @return what P does: NONE when STOPPED, PAUSE when PLAYING, RESUME when PAUSED
     */
    public static Action pauseAction(State state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case STOPPED -> Action.NONE;
            case PLAYING -> Action.PAUSE;
            case PAUSED -> Action.RESUME;
        };
    }
}
