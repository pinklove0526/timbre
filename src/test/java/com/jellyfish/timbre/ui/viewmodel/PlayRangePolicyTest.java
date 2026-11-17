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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.ui.viewmodel.PlayRangePolicy.Range;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlayRangePolicyTest {

    @Test
    void aSelectionPlaysExactlyItself() {
        assertEquals(Optional.of(new Range(100, 700)), PlayRangePolicy.choose(new Selection(100, 700), 1000));
        assertEquals(Optional.of(new Range(0, 1000)), PlayRangePolicy.choose(new Selection(0, 1000), 1000));
        assertEquals(Optional.of(new Range(999, 1000)), PlayRangePolicy.choose(new Selection(999, 1000), 1000));
    }

    @Test
    void aSelectionPastTheEndIsClamped() {
        assertEquals(Optional.of(new Range(100, 1000)), PlayRangePolicy.choose(new Selection(100, 5000), 1000));
    }

    @Test
    void aSelectionEntirelyAfterTheEndCountsAsACursorThere() {
        assertEquals(Optional.of(new Range(0, 1000)), PlayRangePolicy.choose(new Selection(1200, 5000), 1000));
    }

    @Test
    void aCursorPlaysToTheEnd() {
        assertEquals(Optional.of(new Range(400, 1000)), PlayRangePolicy.choose(Selection.cursor(400), 1000));
        assertEquals(Optional.of(new Range(0, 1000)), PlayRangePolicy.choose(Selection.cursor(0), 1000));
        assertEquals(Optional.of(new Range(999, 1000)), PlayRangePolicy.choose(Selection.cursor(999), 1000));
    }

    @Test
    void aCursorAtOrAfterTheEndPlaysFromTheStart() {
        assertEquals(Optional.of(new Range(0, 1000)), PlayRangePolicy.choose(Selection.cursor(1000), 1000));
        assertEquals(Optional.of(new Range(0, 1000)), PlayRangePolicy.choose(Selection.cursor(4000), 1000));
    }

    @Test
    void anEmptyProjectPlaysNothing() {
        assertTrue(PlayRangePolicy.choose(Selection.cursor(0), 0).isEmpty());
        assertTrue(PlayRangePolicy.choose(new Selection(0, 50), 0).isEmpty());
    }

    @Test
    void argumentChecks() {
        assertThrows(IllegalArgumentException.class, () -> PlayRangePolicy.choose(Selection.cursor(0), -1));
        assertThrows(NullPointerException.class, () -> PlayRangePolicy.choose(null, 10));
        assertThrows(IllegalArgumentException.class, () -> new Range(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new Range(-1, 5));
    }
}
