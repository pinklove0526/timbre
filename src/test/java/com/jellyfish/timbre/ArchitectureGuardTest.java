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
package com.jellyfish.timbre;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Enforces that the non-UI packages never depend on JavaFX, and that audio never depends on ui. */
class ArchitectureGuardTest {

    @Test
    void nonUiPackagesDoNotImportJavaFx() throws IOException {
        Path root = Path.of("src/main/java/com/jellyfish/timbre");
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String pkg : new String[] {"model", "audio", "dsp", "edit"}) {
            Path dir = root.resolve(pkg);
            assertTrue(Files.isDirectory(dir), "missing package directory " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    List<String> lines = Files.readAllLines(f);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains("import javafx")) {
                            violations.add(f + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        assertTrue(scanned > 10, "scanner found only " + scanned + " files; wrong working directory?");
        assertTrue(violations.isEmpty(), "JavaFX imports found: " + violations);
    }
    @Test
    void audioDoesNotImportUi() throws IOException {
        Path dir = Path.of("src/main/java/com/jellyfish/timbre/audio");
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).contains("import com.jellyfish.timbre.ui")) {
                        violations.add(f + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(scanned > 10, "scanner found only " + scanned + " files; wrong working directory?");
        assertTrue(violations.isEmpty(), "audio imports ui: " + violations);
    }
}
