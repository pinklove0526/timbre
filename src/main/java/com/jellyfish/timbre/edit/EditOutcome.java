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

import com.jellyfish.timbre.model.Selection;
import java.util.Objects;

/**
 * The result of an edit that changed something: the recorded command and the selection the UI should show
 * afterwards.
 *
 * <p><b>Threading:</b> immutable record; the command itself belongs to the editing thread.
 *
 * @param command        the command recorded in the history
 * @param selectionAfter the selection to show after the edit
 */
public record EditOutcome(Command command, Selection selectionAfter) {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if a component is null
     */
    public EditOutcome {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(selectionAfter, "selectionAfter");
    }
}
