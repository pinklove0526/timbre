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
package com.jellyfish.timbre.audio.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.SourceDataLine;

/**
 * Samples a line's clock every ~5 ms from its own thread, and holds the analysis helpers.
 *
 * <p>Dev tool, not shipped. Rows are read only after {@link #finish()}.
 */
final class ProbeSampler implements Runnable {

    /** One sample of the line. */
    record Row(long tNanos, long written, long pos, long micros, long availFrames, boolean running,
               boolean active) {
    }

    /** A named moment, recorded by the controlling thread. */
    record Event(String name, long tNanos) {
    }

    /** Frames handed to the line so far (updated by the writer). */
    final AtomicLong written = new AtomicLong();
    final List<Row> rows = new ArrayList<>(4096);
    final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();

    private final SourceDataLine line;
    private final int frameSize;
    private final long intervalNanos;
    private volatile boolean stop;
    private Thread thread;

    ProbeSampler(SourceDataLine line) {
        this(line, 5_000_000L);
    }

    ProbeSampler(SourceDataLine line, long intervalNanos) {
        this.line = line;
        this.frameSize = line.getFormat().getFrameSize();
        this.intervalNanos = intervalNanos;
    }

    void begin() {
        thread = new Thread(this, "probe-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    void event(String name) {
        events.add(new Event(name, System.nanoTime()));
    }

    /** Stops the sampler thread and waits for it. */
    void finish() {
        stop = true;
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run() {
        while (!stop) {
            try {
                rows.add(new Row(System.nanoTime(), written.get(), line.getLongFramePosition(),
                        line.getMicrosecondPosition(), line.available() / frameSize, line.isRunning(),
                        line.isActive()));
            } catch (RuntimeException e) {
                break;
            }
            LockSupport.parkNanos(intervalNanos);
        }
    }

    long eventTime(String name) {
        for (Event e : events) {
            if (e.name().equals(name)) {
                return e.tNanos();
            }
        }
        throw new IllegalStateException("no event " + name);
    }

    /** @return rows with {@code from <= t <= to} */
    List<Row> window(long from, long to) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.tNanos() >= from && r.tNanos() <= to) {
                out.add(r);
            }
        }
        return out;
    }

    /** Least squares fit of y on x. @return {slope, intercept} */
    static double[] fit(double[] x, double[] y) {
        int n = x.length;
        if (n < 2) {
            throw new IllegalArgumentException("need at least two points, got " + n);
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
        }
        double slope = sxy / sxx;
        return new double[] {slope, my - slope * mx};
    }

    static double median(List<Long> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        List<Long> s = new ArrayList<>(v);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
