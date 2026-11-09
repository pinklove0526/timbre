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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectTest {

    @Test
    void emptyProjectHasNoRate() {
        Project p = new Project();
        assertTrue(p.sampleRate().isEmpty());
        assertEquals(0, p.tracks().size());
    }

    @Test
    void firstTrackFixesTheRateAndSameRateIsAccepted() {
        Project p = new Project();
        p.addTrack(new Track("a", 44100, 2));
        assertEquals(44100, p.sampleRate().getAsInt());
        p.addTrack(new Track("b", 44100, 1)); // channel counts may differ
        assertEquals(2, p.tracks().size());
    }

    @Test
    void differentRateIsRejectedAndNamesBothRates() {
        Project p = new Project();
        p.addTrack(new Track("a", 44100, 2));
        SampleRateMismatchException e = assertThrows(SampleRateMismatchException.class,
                () -> p.addTrack(new Track("b", 48000, 2)));
        assertTrue(e.getMessage().contains("44100"), e.getMessage());
        assertTrue(e.getMessage().contains("48000"), e.getMessage());
        assertTrue(e instanceof IllegalArgumentException);
        assertEquals(1, p.tracks().size());
        assertEquals(44100, p.sampleRate().getAsInt());
    }

    @Test
    void rateStaysFixedAfterTracksExist() {
        Project p = new Project();
        p.addTrack(new Track("a", 8000, 1));
        assertThrows(SampleRateMismatchException.class, () -> p.addTrack(new Track("b", 96000, 1)));
        assertEquals(8000, p.sampleRate().getAsInt());
    }

    @Test
    void tracksViewIsUnmodifiable() {
        Project p = new Project();
        assertThrows(UnsupportedOperationException.class, () -> p.tracks().add(new Track("x", 8000, 1)));
        assertThrows(NullPointerException.class, () -> p.addTrack(null));
    }
}
