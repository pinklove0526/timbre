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

/**
 * One undoable change. {@link #apply()} and {@link #revert()} must be callable alternately any number of
 * times, starting with apply.
 *
 * <p><b>Threading:</b> implementations are used on one thread (the JavaFX application thread in the app),
 * the thread that owns the edited tracks.
 */
public interface Command {

    /** @return the name shown to the user, for example "Cut" */
    String name();

    /** Performs (or re-performs) the change. */
    void apply();

    /** Undoes the change made by the last {@link #apply()}. */
    void revert();
}
