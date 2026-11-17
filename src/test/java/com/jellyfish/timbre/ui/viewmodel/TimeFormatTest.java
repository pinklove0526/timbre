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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TimeFormatTest {

    @Test
    void clockKnownAnswers() {
        assertEquals("0:00:00.000", TimeFormat.clock(0, 44100));
        assertEquals("0:01:05.250", TimeFormat.clock(44100L * 65 + 11025, 44100));
        assertEquals("1:01:01.010", TimeFormat.clock(44100L * 3661 + 441, 44100));
        assertEquals("0:00:00.000", TimeFormat.clock(1, 44100), "milliseconds are truncated");
        assertEquals("0:00:00.999", TimeFormat.clock(44099, 44100));
        assertEquals("0:00:00.999", TimeFormat.clock(47999, 48000));
        assertEquals("0:00:01.000", TimeFormat.clock(48000, 48000));
        assertEquals("100:00:00.000", TimeFormat.clock(44100L * 360000, 44100));
        assertEquals("0:00:00.125", TimeFormat.clock(1000, 8000));
    }

    @Test
    void clockHasOneFixedShape() {
        for (long f : new long[] {0, 1, 44100, 44100L * 59, 44100L * 600, 44100L * 36000}) {
            assertEquals(true, TimeFormat.clock(f, 44100).matches("\\d+:\\d\\d:\\d\\d\\.\\d\\d\\d"),
                    TimeFormat.clock(f, 44100));
        }
    }

    @Test
    void clockRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> TimeFormat.clock(-1, 44100));
        assertThrows(IllegalArgumentException.class, () -> TimeFormat.clock(1, 0));
    }

    @Test
    void rulerLabelsKnownAnswers() {
        assertEquals("0", TimeFormat.ruler(0, 1));
        assertEquals("5", TimeFormat.ruler(5, 1));
        assertEquals("50", TimeFormat.ruler(50, 10));
        assertEquals("0.5", TimeFormat.ruler(0.5, 0.5));
        assertEquals("10.2", TimeFormat.ruler(10.2, 0.1));
        assertEquals("0.003", TimeFormat.ruler(0.003, 0.001));
        assertEquals("0.0005", TimeFormat.ruler(0.0005, 0.0005));
        assertEquals("0.0000", TimeFormat.ruler(0, 0.0001));
        assertEquals("0.000005", TimeFormat.ruler(0.000005, 0.000001));
        assertEquals("0.000020", TimeFormat.ruler(0.00002, 0.000002));
        assertEquals("59.9999", TimeFormat.ruler(59.9999, 0.0001));
        assertEquals("1:05.5", TimeFormat.ruler(65.5, 0.5));
        assertEquals("1:40", TimeFormat.ruler(100, 50));
        assertEquals("59:59.5", TimeFormat.ruler(3599.5, 0.5));
        assertEquals("1:00.0", TimeFormat.ruler(59.96, 0.1), "rounding carries into the minute");
        assertEquals("1:00:00", TimeFormat.ruler(3600, 3600));
        assertEquals("2:02:05", TimeFormat.ruler(7200 + 120 + 5, 5));
        assertEquals("10:00:00", TimeFormat.ruler(36000, 3600));
    }

    @Test
    void rulerRejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> TimeFormat.ruler(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> TimeFormat.ruler(1, 0));
        assertThrows(IllegalArgumentException.class, () -> TimeFormat.ruler(Double.NaN, 1));
    }
}
