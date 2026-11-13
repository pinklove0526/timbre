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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

/**
 * Developer tool that measures the audio output path of this machine. Not a test, not shipped.
 *
 * <p>Usage: {@code AudioProbe <env|mixers|open|clock|pause|flush|drain|underrun|reuse|all> [--consent]
 * [--out DIR]}. Audible modes (clock, pause, flush, drain, underrun, reuse) refuse to run without
 * {@code --consent}, which must only be given after the user agreed; they play quiet (-24 dBFS) tones
 * on the default output path and stop when the 30 s audible budget in {@code sound-ledger.txt} is used up.
 * Capture lines are only listed, never opened.
 *
 * <p>Threading: the main thread controls, a writer thread (where noted) writes, a sampler thread reads
 * the line clock.
 */
public final class AudioProbe {

    private static final int FRAME_BYTES = 4;
    private static final int BLOCK_FRAMES = 1024;
    private static final double BUFFER_SECONDS = 0.2;

    private static Path out = Path.of("build-logs", "m2-probe");
    private static boolean consent;

    private AudioProbe() {
    }

    /**
     * Runs one probe mode.
     *
     * @param args mode and options
     * @throws Exception on unexpected failure
     */
    public static void main(String[] args) throws Exception {
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--consent" -> consent = true;
                case "--out" -> out = Path.of(args[++i]);
                default -> mode = args[i];
            }
        }
        if (mode == null) {
            System.err.println("usage: AudioProbe <env|mixers|open|clock|pause|flush|drain|underrun|reuse|all>"
                    + " [--consent] [--out DIR]");
            System.exit(2);
        }
        Files.createDirectories(out);
        Thread dog = new Thread(() -> {
            try {
                Thread.sleep(150_000);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println("WATCHDOG: probe appears hung, halting");
            Runtime.getRuntime().halt(3);
        }, "probe-watchdog");
        dog.setDaemon(true);
        dog.start();

        List<String> modes = mode.equals("all")
                ? List.of("env", "mixers", "open", "clock", "pause", "flush", "drain", "underrun", "reuse")
                : List.of(mode);
        for (String m : modes) {
            System.out.println("==== mode " + m + " ====");
            switch (m) {
                case "env" -> env();
                case "mixers" -> mixers();
                case "open" -> open();
                case "clock" -> {
                    if (audible("clock")) {
                        clock(44100);
                        clock(48000);
                        if (AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, format(96000)))) {
                            clock(96000);
                        } else {
                            System.out.println("96000 Hz stereo 16-bit not supported on the default path: skipped");
                        }
                    }
                }
                case "pause" -> {
                    if (audible("pause")) {
                        pause();
                    }
                }
                case "flush" -> {
                    if (audible("flush")) {
                        flush();
                    }
                }
                case "drain" -> {
                    if (audible("drain")) {
                        drain();
                    }
                }
                case "underrun" -> {
                    if (audible("underrun")) {
                        underrun();
                    }
                }
                case "reuse" -> {
                    if (audible("reuse")) {
                        reuse();
                    }
                }
                default -> {
                    System.err.println("unknown mode " + m);
                    System.exit(2);
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean audible(String mode) {
        if (!consent) {
            System.out.println("SKIPPED: mode " + mode + " is audible and --consent was not given");
            return false;
        }
        return true;
    }

    private static void reserve(String label, double seconds) {
        ProbeTone.reserve(out.resolve("sound-ledger.txt"), label, seconds);
    }

    private static AudioFormat format(int rate) {
        return new AudioFormat(rate, 16, 2, true, false);
    }

    private static int bufferBytes(int rate) {
        return (int) Math.round(rate * BUFFER_SECONDS) * FRAME_BYTES;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String f(String fmt, Object... a) {
        return String.format(Locale.ROOT, fmt, a);
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static double framesToMs(double frames, int rate) {
        return frames * 1000.0 / rate;
    }

    /** Counts of write-call behaviour. */
    private static final class WriteStats {
        int calls;
        int shortWrites;
        int zeroWrites;
    }

    /** Writes in 1024-frame blocks, tolerating short returns (stopped/flushed lines). */
    private static void writeFully(SourceDataLine line, byte[] pcm, ProbeSampler s, WriteStats st) {
        int off = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (off < pcm.length && System.nanoTime() < deadline && line.isOpen()) {
            int len = Math.min(BLOCK_FRAMES * FRAME_BYTES, pcm.length - off);
            int n = line.write(pcm, off, len);
            st.calls++;
            if (n < len) {
                st.shortWrites++;
            }
            if (n == 0) {
                st.zeroWrites++;
                sleep(1);
            }
            off += n;
            s.written.addAndGet(n / FRAME_BYTES);
        }
    }

    /** An open default-path line with a sampler. */
    private static final class Session {
        final int rate;
        final AudioFormat fmt;
        final SourceDataLine line;
        final ProbeSampler sampler;
        final WriteStats stats = new WriteStats();
        final int bufferFrames;
        final double openMs;

        Session(int rate) throws LineUnavailableException {
            this.rate = rate;
            this.fmt = format(rate);
            this.line = AudioSystem.getSourceDataLine(fmt);
            long t0 = System.nanoTime();
            line.open(fmt, bufferBytes(rate));
            this.openMs = ms(System.nanoTime() - t0);
            this.bufferFrames = line.getBufferSize() / FRAME_BYTES;
            this.sampler = new ProbeSampler(line);
            System.out.println(f("opened %d Hz: open %.1f ms, granted buffer %d frames (%.1f ms), format %s",
                    rate, openMs, bufferFrames, framesToMs(bufferFrames, rate), line.getFormat()));
        }

        void close() {
            sampler.finish();
            line.close();
        }

        long last(String ev, long deltaMs) {
            return sampler.eventTime(ev) + deltaMs * 1_000_000L;
        }
    }

    private static void csv(Session s, String name) throws IOException {
        long t0 = s.sampler.rows.isEmpty() ? 0 : s.sampler.rows.get(0).tNanos();
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve(name + ".csv"), StandardCharsets.UTF_8)) {
            w.write("t_ms,written_frames,pos_frames,pos_micros,avail_frames,running,active\n");
            for (ProbeSampler.Row r : s.sampler.rows) {
                w.write(f("%.3f,%d,%d,%d,%d,%b,%b%n", ms(r.tNanos() - t0), r.written(), r.pos(), r.micros(),
                        r.availFrames(), r.running(), r.active()));
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve(name + "-events.csv"), StandardCharsets.UTF_8)) {
            w.write("event,t_ms\n");
            for (ProbeSampler.Event e : s.sampler.events) {
                w.write(f("%s,%.3f%n", e.name(), ms(e.tNanos() - t0)));
            }
        }
    }

    // ------------------------------------------------------------------ env

    private static void env() throws IOException {
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("os.version=" + System.getProperty("os.version"));
        System.out.println("~/.asoundrc exists: "
                + Files.exists(Path.of(System.getProperty("user.home"), ".asoundrc")));
        System.out.println("/etc/asound.conf exists: " + Files.exists(Path.of("/etc/asound.conf")));
        for (String[] cmd : new String[][] {{"pactl", "info"}, {"pactl", "list", "short", "sinks"}}) {
            System.out.println("$ " + String.join(" ", cmd));
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getOutputStream().close();
                String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(10, TimeUnit.SECONDS);
                for (String line : text.split("\n")) {
                    if (line.startsWith("User Name") || line.startsWith("Host Name") || line.startsWith("Cookie")
                            || line.startsWith("Server String")) {
                        continue; // personal or machine-specific, not needed
                    }
                    System.out.println("  " + line);
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("  unavailable: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ mixers

    private static AudioFormat matrixFormat(String enc, int bits, int rate, int ch) {
        AudioFormat.Encoding e = enc.equals("FLOAT") ? AudioFormat.Encoding.PCM_FLOAT : AudioFormat.Encoding.PCM_SIGNED;
        return new AudioFormat(e, rate, bits, ch, ch * bits / 8, rate, false);
    }

    private static void mixers() throws IOException {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        int[] rates = {8000, 22050, 44100, 48000, 96000};
        String[][] encs = {{"SIGNED", "16"}, {"SIGNED", "24"}, {"SIGNED", "32"}, {"FLOAT", "32"}};
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("mixers.csv"), StandardCharsets.UTF_8)) {
            w.write("mixer,encoding,bits,channels,rate,source_data_line_supported\n");
            for (Mixer.Info info : infos) {
                Mixer m = AudioSystem.getMixer(info);
                System.out.println("MIXER '" + info.getName() + "' vendor='" + info.getVendor() + "' desc='"
                        + info.getDescription() + "' version='" + info.getVersion() + "' sourceLineInfos="
                        + m.getSourceLineInfo().length + " targetLineInfos=" + m.getTargetLineInfo().length);
                for (Line.Info li : m.getTargetLineInfo()) {
                    System.out.println("    capture line (listed only, never opened): " + li);
                }
                boolean anySource = false;
                for (Line.Info li : m.getSourceLineInfo()) {
                    anySource |= li.getLineClass() == SourceDataLine.class;
                }
                if (!anySource) {
                    System.out.println("    no SourceDataLine support");
                    continue;
                }
                for (String[] e : encs) {
                    StringBuilder row = new StringBuilder(f("    %-6s %2s-bit:", e[0], e[1]));
                    for (int ch = 1; ch <= 2; ch++) {
                        for (int rate : rates) {
                            boolean ok = m.isLineSupported(new DataLine.Info(SourceDataLine.class,
                                    matrixFormat(e[0], Integer.parseInt(e[1]), rate, ch)));
                            row.append(f("  %dch/%d=%s", ch, rate, ok ? "Y" : "-"));
                            w.write(f("\"%s\",%s,%s,%d,%d,%b%n", info.getName(), e[0], e[1], ch, rate, ok));
                        }
                    }
                    System.out.println(row);
                }
            }
        }
        for (int rate : new int[] {44100, 48000}) {
            AudioFormat fmt = format(rate);
            try {
                SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
                l.open(fmt, bufferBytes(rate)); // opened and closed, nothing written
                String owner = "unknown";
                for (Mixer.Info info : infos) {
                    if (Arrays.asList(AudioSystem.getMixer(info).getSourceLines()).contains(l)) {
                        owner = info.getName();
                    }
                }
                l.close();
                System.out.println("AudioSystem.getSourceDataLine(16-bit stereo " + rate + ") -> mixer '" + owner + "'");
            } catch (LineUnavailableException | IllegalArgumentException e) {
                System.out.println("AudioSystem.getSourceDataLine(16-bit stereo " + rate + ") failed: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ open (silent)

    private static void open() throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("open.csv"), StandardCharsets.UTF_8)) {
            w.write("mixer,rate,open_ms,granted_buffer_frames,granted_buffer_ms,granted_format,error\n");
            for (Mixer.Info info : AudioSystem.getMixerInfo()) {
                Mixer m = AudioSystem.getMixer(info);
                for (int rate : new int[] {44100, 48000}) {
                    AudioFormat fmt = format(rate);
                    DataLine.Info li = new DataLine.Info(SourceDataLine.class, fmt);
                    if (!m.isLineSupported(li)) {
                        continue;
                    }
                    String err = "";
                    double openMs = Double.NaN;
                    int frames = -1;
                    String granted = "";
                    try {
                        SourceDataLine l = (SourceDataLine) m.getLine(li);
                        long t0 = System.nanoTime();
                        l.open(fmt, bufferBytes(rate));
                        openMs = ms(System.nanoTime() - t0);
                        frames = l.getBufferSize() / FRAME_BYTES;
                        granted = l.getFormat().toString();
                        l.close(); // nothing written
                    } catch (LineUnavailableException | RuntimeException e) {
                        err = e.toString();
                    }
                    System.out.println(f("'%s' %d Hz: open %.1f ms, buffer %d frames (%.1f ms), format [%s]%s",
                            info.getName(), rate, openMs, frames, framesToMs(frames, rate), granted,
                            err.isEmpty() ? "" : ", ERROR " + err));
                    w.write(f("\"%s\",%d,%.2f,%d,%.2f,\"%s\",\"%s\"%n", info.getName(), rate, openMs, frames,
                            framesToMs(frames, rate), granted, err.replace('"', '\'')));
                }
            }
        }
    }

    // ------------------------------------------------------------------ clock

    private static void clock(int rate) throws Exception {
        reserve("clock-" + rate, 3.0);
        Session s = new Session(rate);
        byte[] tone = ProbeTone.segment(rate, 3.0);
        s.sampler.begin();
        s.sampler.event("start-call");
        s.line.start();
        s.sampler.event("first-write");
        writeFully(s.line, tone, s.sampler, s.stats);
        s.sampler.event("write-end");
        s.line.drain();
        s.sampler.event("drain-end");
        sleep(50);
        s.sampler.finish();
        s.line.close();
        csv(s, "clock-" + rate);

        List<ProbeSampler.Row> rows = s.sampler.rows;
        long tFirst = s.sampler.eventTime("first-write");
        long tEnd = s.sampler.eventTime("write-end");
        long maxBack = 0;
        List<Long> steps = new ArrayList<>();
        long firstNonzero = -1;
        for (int i = 0; i < rows.size(); i++) {
            ProbeSampler.Row r = rows.get(i);
            if (i > 0) {
                long d = r.pos() - rows.get(i - 1).pos();
                maxBack = Math.max(maxBack, -d);
                if (d > 0) {
                    steps.add(d);
                }
            }
            if (firstNonzero < 0 && r.pos() > 0 && r.tNanos() >= tFirst) {
                firstNonzero = r.tNanos();
            }
        }
        long minStep = steps.stream().mapToLong(Long::longValue).min().orElse(0);
        long maxStep = steps.stream().mapToLong(Long::longValue).max().orElse(0);
        List<ProbeSampler.Row> steady = s.sampler.window(firstNonzero + 300_000_000L, tEnd + 100_000_000L);
        double[] x = new double[steady.size()];
        double[] y = new double[steady.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = (steady.get(i).tNanos() - tFirst) / 1e9;
            y[i] = steady.get(i).pos();
        }
        double[] fit = ProbeSampler.fit(x, y);
        double ppm = (fit[0] / rate - 1.0) * 1e6;
        double jitter = 0;
        double sumSigned = 0;
        double sumAbs = 0;
        double maxAbs = 0;
        double[] mx = new double[x.length];
        double[] my = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            jitter = Math.max(jitter, Math.abs(y[i] - (fit[0] * x[i] + fit[1])));
            ProbeSampler.Row r = steady.get(i);
            double est = r.written() - (s.bufferFrames - r.availFrames());
            double diff = framesToMs(r.pos() - est, rate);
            sumSigned += diff;
            sumAbs += Math.abs(diff);
            maxAbs = Math.max(maxAbs, Math.abs(diff));
            mx[i] = x[i];
            my[i] = r.micros();
        }
        double[] mfit = ProbeSampler.fit(mx, my);
        System.out.println(f("CLOCK %d Hz: rows=%d (%.2f ms mean cadence)", rate, rows.size(),
                ms(rows.get(rows.size() - 1).tNanos() - rows.get(0).tNanos()) / rows.size()));
        System.out.println(f("  monotonic: max backward step = %d frames", maxBack));
        System.out.println(f("  granularity: smallest nonzero step = %d frames, median = %.0f, largest = %d",
                minStep, ProbeSampler.median(steps), maxStep));
        System.out.println(f("  slope = %.2f frames/s vs nominal %d -> %.1f ppm; micros slope %.1f ppm",
                fit[0], rate, ppm, (mfit[0] / 1e6 - 1.0) * 1e6));
        System.out.println(f("  jitter (max distance from fitted line) = %.3f ms", framesToMs(jitter, rate)));
        System.out.println(f("  device position minus estimate: mean %.3f ms, mean|.| %.3f ms, max|.| %.3f ms (n=%d)",
                sumSigned / x.length, sumAbs / x.length, maxAbs, x.length));
        System.out.println(f("  latency first write -> first nonzero position = %.1f ms", ms(firstNonzero - tFirst)));
        System.out.println(f("  final: written %d frames, last position %d, write calls %d (short %d, zero %d)",
                s.sampler.written.get(), rows.get(rows.size() - 1).pos(), s.stats.calls, s.stats.shortWrites,
                s.stats.zeroWrites));
    }

    // ------------------------------------------------------------------ pause

    private static Set<String> flags(List<ProbeSampler.Row> rows) {
        Set<String> set = new LinkedHashSet<>();
        for (ProbeSampler.Row r : rows) {
            set.add("running=" + r.running() + "/active=" + r.active());
        }
        return set;
    }

    private static void pause() throws Exception {
        reserve("pause", 2.5);
        Session s = new Session(44100);
        byte[] tone = ProbeTone.segment(44100, 2.5);
        s.sampler.begin();
        s.line.start();
        s.sampler.event("first-write");
        Thread writer = new Thread(() -> writeFully(s.line, tone, s.sampler, s.stats), "probe-writer");
        writer.start();
        sleep(800);
        s.sampler.event("stop-call");
        s.line.stop();
        s.sampler.event("stop-returned");
        sleep(500);
        s.sampler.event("start-call");
        s.line.start();
        s.sampler.event("start-returned");
        writer.join(10_000);
        s.sampler.event("write-end");
        s.line.drain();
        sleep(50);
        s.sampler.finish();
        s.line.close();
        csv(s, "pause");

        long stopRet = s.sampler.eventTime("stop-returned");
        long startCall = s.sampler.eventTime("start-call");
        List<ProbeSampler.Row> frozen = s.sampler.window(stopRet + 40_000_000L, startCall);
        long minPos = Long.MAX_VALUE;
        long maxPos = Long.MIN_VALUE;
        long minAvail = Long.MAX_VALUE;
        long maxAvail = Long.MIN_VALUE;
        long minW = Long.MAX_VALUE;
        long maxW = Long.MIN_VALUE;
        for (ProbeSampler.Row r : frozen) {
            minPos = Math.min(minPos, r.pos());
            maxPos = Math.max(maxPos, r.pos());
            minAvail = Math.min(minAvail, r.availFrames());
            maxAvail = Math.max(maxAvail, r.availFrames());
            minW = Math.min(minW, r.written());
            maxW = Math.max(maxW, r.written());
        }
        ProbeSampler.Row beforeStop = null;
        for (ProbeSampler.Row r : s.sampler.window(0, stopRet)) {
            beforeStop = r;
        }
        System.out.println(f("PAUSE: while stopped (n=%d rows): position %d..%d (spread %d frames = %.2f ms),"
                        + " available %d..%d, written %d..%d, flags %s",
                frozen.size(), minPos, maxPos, maxPos - minPos, framesToMs(maxPos - minPos, 44100), minAvail,
                maxAvail, minW, maxW, flags(frozen)));
        System.out.println("  position just before stop returned: " + (beforeStop == null ? "n/a" : beforeStop.pos())
                + ", flags before stop: " + flags(s.sampler.window(0, s.sampler.eventTime("stop-call"))));
        long firstAdvance = -1;
        for (ProbeSampler.Row r : s.sampler.window(startCall, startCall + 1_000_000_000L)) {
            if (r.pos() > maxPos) {
                firstAdvance = r.tNanos();
                break;
            }
        }
        ProbeSampler.Row after100 = null;
        for (ProbeSampler.Row r : s.sampler.window(startCall + 100_000_000L, startCall + 200_000_000L)) {
            after100 = after100 == null ? r : after100;
        }
        System.out.println(f("  resume latency (start() call -> position exceeds frozen value) = %.1f ms",
                firstAdvance < 0 ? Double.NaN : ms(firstAdvance - startCall)));
        if (after100 != null) {
            System.out.println(f("  position %.1f ms after start() = frozen + %d frames (%.1f ms of audio); flags after start %s",
                    ms(after100.tNanos() - startCall), after100.pos() - maxPos,
                    framesToMs(after100.pos() - maxPos, 44100), flags(s.sampler.window(startCall + 50_000_000L,
                            startCall + 400_000_000L))));
        }
        System.out.println(f("  write calls %d (short %d, zero %d)", s.stats.calls, s.stats.shortWrites,
                s.stats.zeroWrites));
    }

    // ------------------------------------------------------------------ flush

    private static void flush() throws Exception {
        reserve("flush", 2.0);
        Session s = new Session(44100);
        byte[] tone = ProbeTone.segment(44100, 2.0);
        s.sampler.begin();
        s.line.start();
        s.sampler.event("first-write");
        Thread writer = new Thread(() -> writeFully(s.line, tone, s.sampler, s.stats), "probe-writer");
        writer.start();
        sleep(700);
        long posBefore = s.line.getLongFramePosition();
        int availBefore = s.line.available() / FRAME_BYTES;
        s.sampler.event("flush-call");
        s.line.flush();
        s.sampler.event("flush-returned");
        long posAfter = s.line.getLongFramePosition();
        int availAfter = s.line.available() / FRAME_BYTES;
        boolean running = s.line.isRunning();
        boolean active = s.line.isActive();
        writer.join(10_000);
        s.sampler.event("write-end");
        s.line.drain();
        sleep(50);
        s.sampler.finish();
        long writtenTotal = s.sampler.written.get();
        long lastPos = s.sampler.rows.get(s.sampler.rows.size() - 1).pos();
        s.line.close();
        csv(s, "flush");

        long flushRet = s.sampler.eventTime("flush-returned");
        long maxBack = 0;
        for (int i = 1; i < s.sampler.rows.size(); i++) {
            maxBack = Math.max(maxBack, s.sampler.rows.get(i - 1).pos() - s.sampler.rows.get(i).pos());
        }
        System.out.println(f("FLUSH: position before %d, immediately after %d (delta %d frames); available before %d,"
                        + " after %d of buffer %d (empty = %b); running=%b active=%b after flush",
                posBefore, posAfter, posAfter - posBefore, availBefore, availAfter, s.bufferFrames,
                availAfter >= s.bufferFrames, running, active));
        for (long d : new long[] {50, 150, 400}) {
            List<ProbeSampler.Row> w = s.sampler.window(flushRet + d * 1_000_000L, flushRet + (d + 20) * 1_000_000L);
            if (!w.isEmpty()) {
                System.out.println(f("  %d ms after flush: position %d (%+d vs immediately after), written %d, available %d",
                        d, w.get(0).pos(), w.get(0).pos() - posAfter, w.get(0).written(), w.get(0).availFrames()));
            }
        }
        System.out.println(f("  max backward step over whole run = %d; frames written %d, final position %d (difference"
                        + " %d frames = discarded by flush); write calls %d (short %d, zero %d)",
                maxBack, writtenTotal, lastPos, writtenTotal - lastPos, s.stats.calls, s.stats.shortWrites,
                s.stats.zeroWrites));
    }

    // ------------------------------------------------------------------ drain

    private static void drain() throws Exception {
        reserve("drain", 0.5);
        Session s = new Session(44100);
        byte[] tone = ProbeTone.segment(44100, 0.5);
        s.sampler.begin();
        s.line.start();
        writeFully(s.line, tone, s.sampler, s.stats);
        ProbeSampler.Row now = s.sampler.rows.get(s.sampler.rows.size() - 1);
        long writtenFrames = s.sampler.written.get();
        long posAtCall = s.line.getLongFramePosition();
        long buffered = writtenFrames - posAtCall;
        s.sampler.event("drain-call");
        long t0 = System.nanoTime();
        s.line.drain();
        double drainMs = ms(System.nanoTime() - t0);
        s.sampler.event("drain-returned");
        long posAfter = s.line.getLongFramePosition();
        boolean active = s.line.isActive();
        boolean running = s.line.isRunning();
        int avail = s.line.available() / FRAME_BYTES;
        sleep(100);
        long posLater = s.line.getLongFramePosition();
        s.sampler.finish();
        s.line.close();
        csv(s, "drain");
        System.out.println(f("DRAIN: written %d frames, position at drain() call %d -> buffered %d frames = %.1f ms",
                writtenFrames, posAtCall, buffered, framesToMs(buffered, 44100)));
        System.out.println(f("  drain() took %.1f ms; position right after %d (written - position = %d frames),"
                        + " 100 ms later %d; running=%b active=%b available=%d of %d (last sampled row before: %s)",
                drainMs, posAfter, writtenFrames - posAfter, posLater, running, active, avail, s.bufferFrames,
                now.pos()));
    }

    // ------------------------------------------------------------------ underrun

    private static void underrun() throws Exception {
        reserve("underrun", 0.8);
        Session s = new Session(44100);
        byte[] a = ProbeTone.segment(44100, 0.5);
        byte[] b = ProbeTone.segment(44100, 0.3);
        s.sampler.begin();
        s.line.start();
        s.sampler.event("first-write");
        String error = "none";
        try {
            writeFully(s.line, a, s.sampler, s.stats);
            long writtenA = s.sampler.written.get();
            s.sampler.event("A-end");
            sleep(400);
            s.sampler.event("resume");
            writeFully(s.line, b, s.sampler, s.stats);
            s.sampler.event("B-end");
            s.line.drain();
            s.sampler.event("drain-end");
            sleep(50);
            long writtenTotal = s.sampler.written.get();
            s.sampler.finish();
            csv(s, "underrun");
            long aEnd = s.sampler.eventTime("A-end");
            long resume = s.sampler.eventTime("resume");
            ProbeSampler.Row atA = s.sampler.window(aEnd, resume).get(0);
            ProbeSampler.Row atResume = s.sampler.window(resume - 20_000_000L, resume).get(0);
            System.out.println(f("UNDERRUN: exception during run: %s", error));
            System.out.println(f("  frames written before gap %d; position at gap start %d, at resume %d (advanced %d"
                            + " frames = %.1f ms of a 400 ms sleep); position beyond frames written before the gap: %b",
                    writtenA, atA.pos(), atResume.pos(), atResume.pos() - atA.pos(),
                    framesToMs(atResume.pos() - atA.pos(), 44100), atResume.pos() > writtenA));
            System.out.println("  flags during the gap: " + flags(s.sampler.window(aEnd, resume))
                    + "; flags 20..200 ms after resume: " + flags(s.sampler.window(resume + 20_000_000L,
                            resume + 200_000_000L)));
            List<ProbeSampler.Row> empty = s.sampler.window(resume - 150_000_000L, resume);
            long posGapMin = Long.MAX_VALUE;
            long posGapMax = Long.MIN_VALUE;
            for (ProbeSampler.Row r : empty) {
                posGapMin = Math.min(posGapMin, r.pos());
                posGapMax = Math.max(posGapMax, r.pos());
            }
            System.out.println(f("  last 150 ms of the gap (buffer empty): position %d..%d (spread %d frames), available"
                    + " %d of %d", posGapMin, posGapMax, posGapMax - posGapMin,
                    empty.get(empty.size() - 1).availFrames(), s.bufferFrames));
            List<ProbeSampler.Row> after = s.sampler.window(resume, resume + 100_000_000L);
            long maxJump = 0;
            for (int i = 1; i < after.size(); i++) {
                maxJump = Math.max(maxJump, after.get(i).pos() - after.get(i - 1).pos());
            }
            ProbeSampler.Row fin = s.sampler.rows.get(s.sampler.rows.size() - 1);
            System.out.println(f("  first 100 ms after resume: largest single position step %d frames (%.1f ms);"
                            + " frames written in total %d, final position %d (difference %d)",
                    maxJump, framesToMs(maxJump, 44100), writtenTotal, fin.pos(), writtenTotal - fin.pos()));
            System.out.println(f("  write calls %d (short %d, zero %d)", s.stats.calls, s.stats.shortWrites,
                    s.stats.zeroWrites));
        } catch (RuntimeException e) {
            error = e.toString();
            System.out.println("UNDERRUN: exception: " + error);
        } finally {
            s.line.close();
        }
    }

    // ------------------------------------------------------------------ reuse

    private static void reuse() throws Exception {
        reserve("reuse", 2.0);
        for (int rate : new int[] {44100, 48000}) {
            AudioFormat fmt = format(rate);
            byte[] tone = ProbeTone.segment(rate, 0.25);
            SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
            for (int i = 1; i <= 4; i++) {
                if (i == 4) {
                    line = AudioSystem.getSourceDataLine(fmt); // a fresh line object for comparison
                }
                long t0 = System.nanoTime();
                line.open(fmt, bufferBytes(rate));
                double openMs = ms(System.nanoTime() - t0);
                long posOpen = line.getLongFramePosition();
                line.start();
                ProbeSampler dummy = new ProbeSampler(line);
                WriteStats st = new WriteStats();
                long tw = System.nanoTime();
                writeFully(line, tone, dummy, st);
                long tFirst = -1;
                long deadline = System.nanoTime() + 1_000_000_000L;
                while (System.nanoTime() < deadline) {
                    if (line.getLongFramePosition() > 0) {
                        tFirst = System.nanoTime();
                        break;
                    }
                    sleep(1);
                }
                line.drain();
                long posEnd = line.getLongFramePosition();
                line.stop();
                line.close();
                System.out.println(f("REUSE %d Hz, %s open #%d: open %.1f ms, position after open %d, first nonzero"
                                + " position %.1f ms after first write, final position %d for %d frames written",
                        rate, i == 4 ? "fresh line" : "same line", i, openMs, posOpen,
                        tFirst < 0 ? Double.NaN : ms(tFirst - tw), posEnd, tone.length / FRAME_BYTES));
            }
        }
    }
}
