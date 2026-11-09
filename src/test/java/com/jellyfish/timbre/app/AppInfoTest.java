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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void nameIsTimbre() {
        assertEquals("Timbre", AppInfo.get().name());
    }

    @Test
    void versionIsFiltered() {
        String version = AppInfo.get().version();
        assertFalse(version.isBlank());
        assertFalse(version.contains("${"));
    }

    @Test
    void displayNameCombinesNameAndVersion() {
        AppInfo info = AppInfo.get();
        assertEquals(info.name() + " " + info.version(), info.displayName());
    }

    @Test
    void missingResourceFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> AppInfo.load("/no/such/timbre.properties"));
    }
}
