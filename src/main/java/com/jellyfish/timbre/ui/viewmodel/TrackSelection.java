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

import com.jellyfish.timbre.model.Track;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which tracks are selected (by identity) and the anchor for range clicks on the track headers.
 *
 * <p>Click rules: a plain click selects only the clicked track and makes it the anchor; a toggle click
 * (Ctrl/Cmd) adds or removes the clicked track and makes it the anchor; a range click (Shift) selects the
 * tracks from the anchor to the clicked one, inclusive, in either direction, and keeps the anchor; a toggle
 * range click (Ctrl/Cmd+Shift) adds that range to the existing selection. A range click without an anchor
 * (none yet, or the anchor was removed) behaves like a plain click. Edits target the selected tracks, or all
 * tracks when none is selected ({@link #targets}).
 *
 * <p><b>Threading:</b> not thread-safe; use it on the JavaFX application thread.
 */
public final class TrackSelection {

    private final Set<Track> selected = Collections.newSetFromMap(new IdentityHashMap<>());
    private Track anchor;

    /** Creates an empty selection without an anchor. */
    public TrackSelection() {
        // empty
    }

    /**
     * Applies a header click.
     *
     * @param all    the project's tracks in order
     * @param index  the clicked track, {@code 0 <= index < all.size()}
     * @param toggle true if Ctrl/Cmd is held
     * @param range  true if Shift is held
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public void click(List<Track> all, int index, boolean toggle, boolean range) {
        Objects.requireNonNull(all, "all");
        Objects.checkIndex(index, all.size());
        Track clicked = all.get(index);
        int anchorIndex = anchor == null ? -1 : indexOf(all, anchor);
        if (range && anchorIndex >= 0) {
            if (!toggle) {
                selected.clear();
            }
            int lo = Math.min(anchorIndex, index);
            int hi = Math.max(anchorIndex, index);
            for (int i = lo; i <= hi; i++) {
                selected.add(all.get(i));
            }
            return;
        }
        if (toggle && !range) {
            if (!selected.remove(clicked)) {
                selected.add(clicked);
            }
        } else {
            selected.clear();
            selected.add(clicked);
        }
        anchor = clicked;
    }

    /**
     * Drops the tracks that are no longer in the project; the anchor is dropped if its track went.
     *
     * @param all the project's tracks
     */
    public void retain(List<Track> all) {
        Objects.requireNonNull(all, "all");
        selected.removeIf(t -> indexOf(all, t) < 0);
        if (anchor != null && indexOf(all, anchor) < 0) {
            anchor = null;
        }
    }

    /** Selects nothing and forgets the anchor. */
    public void clear() {
        selected.clear();
        anchor = null;
    }

    /**
     * Returns the selected tracks in project order.
     *
     * @param all the project's tracks in order
     * @return the selected tracks that are in {@code all}, in its order (may be empty)
     */
    public List<Track> selected(List<Track> all) {
        Objects.requireNonNull(all, "all");
        List<Track> out = new ArrayList<>();
        for (Track t : all) {
            if (selected.contains(t)) {
                out.add(t);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Returns the tracks an edit applies to.
     *
     * @param all the project's tracks in order
     * @return the selected tracks in project order, or all tracks when none of them is selected
     */
    public List<Track> targets(List<Track> all) {
        List<Track> sel = selected(all);
        return sel.isEmpty() ? List.copyOf(all) : sel;
    }

    /**
     * @param track a track
     * @return true if it is selected
     */
    public boolean isSelected(Track track) {
        return selected.contains(track);
    }

    /** @return true if no track is selected */
    public boolean isEmpty() {
        return selected.isEmpty();
    }

    /** @return the anchor of range clicks, if any */
    public Optional<Track> anchor() {
        return Optional.ofNullable(anchor);
    }

    private static int indexOf(List<Track> all, Track t) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == t) {
                return i;
            }
        }
        return -1;
    }
}
