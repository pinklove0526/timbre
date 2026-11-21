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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Track;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Five tracks t0..t4; expectations are written as lists of names. */
class TrackSelectionTest {

    private List<Track> all;
    private TrackSelection sel;

    @BeforeEach
    void setUp() {
        all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            all.add(new Track("t" + i, 44100, 1));
        }
        sel = new TrackSelection();
    }

    private List<String> names() {
        return sel.selected(all).stream().map(Track::name).toList();
    }

    @Test
    void plainClickSelectsOnlyThatTrack() {
        sel.click(all, 3, false, false);
        assertEquals(List.of("t3"), names());
        sel.click(all, 1, false, false);
        assertEquals(List.of("t1"), names());
        assertEquals(Optional.of(all.get(1)), sel.anchor());
    }

    @Test
    void toggleAddsAndRemovesAndMovesTheAnchor() {
        sel.click(all, 1, false, false);
        sel.click(all, 4, true, false);
        assertEquals(List.of("t1", "t4"), names());
        sel.click(all, 1, true, false);
        assertEquals(List.of("t4"), names());
        assertEquals(Optional.of(all.get(1)), sel.anchor(), "a toggle sets the anchor even when it deselects");
        sel.click(all, 4, true, false);
        assertTrue(sel.isEmpty());
    }

    @Test
    void rangeInBothDirectionsKeepsTheAnchor() {
        sel.click(all, 1, false, false);
        sel.click(all, 3, false, true);
        assertEquals(List.of("t1", "t2", "t3"), names());
        sel.click(all, 0, false, true); // from the same anchor t1, now backwards
        assertEquals(List.of("t0", "t1"), names());
        assertEquals(Optional.of(all.get(1)), sel.anchor());
    }

    @Test
    void toggleRangeAddsToTheSelection() {
        sel.click(all, 0, false, false);
        sel.click(all, 3, true, false); // t0, t3; anchor t3
        sel.click(all, 4, true, true); // add t3..t4
        assertEquals(List.of("t0", "t3", "t4"), names());
        sel.click(all, 2, false, true); // a plain range from t3 replaces the selection
        assertEquals(List.of("t2", "t3"), names());
    }

    @Test
    void aRangeWithoutAnAnchorIsAPlainClick() {
        sel.click(all, 2, false, true);
        assertEquals(List.of("t2"), names());
        assertEquals(Optional.of(all.get(2)), sel.anchor());
        TrackSelection other = new TrackSelection();
        other.click(all, 3, true, true);
        assertEquals(List.of("t3"), other.selected(all).stream().map(Track::name).toList());
    }

    @Test
    void retainDropsRemovedTracksAndTheirAnchor() {
        sel.click(all, 1, false, false);
        sel.click(all, 3, false, true); // t1..t3, anchor t1
        Track gone = all.remove(1); // t1 removed
        sel.retain(all);
        assertEquals(List.of("t2", "t3"), names());
        assertTrue(sel.anchor().isEmpty());
        assertFalse(sel.isSelected(gone));
        sel.click(all, 0, false, true); // no anchor any more: plain
        assertEquals(List.of("t0"), names());
    }

    @Test
    void targetsAreTheSelectionOrAllTracks() {
        assertEquals(all, sel.targets(all), "none selected: all tracks");
        sel.click(all, 4, false, false);
        sel.click(all, 0, true, false);
        assertEquals(List.of(all.get(0), all.get(4)), sel.targets(all), "in project order, not click order");
        sel.clear();
        assertEquals(all, sel.targets(all));
        assertTrue(sel.anchor().isEmpty());
    }

    @Test
    void identityNotIndex() {
        sel.click(all, 2, false, false); // t2
        all.add(0, new Track("new", 44100, 1)); // t2 is now at index 3
        assertEquals(List.of("t2"), names());
        sel.click(all, 4, false, true); // range from the anchor t2 (index 3) to t3 (index 4)
        assertEquals(List.of("t2", "t3"), names());
        Track twin = new Track("t2", 44100, 1); // same name, different object
        assertFalse(sel.isSelected(twin));
    }

    @Test
    void badIndexIsRejected() {
        assertThrows(IndexOutOfBoundsException.class, () -> sel.click(all, 5, false, false));
        assertThrows(IndexOutOfBoundsException.class, () -> sel.click(all, -1, false, false));
    }
}
