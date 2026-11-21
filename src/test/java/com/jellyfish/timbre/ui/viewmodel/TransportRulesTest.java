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

import static com.jellyfish.timbre.audio.PlaybackEngine.State.PAUSED;
import static com.jellyfish.timbre.audio.PlaybackEngine.State.PLAYING;
import static com.jellyfish.timbre.audio.PlaybackEngine.State.STOPPED;
import static com.jellyfish.timbre.ui.viewmodel.TransportRules.Action.NONE;
import static com.jellyfish.timbre.ui.viewmodel.TransportRules.Action.PAUSE;
import static com.jellyfish.timbre.ui.viewmodel.TransportRules.Action.PLAY;
import static com.jellyfish.timbre.ui.viewmodel.TransportRules.Action.RESUME;
import static com.jellyfish.timbre.ui.viewmodel.TransportRules.Action.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The table, written by hand: state, play, pause, stop, "Resume" label, locked, Space, P. */
class TransportRulesTest {

    @Test
    void stopped() {
        assertEquals(true, TransportRules.playEnabled(STOPPED));
        assertEquals(false, TransportRules.pauseEnabled(STOPPED));
        assertEquals(false, TransportRules.stopEnabled(STOPPED));
        assertEquals(false, TransportRules.pauseLabelIsResume(STOPPED));
        assertEquals(false, TransportRules.selectionLocked(STOPPED));
        assertEquals(PLAY, TransportRules.spaceAction(STOPPED));
        assertEquals(NONE, TransportRules.pauseAction(STOPPED));
    }

    @Test
    void playing() {
        assertEquals(false, TransportRules.playEnabled(PLAYING));
        assertEquals(true, TransportRules.pauseEnabled(PLAYING));
        assertEquals(true, TransportRules.stopEnabled(PLAYING));
        assertEquals(false, TransportRules.pauseLabelIsResume(PLAYING));
        assertEquals(true, TransportRules.selectionLocked(PLAYING));
        assertEquals(STOP, TransportRules.spaceAction(PLAYING));
        assertEquals(PAUSE, TransportRules.pauseAction(PLAYING));
    }

    @Test
    void paused() {
        assertEquals(false, TransportRules.playEnabled(PAUSED));
        assertEquals(true, TransportRules.pauseEnabled(PAUSED));
        assertEquals(true, TransportRules.stopEnabled(PAUSED));
        assertEquals(true, TransportRules.pauseLabelIsResume(PAUSED));
        assertEquals(true, TransportRules.selectionLocked(PAUSED));
        assertEquals(STOP, TransportRules.spaceAction(PAUSED));
        assertEquals(RESUME, TransportRules.pauseAction(PAUSED));
    }

    @Test
    void nullStateIsRejected() {
        assertThrows(NullPointerException.class, () -> TransportRules.playEnabled(null));
        assertThrows(NullPointerException.class, () -> TransportRules.spaceAction(null));
        assertThrows(NullPointerException.class, () -> TransportRules.pauseAction(null));
    }
}
