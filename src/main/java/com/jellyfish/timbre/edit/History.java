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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unlimited undo and redo of {@link Command}s, with a save point for the "modified" flag.
 *
 * <p>The position is the number of commands currently applied. {@link #isModified()} is true when the
 * position differs from the save point. A save point inside the redo part is lost when {@link #execute}
 * discards the redo part; from then on the history counts as modified until the next {@link #markSaved()}.
 *
 * <p>Listeners are called exactly once after each state change: a successful execute, undo or redo, a
 * {@link #markSaved()} that moves the save point, and a {@link #clear()} that changes anything. Calls that
 * change nothing (undo with nothing to undo, for example) notify nobody.
 *
 * <p><b>Threading:</b> single-threaded: use it on the JavaFX application thread only.
 */
public final class History {

    private static final int LOST = -1;

    private final List<Command> done = new ArrayList<>();
    private final List<Command> undone = new ArrayList<>(); // top of the redo stack is the last element
    private final List<HistoryListener> listeners = new CopyOnWriteArrayList<>();
    private int savePoint;

    /** Creates an empty, unmodified history. */
    public History() {
        // empty
    }

    /**
     * Applies a command and records it; the redo part is discarded. If {@code apply} throws, nothing is
     * recorded and the exception propagates.
     *
     * @param command the command
     */
    public void execute(Command command) {
        Objects.requireNonNull(command, "command");
        command.apply();
        if (savePoint > done.size()) {
            savePoint = LOST;
        }
        undone.clear();
        done.add(command);
        fire();
    }

    /** @return true if a command was reverted */
    public boolean undo() {
        if (done.isEmpty()) {
            return false;
        }
        Command c = done.get(done.size() - 1);
        c.revert();
        done.remove(done.size() - 1);
        undone.add(c);
        fire();
        return true;
    }

    /** @return true if a command was re-applied */
    public boolean redo() {
        if (undone.isEmpty()) {
            return false;
        }
        Command c = undone.get(undone.size() - 1);
        c.apply();
        undone.remove(undone.size() - 1);
        done.add(c);
        fire();
        return true;
    }

    /** @return true if there is something to undo */
    public boolean canUndo() {
        return !done.isEmpty();
    }

    /** @return true if there is something to redo */
    public boolean canRedo() {
        return !undone.isEmpty();
    }

    /** @return the name of the command {@link #undo()} would revert */
    public Optional<String> undoName() {
        return done.isEmpty() ? Optional.empty() : Optional.of(done.get(done.size() - 1).name());
    }

    /** @return the name of the command {@link #redo()} would re-apply */
    public Optional<String> redoName() {
        return undone.isEmpty() ? Optional.empty() : Optional.of(undone.get(undone.size() - 1).name());
    }

    /** Marks the current position as saved. */
    public void markSaved() {
        if (savePoint != done.size()) {
            savePoint = done.size();
            fire();
        }
    }

    /** @return true if the current position differs from the save point (always true once it was lost) */
    public boolean isModified() {
        return done.size() != savePoint;
    }

    /** Forgets every command; the empty state counts as saved. */
    public void clear() {
        if (done.isEmpty() && undone.isEmpty() && savePoint == 0) {
            return;
        }
        done.clear();
        undone.clear();
        savePoint = 0;
        fire();
    }

    /**
     * Adds a listener.
     *
     * @param listener the listener
     */
    public void addListener(HistoryListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    public void removeListener(HistoryListener listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (HistoryListener l : listeners) {
            l.changed();
        }
    }
}
