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

import java.util.List;
import java.util.Objects;

/**
 * The app-internal clipboard: one {@link Fragment} per copied track, in project order, or nothing. It is
 * not the system clipboard. The fragments keep the sources they reference alive for as long as they are on
 * the clipboard (that is how a cut can be pasted after the track was edited further).
 *
 * <p><b>Threading:</b> single-threaded: use it on the JavaFX application thread only.
 */
public final class Clipboard {

    private List<Fragment> fragments = List.of();

    /** Creates an empty clipboard. */
    public Clipboard() {
        // empty
    }

    /**
     * Replaces the content.
     *
     * @param newFragments the fragments, one per track in project order (copied); an empty list empties it
     */
    public void set(List<Fragment> newFragments) {
        fragments = List.copyOf(Objects.requireNonNull(newFragments, "newFragments"));
    }

    /** @return the fragments, immutable; empty if the clipboard is empty */
    public List<Fragment> get() {
        return fragments;
    }

    /** @return true if nothing is on the clipboard */
    public boolean isEmpty() {
        return fragments.isEmpty();
    }

    /** Empties the clipboard. */
    public void clear() {
        fragments = List.of();
    }
}
