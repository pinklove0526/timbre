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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelectionTest {

    @Test
    void invariantsAreEnforced() {
        assertThrows(IllegalArgumentException.class, () -> new Selection(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new Selection(6, 5));
        assertThrows(IllegalArgumentException.class, () -> Selection.cursor(-3));
        Selection s = new Selection(7, 19);
        assertEquals(7, s.startFrame());
        assertEquals(19, s.endFrame());
        assertEquals(12, s.length());
        assertFalse(s.isEmpty());
    }

    @Test
    void cursorIsEmpty() {
        Selection c = Selection.cursor(42);
        assertTrue(c.isEmpty());
        assertEquals(0, c.length());
        assertEquals(42, c.startFrame());
        assertEquals(42, c.endFrame());
        assertEquals(new Selection(0, 0), Selection.cursor(0));
    }

    @Test
    void betweenOrdersTheTwoFrames() {
        assertEquals(new Selection(3, 90), Selection.between(90, 3));
        assertEquals(new Selection(3, 90), Selection.between(3, 90));
        assertEquals(new Selection(5, 5), Selection.between(5, 5));
        assertThrows(IllegalArgumentException.class, () -> Selection.between(-1, 4));
    }

    @Test
    void clampedToLimitsBothEnds() {
        Selection s = new Selection(10, 50);
        assertSame(s, s.clampedTo(50));
        assertSame(s, s.clampedTo(1000));
        assertEquals(new Selection(10, 30), s.clampedTo(30));
        assertEquals(new Selection(20, 20), new Selection(20, 50).clampedTo(20));
        assertEquals(new Selection(8, 8), new Selection(10, 50).clampedTo(8));
        assertEquals(new Selection(0, 0), s.clampedTo(0));
        assertThrows(IllegalArgumentException.class, () -> s.clampedTo(-1));
    }
}
