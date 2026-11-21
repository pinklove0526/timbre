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

import com.jellyfish.timbre.model.Selection;
import org.junit.jupiter.api.Test;

class SelectionControllerTest {

    /** frameAtX(x) = 1000 + floor(100 * x). */
    private static final TimeAxis AXIS = new TimeAxis(44100, 100, 1000);

    private static SelectionController controller(long projectFrames) {
        return new SelectionController(AXIS, projectFrames);
    }

    @Test
    void startsWithTheCursorAtZero() {
        SelectionController c = controller(10_000);
        assertEquals(Selection.cursor(0), c.selection());
        assertEquals(0, c.anchor());
        assertFalse(c.isPressed());
    }

    @Test
    void aClickSetsTheCursor() {
        SelectionController c = controller(10_000);
        c.press(10, false);
        assertTrue(c.isPressed());
        assertEquals(Selection.cursor(2000), c.selection());
        c.release(10);
        assertFalse(c.isPressed());
        assertEquals(Selection.cursor(2000), c.selection());
        assertEquals(2000, c.anchor());
    }

    @Test
    void movementBelowThreeAndAtThreePixels() {
        SelectionController c = controller(10_000);
        c.press(10, false);
        c.drag(12.9);
        assertEquals(Selection.cursor(2000), c.selection(), "2.9 px is still a click");
        c.release(12.9);
        assertEquals(Selection.cursor(2000), c.selection());

        c.press(10, false);
        c.drag(13);
        assertEquals(new Selection(2000, 2300), c.selection(), "3 px is a drag");
        c.release(13);
        assertEquals(new Selection(2000, 2300), c.selection());

        c.press(10, false);
        c.drag(7);
        assertEquals(new Selection(1700, 2000), c.selection(), "3 px to the left is a drag too");
    }

    @Test
    void backwardDragIsOrderedAndKeepsTheAnchor() {
        SelectionController c = controller(10_000);
        c.press(50, false);
        c.drag(20);
        assertEquals(new Selection(3000, 6000), c.selection());
        assertEquals(6000, c.anchor());
        c.drag(80);
        assertEquals(new Selection(6000, 9000), c.selection(), "crossing the anchor flips the range");
        c.release(80);
        assertEquals(new Selection(6000, 9000), c.selection());
    }

    @Test
    void draggingBackToTheAnchorStaysADragAndEndsEmpty() {
        SelectionController c = controller(10_000);
        c.press(10, false);
        c.drag(40);
        c.drag(10);
        assertTrue(c.selection().isEmpty());
        c.release(10);
        assertEquals(Selection.cursor(2000), c.selection());
    }

    @Test
    void pointerPositionsAreClampedToTheProject() {
        SelectionController c = controller(5000);
        c.press(-20, false); // frame 1000 - 2000 clamps to 0
        assertEquals(0, c.anchor());
        c.drag(1000); // frame 101000 clamps to 5000
        assertEquals(new Selection(0, 5000), c.selection());
        c.release(1000);
        c.press(1000, false);
        assertEquals(5000, c.anchor());
        assertEquals(Selection.cursor(5000), c.selection());
    }

    @Test
    void shiftPressExtendsFromTheExistingAnchor() {
        SelectionController c = controller(10_000);
        c.press(10, false);
        c.release(10);
        c.press(30, true);
        assertEquals(new Selection(2000, 4000), c.selection());
        assertEquals(2000, c.anchor(), "a shift-press keeps the anchor");
        c.drag(5);
        assertEquals(new Selection(1500, 2000), c.selection());
        c.release(5);
        assertEquals(new Selection(1500, 2000), c.selection());
        c.press(40, true);
        c.release(40);
        assertEquals(new Selection(2000, 5000), c.selection(), "the second shift-click uses the same anchor");
        c.press(20, false);
        assertEquals(Selection.cursor(3000), c.selection(), "a plain press starts over");
    }

    @Test
    void shiftPressWithoutAnEarlierClickExtendsFromFrameZero() {
        SelectionController c = controller(10_000);
        c.press(20, true);
        assertEquals(new Selection(0, 3000), c.selection());
    }

    @Test
    void dragOrReleaseWithoutAPressAreIgnored() {
        SelectionController c = controller(10_000);
        c.drag(50);
        c.release(50);
        assertEquals(Selection.cursor(0), c.selection());
    }

    @Test
    void axisChangeDuringAGestureKeepsTheAnchorInFrames() {
        SelectionController c = controller(100_000);
        c.press(10, false); // anchor 2000
        c.setAxis(new TimeAxis(44100, 10, 0)); // frameAtX(x) = floor(10 x)
        c.drag(500);
        assertEquals(new Selection(2000, 5000), c.selection());
    }

    @Test
    void shrinkingTheProjectClampsTheSelection() {
        SelectionController c = controller(10_000);
        c.press(10, false);
        c.drag(60);
        c.release(60);
        assertEquals(new Selection(2000, 7000), c.selection());
        c.setProjectFrames(4000);
        assertEquals(new Selection(2000, 4000), c.selection());
        c.setProjectFrames(1000);
        assertEquals(new Selection(1000, 1000), c.selection());
        assertEquals(1000, c.anchor());
        assertThrows(IllegalArgumentException.class, () -> c.setProjectFrames(-1));
    }

    @Test
    void emptyProjectKeepsEverythingAtZero() {
        SelectionController c = controller(0);
        c.press(50, false);
        c.drag(90);
        c.release(90);
        assertEquals(Selection.cursor(0), c.selection());
    }

    @Test
    void setSelectionFromOutsideMovesTheAnchorToItsStartAndClamps() {
        SelectionController c = new SelectionController(new TimeAxis(44100, 100.0, 0), 50_000);
        c.press(100, false); // anchor 10000
        c.setSelection(new com.jellyfish.timbre.model.Selection(20_000, 80_000)); // end beyond the project
        assertEquals(new com.jellyfish.timbre.model.Selection(20_000, 50_000), c.selection());
        assertEquals(20_000, c.anchor());
        assertEquals(false, c.isPressed());
        c.press(400, true); // extends from the anchor 20000 to frame 40000
        assertEquals(new com.jellyfish.timbre.model.Selection(20_000, 40_000), c.selection());
    }
}
