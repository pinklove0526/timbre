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

import com.jellyfish.timbre.model.Selection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The time selections before and after each history entry, kept beside the edit history so that undo and
 * redo can restore the selection. It must move in lockstep with the history: {@link #record} after every
 * recorded edit, {@link #undo()} and {@link #redo()} only when the history actually moved.
 *
 * <p><b>Threading:</b> not thread-safe; use it on the JavaFX application thread.
 */
public final class SelectionTrail {

    private record Entry(Selection before, Selection after) {
    }

    private final List<Entry> done = new ArrayList<>();
    private final List<Entry> undone = new ArrayList<>();

    /** Creates an empty trail. */
    public SelectionTrail() {
        // empty
    }

    /**
     * Records the selections of a new history entry; the redo part is discarded.
     *
     * @param before the selection before the edit
     * @param after  the selection after the edit
     */
    public void record(Selection before, Selection after) {
        done.add(new Entry(Objects.requireNonNull(before, "before"), Objects.requireNonNull(after, "after")));
        undone.clear();
    }

    /** @return the selection from before the latest entry (which moves to the redo part), or empty */
    public Optional<Selection> undo() {
        if (done.isEmpty()) {
            return Optional.empty();
        }
        Entry e = done.remove(done.size() - 1);
        undone.add(e);
        return Optional.of(e.before());
    }

    /** @return the selection from after the next redo entry (which moves back), or empty */
    public Optional<Selection> redo() {
        if (undone.isEmpty()) {
            return Optional.empty();
        }
        Entry e = undone.remove(undone.size() - 1);
        done.add(e);
        return Optional.of(e.after());
    }

    /** Forgets everything. */
    public void clear() {
        done.clear();
        undone.clear();
    }

    /** @return the number of entries that can be undone */
    public int undoDepth() {
        return done.size();
    }

    /** @return the number of entries that can be redone */
    public int redoDepth() {
        return undone.size();
    }
}
