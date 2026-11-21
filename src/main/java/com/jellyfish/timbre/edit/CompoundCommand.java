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
 * Several commands as one: apply runs them in order and is all-or-nothing (if one throws, the ones already
 * applied are reverted in reverse order and the exception is rethrown); revert runs them in reverse order.
 *
 * <p><b>Threading:</b> the thread that owns the edited tracks.
 */
public final class CompoundCommand implements Command {

    private final String name;
    private final List<Command> commands;

    /**
     * Creates the compound.
     *
     * @param name     the name
     * @param commands the parts, in apply order (copied)
     */
    public CompoundCommand(String name, List<? extends Command> commands) {
        this.name = Objects.requireNonNull(name, "name");
        this.commands = List.copyOf(commands);
    }

    @Override
    public String name() {
        return name;
    }

    /** @return the parts in apply order */
    public List<Command> commands() {
        return commands;
    }

    @Override
    public void apply() {
        int done = 0;
        try {
            for (Command c : commands) {
                c.apply();
                done++;
            }
        } catch (RuntimeException | Error e) {
            for (int i = done - 1; i >= 0; i--) {
                try {
                    commands.get(i).revert();
                } catch (RuntimeException | Error suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }

    @Override
    public void revert() {
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).revert();
        }
    }
}
