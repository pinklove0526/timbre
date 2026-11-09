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
package com.jellyfish.timbre.app;

import javafx.application.Application;

/**
 * Main class of the runnable jar. Deliberately does not extend {@link Application}:
 * a fat-jar main class extending Application fails with "JavaFX runtime components are missing".
 */
public final class Launcher {

    private Launcher() {
    }

    /**
     * Prints the version and returns for {@code --version}; otherwise starts the JavaFX application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        for (String arg : args) {
            if ("--version".equals(arg)) {
                System.out.println(AppInfo.get().displayName());
                return;
            }
        }
        Application.launch(TimbreApp.class, args);
    }
}
