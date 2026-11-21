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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.edit.Command;
import com.jellyfish.timbre.edit.History;
import com.jellyfish.timbre.model.Selection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SelectionTrailTest {

    private static final Selection A = new Selection(10, 30);
    private static final Selection B = Selection.cursor(10);
    private static final Selection C = new Selection(7, 41);
    private static final Selection D = Selection.cursor(0);

    @Test
    void undoGivesTheBeforeAndRedoTheAfterSelection() {
        SelectionTrail t = new SelectionTrail();
        assertEquals(Optional.empty(), t.undo());
        t.record(A, B);
        t.record(C, D);
        assertEquals(Optional.of(C), t.undo());
        assertEquals(Optional.of(A), t.undo());
        assertEquals(Optional.empty(), t.undo());
        assertEquals(Optional.of(B), t.redo());
        assertEquals(Optional.of(D), t.redo());
        assertEquals(Optional.empty(), t.redo());
        assertEquals(2, t.undoDepth());
    }

    @Test
    void aNewRecordDiscardsTheRedoPart() {
        SelectionTrail t = new SelectionTrail();
        t.record(A, B);
        t.record(C, D);
        t.undo();
        assertEquals(1, t.redoDepth());
        t.record(D, A);
        assertEquals(0, t.redoDepth());
        assertEquals(Optional.of(D), t.undo());
        assertEquals(Optional.of(A), t.undo());
        t.clear();
        assertEquals(0, t.undoDepth());
        assertEquals(0, t.redoDepth());
    }

    /** A command that does nothing; only the history's bookkeeping matters here. */
    private static final class Nop implements Command {
        @Override
        public String name() {
            return "nop";
        }

        @Override
        public void apply() {
            // nothing
        }

        @Override
        public void revert() {
            // nothing
        }
    }

    @Test
    void lockstepWithARealHistory() {
        for (int seed = 0; seed < 30; seed++) {
            Random rnd = new Random(seed);
            History h = new History();
            SelectionTrail t = new SelectionTrail();
            Deque<Selection[]> expectUndo = new ArrayDeque<>();
            Deque<Selection[]> expectRedo = new ArrayDeque<>();
            for (int i = 0; i < 200; i++) {
                int k = rnd.nextInt(5);
                if (k <= 1) {
                    Selection before = Selection.between(rnd.nextInt(1000), rnd.nextInt(1000));
                    Selection after = Selection.cursor(rnd.nextInt(1000));
                    h.execute(new Nop());
                    t.record(before, after);
                    expectUndo.push(new Selection[] {before, after});
                    expectRedo.clear();
                } else if (k <= 3) {
                    if (h.undo()) {
                        Selection[] e = expectUndo.pop();
                        assertEquals(Optional.of(e[0]), t.undo(), "seed " + seed + " step " + i);
                        expectRedo.push(e);
                    }
                } else if (h.redo()) {
                    Selection[] e = expectRedo.pop();
                    assertEquals(Optional.of(e[1]), t.redo(), "seed " + seed + " step " + i);
                    expectUndo.push(e);
                }
                assertEquals(h.canUndo(), t.undoDepth() > 0, "seed " + seed + " step " + i);
                assertEquals(h.canRedo(), t.redoDepth() > 0, "seed " + seed + " step " + i);
                assertEquals(expectUndo.size(), t.undoDepth());
                assertEquals(expectRedo.size(), t.redoDepth());
            }
            assertTrue(t.undoDepth() + t.redoDepth() > 0);
        }
    }
}
