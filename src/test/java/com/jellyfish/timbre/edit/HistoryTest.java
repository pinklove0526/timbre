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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HistoryTest {

    /** A command that appends "+name" on apply and "-name" on revert to a shared log. */
    private static final class Logged implements Command {
        private final String name;
        private final List<String> log;
        private final boolean failOnApply;

        Logged(String name, List<String> log) {
            this(name, log, false);
        }

        Logged(String name, List<String> log, boolean failOnApply) {
            this.name = name;
            this.log = log;
            this.failOnApply = failOnApply;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void apply() {
            if (failOnApply) {
                throw new IllegalStateException("boom in " + name);
            }
            log.add("+" + name);
        }

        @Override
        public void revert() {
            log.add("-" + name);
        }
    }

    @Test
    void undoAndRedoOrderAndNames() {
        List<String> log = new ArrayList<>();
        History h = new History();
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertEquals(Optional.empty(), h.undoName());
        h.execute(new Logged("a", log));
        h.execute(new Logged("b", log));
        h.execute(new Logged("c", log));
        assertEquals(Optional.of("c"), h.undoName());
        assertTrue(h.undo());
        assertTrue(h.undo());
        assertEquals(Optional.of("a"), h.undoName());
        assertEquals(Optional.of("b"), h.redoName());
        assertTrue(h.redo());
        assertEquals(List.of("+a", "+b", "+c", "-c", "-b", "+b"), log);
        assertTrue(h.canUndo());
        assertTrue(h.canRedo());
        assertTrue(h.undo()); // b
        assertTrue(h.undo()); // a
        assertFalse(h.undo(), "nothing left to undo");
        assertFalse(h.canUndo());
        assertEquals(Optional.of("a"), h.redoName());
    }

    @Test
    void aNewCommandClearsTheRedoStack() {
        List<String> log = new ArrayList<>();
        History h = new History();
        h.execute(new Logged("a", log));
        h.execute(new Logged("b", log));
        h.undo();
        assertTrue(h.canRedo());
        h.execute(new Logged("x", log));
        assertFalse(h.canRedo());
        assertFalse(h.redo());
        assertEquals(Optional.of("x"), h.undoName());
        h.undo();
        assertEquals(Optional.of("a"), h.undoName(), "b is gone for good");
        assertEquals(List.of("+a", "+b", "-b", "+x", "-x"), log);
    }

    @Test
    void theModifiedFlagFollowsTheSavePoint() {
        List<String> log = new ArrayList<>();
        History h = new History();
        assertFalse(h.isModified());
        h.execute(new Logged("a", log));
        assertTrue(h.isModified());
        h.markSaved();
        assertFalse(h.isModified());
        h.execute(new Logged("b", log));
        assertTrue(h.isModified());
        h.undo();
        assertFalse(h.isModified(), "back at the save point");
        h.undo();
        assertTrue(h.isModified(), "before the save point");
        h.redo();
        assertFalse(h.isModified());
        // save after b, undo b, then a new command discards b and the save point with it
        h.redo();
        h.markSaved();
        h.undo();
        assertTrue(h.isModified());
        h.execute(new Logged("c", log));
        assertTrue(h.isModified());
        h.undo();
        assertTrue(h.isModified(), "the save point is lost: still modified at the old position");
        h.undo();
        assertTrue(h.isModified());
        h.redo();
        h.redo();
        assertTrue(h.isModified());
        h.markSaved();
        assertFalse(h.isModified(), "a new save point");
    }

    @Test
    void listenersAreCalledExactlyOncePerStateChange() {
        List<String> log = new ArrayList<>();
        History h = new History();
        AtomicInteger calls = new AtomicInteger();
        HistoryListener l = calls::incrementAndGet;
        h.addListener(l);
        h.execute(new Logged("a", log));
        assertEquals(1, calls.get());
        h.undo();
        assertEquals(2, calls.get());
        h.undo(); // nothing to undo
        assertEquals(2, calls.get());
        h.redo();
        assertEquals(3, calls.get());
        h.redo(); // nothing to redo
        assertEquals(3, calls.get());
        h.markSaved();
        assertEquals(4, calls.get());
        h.markSaved(); // already saved here
        assertEquals(4, calls.get());
        h.clear();
        assertEquals(5, calls.get());
        h.clear(); // already empty and saved
        assertEquals(5, calls.get());
        h.removeListener(l);
        h.execute(new Logged("b", log));
        assertEquals(5, calls.get());
    }

    @Test
    void aFailingCommandIsNotRecordedAndTheCompoundIsAllOrNothing() {
        List<String> log = new ArrayList<>();
        History h = new History();
        AtomicInteger calls = new AtomicInteger();
        h.addListener(calls::incrementAndGet);
        h.execute(new Logged("before", log));
        CompoundCommand bad = new CompoundCommand("Cut", List.of(new Logged("x", log), new Logged("y", log),
                new Logged("z", log, true), new Logged("w", log)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> h.execute(bad));
        assertEquals("boom in z", e.getMessage());
        assertEquals(List.of("+before", "+x", "+y", "-y", "-x"), log, "x and y reverted in reverse order");
        assertEquals(Optional.of("before"), h.undoName(), "the history is unchanged");
        assertFalse(h.canRedo());
        assertEquals(1, calls.get(), "no notification for the failed execute");
    }

    @Test
    void aCompoundRevertsInReverseOrder() {
        List<String> log = new ArrayList<>();
        History h = new History();
        h.execute(new CompoundCommand("Paste", List.of(new Logged("p", log), new Logged("q", log),
                new Logged("r", log))));
        assertEquals(Optional.of("Paste"), h.undoName());
        h.undo();
        h.redo();
        assertEquals(List.of("+p", "+q", "+r", "-r", "-q", "-p", "+p", "+q", "+r"), log);
    }

    @Test
    void clearForgetsEverythingAndCountsAsSaved() {
        List<String> log = new ArrayList<>();
        History h = new History();
        h.execute(new Logged("a", log));
        h.execute(new Logged("b", log));
        h.undo();
        h.clear();
        assertFalse(h.canUndo());
        assertFalse(h.canRedo());
        assertFalse(h.isModified());
        assertEquals(List.of("+a", "+b", "-b"), log, "clear reverts nothing");
    }
}
