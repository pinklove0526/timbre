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
package com.jellyfish.timbre.audio.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads the recorded Phase A device traces from the test resources. */
public final class DeviceTrace {

    /** One sampled row. */
    public record Row(double tMs, long written, long pos, long avail) {
    }

    private final List<Row> rows;
    private final Map<String, Double> events;

    private DeviceTrace(List<Row> rows, Map<String, Double> events) {
        this.rows = rows;
        this.events = events;
    }

    /**
     * Loads a trace.
     *
     * @param name file base name, for example {@code clock-44100}
     * @return the trace
     */
    public static DeviceTrace load(String name) {
        try {
            List<Row> rows = new ArrayList<>();
            for (String[] f : lines(name + ".csv")) {
                rows.add(new Row(Double.parseDouble(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2]),
                        Long.parseLong(f[4])));
            }
            Map<String, Double> ev = new HashMap<>();
            for (String[] f : lines(name + "-events.csv")) {
                ev.put(f[0], Double.parseDouble(f[1]));
            }
            return new DeviceTrace(rows, ev);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read trace " + name, e);
        }
    }

    private static List<String[]> lines(String file) throws IOException {
        String path = "/com/jellyfish/timbre/audio/traces/" + file;
        try (InputStream in = DeviceTrace.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String[]> out = new ArrayList<>();
            r.readLine(); // header
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    out.add(line.split(","));
                }
            }
            return out;
        }
    }

    /** @return the rows in time order */
    public List<Row> rows() {
        return rows;
    }

    /**
     * @param name event name
     * @return its time in milliseconds
     */
    public double event(String name) {
        Double v = events.get(name);
        if (v == null) {
            throw new IllegalStateException("no event " + name + " in " + events.keySet());
        }
        return v;
    }
}
