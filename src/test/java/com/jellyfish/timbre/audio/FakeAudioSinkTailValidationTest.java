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
package com.jellyfish.timbre.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.support.FakeAudioSink;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Validates the DRAIN_RETURNS_WHEN_STOPPED and RESTART_NEEDS_DEPTH quirks of {@link FakeAudioSink} against the
 * recorded hardening probe (tail-h1.csv, tailpad-h1.csv): every case is re-run on the fake in virtual time and the
 * OK/STUCK verdict must agree, and the drain time must agree within 60 ms (OK) or 40 ms (STUCK, the 500 ms
 * give-up). Documented exception: with K of 4 blocks or more written after the restart, tails of 30 ms were
 * always fine on the real line although the depth is in the stuck range; those rows are not modelled.
 */
@Timeout(120)
class FakeAudioSinkTailValidationTest {

    private record Row(String scenario, int tMs, int k, double expectedMs, double drainMs, String verdict) {
    }

    private static List<Row> load(String file) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (InputStream in = FakeAudioSinkTailValidationTest.class
                .getResourceAsStream("/com/jellyfish/timbre/audio/traces/" + file)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",");
                rows.add(new Row(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Double.parseDouble(f[12]),
                        Double.parseDouble(f[11]), f[13]));
            }
        }
        return rows;
    }

    /** Re-runs one case on the fake and returns the virtual drain time in ms since the restart. */
    private static double run(Row row) throws Exception {
        int rate = 44100;
        FakeAudioSink fake = new FakeAudioSink(FakeAudioSink.Pace.MANUAL, FakeAudioSink.Artifacts.PULSE_LIKE, 1,
                EnumSet.of(FakeAudioSink.Quirk.DRAIN_RETURNS_WHEN_STOPPED, FakeAudioSink.Quirk.RESTART_NEEDS_DEPTH));
        fake.open(rate, 8820);
        fake.start();
        int tFrames = rate * row.tMs() / 1000;
        fake.write(new byte[tFrames * 4], 0, tFrames * 4);
        if (row.scenario().equals("A")) {
            Thread d = new Thread(fake::drain);
            d.start();
            assertTrue(fake.awaitDrainBlocked(5000));
            fake.advance(20_000_000L);
            fake.stop();
            d.join(2000);
            assertTrue(!d.isAlive(), "drain must return when the sink is stopped");
        } else if (row.scenario().equals("P")) {
            fake.advance(60_000_000L);
            fake.stop();
        } else {
            fake.advance(row.tMs() / 2 * 1_000_000L);
            fake.stop();
        }
        fake.advance(300_000_000L);
        fake.start();
        // like the probe: K blocks, then drain(), on one thread (the fake's queue is two buffers deep, so the
        // deepest cases block until the device has consumed some audio, as a real writer would)
        int k = row.k();
        Thread d = new Thread(() -> {
            for (int i = 0; i < k; i++) {
                fake.write(new byte[1024 * 4], 0, 1024 * 4);
            }
            fake.drain();
        });
        d.start();
        double virtualMs = 0;
        while (d.isAlive() && virtualMs < 2000) {
            fake.advance(5_000_000L);
            virtualMs += 5;
            d.join(1);
        }
        d.join(2000);
        assertTrue(!d.isAlive(), "drain never returned for " + row);
        return virtualMs;
    }

    private static void check(String file, boolean skipShortTailsWithPad) throws Exception {
        int compared = 0;
        int ok = 0;
        int stuck = 0;
        double worstOk = 0;
        double worstStuck = 0;
        for (Row row : load(file)) {
            if (skipShortTailsWithPad && row.tMs() == 30 && row.k() >= 4) {
                continue;
            }
            double fakeMs = run(row);
            String fakeVerdict = fakeMs <= row.expectedMs() + 250 ? "OK" : "STUCK";
            assertEquals(row.verdict(), fakeVerdict, "verdict for " + row + " (fake drain " + fakeMs + " ms)");
            if (row.verdict().equals("OK")) {
                ok++;
                worstOk = Math.max(worstOk, Math.abs(fakeMs - row.drainMs()));
                assertTrue(Math.abs(fakeMs - row.drainMs()) <= 60, row + " fake " + fakeMs);
            } else {
                stuck++;
                worstStuck = Math.max(worstStuck, Math.abs(fakeMs - row.drainMs()));
                assertTrue(Math.abs(fakeMs - row.drainMs()) <= 40, row + " fake " + fakeMs);
            }
            compared++;
        }
        System.out.printf("FAKE-TAIL-VALIDATION %s: %d rows compared (%d OK, %d STUCK); worst drain-time difference"
                + " OK %.1f ms, STUCK %.1f ms%n", file, compared, ok, stuck, worstOk, worstStuck);
        assertTrue(compared >= 15, "too few rows compared: " + compared);
    }

    @Test
    void theMatrixOfScenariosAAndBMatchesTheRecordedVerdicts() throws Exception {
        check("tail-h1.csv", true);
    }

    @Test
    void thePreQueuedDepthSweepMatchesTheRecordedVerdicts() throws Exception {
        check("tailpad-h1.csv", false);
    }
}
