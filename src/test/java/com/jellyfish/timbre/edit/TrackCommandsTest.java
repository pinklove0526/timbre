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
package com.jellyfish.timbre.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleRateMismatchException;
import com.jellyfish.timbre.model.Track;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Hand-written literals: tracks A, B, C, D (44100 Hz) in that order. */
class TrackCommandsTest {

    private Project project;
    private Track a;
    private Track b;
    private Track c;
    private Track d;
    private History history;

    private static Track track(String name, int rate, int id) {
        Track t = new Track(name, rate, 1);
        t.addClip(new Clip(Oracle.source(id, 1, 10, rate), 0, 10, 0));
        return t;
    }

    @BeforeEach
    void setUp() {
        project = new Project();
        a = track("A", 44100, 1);
        b = track("B", 44100, 2);
        c = track("C", 44100, 3);
        d = track("D", 44100, 4);
        for (Track t : List.of(a, b, c, d)) {
            project.addTrack(t);
        }
        history = new History();
    }

    private List<String> names() {
        return project.tracks().stream().map(Track::name).toList();
    }

    @Test
    void addAppendsAndUndoRemovesTheSameObject() {
        Track e = track("E", 44100, 5);
        history.execute(new AddTrackCommand(project, e));
        assertEquals(List.of("A", "B", "C", "D", "E"), names());
        assertEquals(Optional.of("Import"), history.undoName());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        history.redo();
        assertSame(e, project.tracks().get(4), "redo adds the same Track object");
    }

    @Test
    void removeFirstMiddleLastAndAll() {
        history.execute(RemoveTrackCommand.forTracks(project, List.of(a)));
        assertEquals(List.of("B", "C", "D"), names());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        history.execute(RemoveTrackCommand.forTracks(project, List.of(c)));
        assertEquals(List.of("A", "B", "D"), names());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        history.execute(RemoveTrackCommand.forTracks(project, List.of(d)));
        assertEquals(List.of("A", "B", "C"), names());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        history.execute(RemoveTrackCommand.forTracks(project, List.of(c, a, d, b)));
        assertEquals(List.of(), names());
        assertEquals(Optional.of("Remove Track"), history.undoName());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        assertSame(a, project.tracks().get(0));
        assertSame(d, project.tracks().get(3));
    }

    @Test
    void severalRemovalsAreOneEntryAndComeBackInTheirOriginalPlaces() {
        history.execute(RemoveTrackCommand.forTracks(project, List.of(d, b))); // given out of order
        assertEquals(List.of("A", "C"), names());
        assertTrue(history.canUndo());
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
        assertTrue(!history.canUndo(), "one history entry");
        history.redo();
        assertEquals(List.of("A", "C"), names());
        history.execute(RemoveTrackCommand.forTracks(project, List.of(a, c)));
        assertEquals(List.of(), names());
        history.undo();
        history.undo();
        assertEquals(List.of("A", "B", "C", "D"), names());
    }

    @Test
    void theSampleRateIsFreeAgainWhenTheProjectIsEmpty() {
        Track odd = track("odd", 48000, 9);
        assertThrows(SampleRateMismatchException.class, () -> history.execute(new AddTrackCommand(project, odd)));
        assertEquals(List.of("A", "B", "C", "D"), names(), "a refused import changes nothing");
        assertTrue(history.undoName().isEmpty(), "and records nothing");
        history.execute(RemoveTrackCommand.forTracks(project, List.of(a, b, c, d)));
        assertEquals(OptionalInt.empty(), project.sampleRate());
        history.execute(new AddTrackCommand(project, odd));
        assertEquals(OptionalInt.of(48000), project.sampleRate());
        assertThrows(SampleRateMismatchException.class,
                () -> project.addTrack(track("x", 44100, 10)), "44100 is refused while the 48 kHz track is there");
        history.undo();
        history.undo();
        assertEquals(OptionalInt.of(44100), project.sampleRate(), "the original rate is back");
        assertEquals(List.of("A", "B", "C", "D"), names());
    }

    @Test
    void projectInsertAndRemoveRules() {
        assertEquals(2, project.removeTrack(c));
        project.insertTrack(0, c);
        assertEquals(List.of("C", "A", "B", "D"), names());
        assertEquals(3, project.indexOf(d));
        assertEquals(-1, project.indexOf(track("z", 44100, 11)));
        assertThrows(IllegalArgumentException.class, () -> project.insertTrack(1, a), "already in the project");
        assertThrows(IndexOutOfBoundsException.class, () -> project.insertTrack(5, track("y", 44100, 12)));
        assertThrows(IllegalArgumentException.class, () -> project.removeTrack(track("w", 44100, 13)));
        assertThrows(IllegalArgumentException.class, () -> RemoveTrackCommand.forTracks(project, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RemoveTrackCommand.forTracks(project, List.of(a, a)));
        assertThrows(IllegalStateException.class, () -> new RemoveTrackCommand(project, a).revert());
    }
}
