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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Developer tool that measures the audio INPUT path of this machine (Milestone 5 Phase A0). Not a test, not
 * shipped.
 *
 * <p>Usage: {@code CaptureProbe <inputs|open|diag|buffers|stall|stop|sustain> [--consent] [--out DIR]}. {@code inputs}
 * lists the input mixers and formats and never opens a line. The other modes open the default input and
 * refuse to run without {@code --consent}, which must only be given after the user agreed. They keep only
 * aggregated statistics (peak and RMS in dBFS per window, counts, timings, frame positions): captured audio is
 * never written to disk and never printed, and the raw bytes are dropped after each read. Every opening mode
 * reserves its capture seconds in {@code capture-ledger.txt} and refuses to run past the 40 s budget. {@code diag}
 * prints per-window aggregates (peak, RMS, DC mean, full-scale share, zero-crossing rate, an 8-bucket magnitude
 * histogram); {@code buffers} opens with small requested buffers and reports the read burst pattern.
 *
 * <p>Output: plain lines {@code key=value} or comma-separated rows with a leading tag, then a {@code SUMMARY}.
 * Each mode runs under a watchdog; a hang is reported and the probe exits with code 3 instead of hanging.
 *
 * <p>Threading: the main thread controls; where noted a reader thread blocks in {@code read()}.
 */
public final class CaptureProbe {

    /** Capture budget across all modes; raised from 30 s to 40 s with the user's consent (2026-11-21). */
    private static final double LEDGER_LIMIT_SECONDS = 40.0;
    private static final int BLOCK_FRAMES = 1024;
    private static Path out = Path.of("build-logs", "m5a0");
    private static boolean consent;

    private CaptureProbe() {
    }

    /**
     * Runs one probe mode.
     *
     * @param args the mode and options
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
            System.err.println("usage: CaptureProbe <inputs|open|diag|buffers|stall|stop|sustain> [--consent] [--out DIR]");
            System.exit(2);
        }
        Files.createDirectories(out);
        String m = mode;
        long budgetMs = switch (m) {
            case "inputs" -> 30_000;
            case "open" -> 25_000;
            case "diag" -> 15_000;
            case "buffers" -> 20_000;
            case "stall", "stop" -> 20_000;
            case "sustain" -> 25_000;
            default -> {
                System.err.println("unknown mode " + m);
                System.exit(2);
                yield 0;
            }
        };
        if (!m.equals("inputs") && !consent) {
            System.out.println("REFUSED mode=" + m + ": opens the microphone; run only with --consent after the user agreed");
            System.exit(4);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                switch (m) {
                    case "inputs" -> inputs();
                    case "open" -> open();
                    case "diag" -> diag();
                    case "buffers" -> buffers();
                    case "stall" -> stall();
                    case "stop" -> stopModes();
                    default -> sustain();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "capture-probe-" + m);
        worker.setDaemon(true);
        worker.start();
        worker.join(budgetMs);
        if (worker.isAlive()) {
            System.out.println("HANG mode=" + m + " still running after " + budgetMs + " ms: watchdog exit");
            System.out.flush();
            Runtime.getRuntime().halt(3);
        }
        if (failure.get() != null) {
            Throwable t = failure.get();
            System.out.println("EXCEPTION mode=" + m + " " + t.getClass().getName() + ": " + t.getMessage());
            System.out.flush();
            System.exit(5);
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ ledger

    private static synchronized void reserve(String label, double seconds) throws IOException {
        Path ledger = out.resolve("capture-ledger.txt");
        double used = 0;
        if (Files.exists(ledger)) {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                String[] p = line.split(",");
                if (p.length >= 2) {
                    used += Double.parseDouble(p[1]);
                }
            }
        }
        if (used + seconds > LEDGER_LIMIT_SECONDS) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "capture budget exceeded: used %.1f s, requested %.1f s, limit %.0f s", used, seconds,
                    LEDGER_LIMIT_SECONDS));
        }
        Files.writeString(ledger, String.format(Locale.ROOT, "%s,%.2f%n", label, seconds), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.printf(Locale.ROOT, "LEDGER reserve=%s seconds=%.2f used_before=%.2f limit=%.0f%n", label,
                seconds, used, LEDGER_LIMIT_SECONDS);
    }

    // ------------------------------------------------------------------ formats

    private static AudioFormat pcm16(float rate, int channels) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, 2 * channels, rate, false);
    }

    private static AudioFormat pcm24(float rate, int channels) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 24, channels, 3 * channels, rate, false);
    }

    private static AudioFormat float32(float rate, int channels) {
        return new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, rate, 32, channels, 4 * channels, rate, false);
    }

    private static String fmt(AudioFormat f) {
        return String.format(Locale.ROOT, "%s/%.0fHz/%dbit/%dch/%s", f.getEncoding(), f.getSampleRate(),
                f.getSampleSizeInBits(), f.getChannels(), f.isBigEndian() ? "BE" : "LE");
    }

    // ------------------------------------------------------------------ inputs (never opens)

    private static void inputs() throws Exception {
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.name=" + System.getProperty("os.name") + " os.version=" + System.getProperty("os.version"));
        System.out.println("property javax.sound.sampled.TargetDataLine="
                + System.getProperty("javax.sound.sampled.TargetDataLine"));
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        List<AudioFormat> formats = new ArrayList<>();
        for (float rate : new float[] {44100f, 48000f}) {
            for (int ch : new int[] {1, 2}) {
                formats.add(pcm16(rate, ch));
                formats.add(pcm24(rate, ch));
                formats.add(float32(rate, ch));
            }
        }
        String firstForDefault = null;
        int inputMixers = 0;
        for (Mixer.Info mi : infos) {
            Mixer mixer = AudioSystem.getMixer(mi);
            Line.Info[] targets = mixer.getTargetLineInfo();
            boolean hasCapture = false;
            for (Line.Info li : targets) {
                if (TargetDataLine.class.isAssignableFrom(li.getLineClass())) {
                    hasCapture = true;
                }
            }
            System.out.println("MIXER," + mi.getName().replace(',', ';') + "," + mi.getDescription().replace(',', ';')
                    + ",targetLineInfos=" + targets.length + ",capture=" + hasCapture);
            if (!hasCapture) {
                continue;
            }
            inputMixers++;
            for (Line.Info li : targets) {
                if (li instanceof DataLine.Info d) {
                    System.out.println("  TARGETINFO," + d.getLineClass().getSimpleName() + ",bufferMin="
                            + d.getMinBufferSize() + ",bufferMax=" + d.getMaxBufferSize() + ",formats="
                            + d.getFormats().length);
                }
            }
            for (AudioFormat f : formats) {
                boolean ok = mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, f));
                System.out.println("  SUPPORT," + fmt(f) + "," + ok);
                if (ok && firstForDefault == null && f.getSampleRate() == 44100f && f.getChannels() == 2
                        && f.getSampleSizeInBits() == 16) {
                    firstForDefault = mi.getName();
                }
            }
        }
        System.out.println("SYSTEM support (AudioSystem.isLineSupported over all mixers):");
        for (AudioFormat f : formats) {
            System.out.println("  SYSSUPPORT," + fmt(f) + ","
                    + AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, f)));
        }
        System.out.println("DEFAULT_GUESS=" + firstForDefault
                + " (the first mixer, in getMixerInfo order, that supports 44100/16/stereo; AudioSystem picks the"
                + " default this way when no property is set; confirmed only by the open mode, inputs never opens)");
        for (String[] cmd : new String[][] {{"pactl", "info"}, {"pactl", "list", "short", "sources"},
            {"arecord", "-l"}}) {
            readOnly(cmd);
        }
        System.out.println("SUMMARY inputs: mixers=" + infos.length + " inputMixers=" + inputMixers
                + " defaultGuess=" + firstForDefault);
    }

    private static void readOnly(String[] cmd) {
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
            System.out.println("  (not available: " + e.getMessage() + ")");
        }
    }

    // ------------------------------------------------------------------ shared capture helpers

    /** Opens the default input line in a format with the default buffer and reports what it got. */
    private static TargetDataLine openDefault(AudioFormat f, String tag) throws Exception {
        long t0 = System.nanoTime();
        TargetDataLine line;
        try {
            line = AudioSystem.getTargetDataLine(f);
            line.open(f);
        } catch (Exception e) {
            System.out.println("OPENFAIL," + tag + "," + fmt(f) + "," + e.getClass().getName() + "," + e.getMessage());
            throw e;
        }
        long openMs = (System.nanoTime() - t0) / 1_000_000;
        String mixerName = "?";
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (Arrays.asList(AudioSystem.getMixer(mi).getTargetLines()).contains(line)) {
                mixerName = mi.getName();
            }
        }
        System.out.printf(Locale.ROOT, "OPEN,%s,requested=%s,actual=%s,bufferBytes=%d,bufferFrames=%d,openMs=%d,"
                + "mixer=%s,lineClass=%s%n", tag, fmt(f), fmt(line.getFormat()), line.getBufferSize(),
                line.getBufferSize() / line.getFormat().getFrameSize(), openMs, mixerName.replace(',', ';'),
                line.getClass().getName());
        return line;
    }

    /** Statistics of a run of reads. */
    private static final class ReadStats {
        final List<Integer> sizes = new ArrayList<>();
        final List<Double> gapsMs = new ArrayList<>();
        int minAvailable = Integer.MAX_VALUE;
        int maxAvailable;
        long framesRead;
        long lastReturn;

        void returned(int bytes, int frameBytes, long now) {
            sizes.add(bytes);
            framesRead += bytes / frameBytes;
            if (lastReturn != 0) {
                gapsMs.add((now - lastReturn) / 1e6);
            }
            lastReturn = now;
        }

        void available(int a) {
            minAvailable = Math.min(minAvailable, a);
            maxAvailable = Math.max(maxAvailable, a);
        }

        String summary(String tag) {
            double[] s = sizes.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
            double[] g = gapsMs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            return String.format(Locale.ROOT, "READS,%s,count=%d,bytes_min=%.0f,bytes_median=%.0f,bytes_max=%.0f,"
                    + "gap_ms_median=%.2f,gap_ms_p95=%.2f,gap_ms_max=%.2f,available_min=%d,available_max=%d,"
                    + "framesRead=%d", tag, s.length, s.length == 0 ? 0 : s[0],
                    s.length == 0 ? 0 : CaptureMath.percentile(s, 50), s.length == 0 ? 0 : s[s.length - 1],
                    g.length == 0 ? 0 : CaptureMath.percentile(g, 50), g.length == 0 ? 0 : CaptureMath.percentile(g, 95),
                    g.length == 0 ? 0 : g[g.length - 1], minAvailable == Integer.MAX_VALUE ? -1 : minAvailable,
                    maxAvailable, framesRead);
        }
    }

    /** Per-100-ms window level printer: keeps only the window's float samples, then discards them. */
    private static final class Levels {
        final int channels;
        final int windowFrames;
        final float[][] window;
        int fill;
        int index;
        double maxPeak = Double.NEGATIVE_INFINITY;

        Levels(int rate, int channels) {
            this.channels = channels;
            this.windowFrames = rate / 10;
            this.window = new float[channels][windowFrames];
        }

        void add(float[][] block, int frames) {
            int at = 0;
            while (at < frames) {
                int n = Math.min(frames - at, windowFrames - fill);
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(block[c], at, window[c], fill, n);
                }
                fill += n;
                at += n;
                if (fill == windowFrames) {
                    StringBuilder sb = new StringBuilder("LEVEL,win=" + index);
                    for (int c = 0; c < channels; c++) {
                        double pk = CaptureMath.peakDbfs(window[c], 0, windowFrames);
                        double rms = CaptureMath.rmsDbfs(window[c], 0, windowFrames);
                        maxPeak = Math.max(maxPeak, pk);
                        sb.append(String.format(Locale.ROOT, ",ch%d_peak_dbfs=%s,ch%d_rms_dbfs=%s", c, db(pk), c, db(rms)));
                    }
                    System.out.println(sb);
                    index++;
                    fill = 0;
                    for (float[] w : window) {
                        Arrays.fill(w, 0f); // drop the samples
                    }
                }
            }
        }
    }

    private static String db(double v) {
        return Double.isInfinite(v) ? "-inf" : String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * Reads 1024-frame blocks until {@code seconds} of wall time after the first data (or a frame target),
     * feeding levels and stats; the raw bytes are overwritten by the next read and never stored.
     */
    private static long[] readFor(TargetDataLine line, double seconds, ReadStats st, Levels lv, long startNanos) {
        AudioFormat f = line.getFormat();
        int frameBytes = f.getFrameSize();
        byte[] buf = new byte[BLOCK_FRAMES * frameBytes];
        float[][] block = new float[f.getChannels()][BLOCK_FRAMES];
        long first = -1;
        long deadline = Long.MAX_VALUE;
        while (System.nanoTime() < deadline) {
            st.available(line.available());
            int n = line.read(buf, 0, buf.length);
            long now = System.nanoTime();
            if (n > 0 && first < 0) {
                first = now;
                deadline = now + (long) (seconds * 1e9);
            }
            if (n <= 0) {
                if (first < 0 && now - startNanos > 3_000_000_000L) {
                    break; // no data at all within 3 s
                }
                continue;
            }
            st.returned(n, frameBytes, now);
            if (lv != null && f.getSampleSizeInBits() == 16) {
                CaptureMath.pcm16LeToFloat(buf, 0, n / frameBytes, f.getChannels(), block);
                lv.add(block, n / frameBytes);
            }
            Arrays.fill(buf, (byte) 0);
        }
        return new long[] {first};
    }

    // ------------------------------------------------------------------ open

    private static void open() throws Exception {
        AudioFormat[] tries = {pcm16(44100f, 2), pcm16(44100f, 1), pcm16(48000f, 2)};
        String[] tags = {"44100-stereo", "44100-mono", "48000-stereo"};
        for (int i = 0; i < tries.length; i++) {
            AudioFormat f = tries[i];
            if (!AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) {
                System.out.println("SKIP," + tags[i] + ",unsupported by AudioSystem.isLineSupported");
                continue;
            }
            reserve("open-" + tags[i], 2.5);
            TargetDataLine line = openDefault(f, tags[i]);
            ReadStats st = new ReadStats();
            Levels lv = new Levels((int) f.getSampleRate(), f.getChannels());
            long beforeStart = System.nanoTime();
            line.start();
            long first = readFor(line, 2.0, st, lv, beforeStart)[0];
            long posEnd = line.getLongFramePosition();
            int availEnd = line.available();
            line.stop();
            line.close();
            System.out.println(st.summary(tags[i]));
            System.out.printf(Locale.ROOT, "TIMING,%s,firstDataMs=%s,expectedCadenceMs=%.1f,framePosition=%d,"
                    + "framesRead=%d,positionMinusRead=%d,availableAtEnd=%d,maxWindowPeakDbfs=%s%n", tags[i],
                    first < 0 ? "none" : String.format(Locale.ROOT, "%.1f", (first - beforeStart) / 1e6),
                    BLOCK_FRAMES * 1000.0 / f.getSampleRate(), posEnd, st.framesRead, posEnd - st.framesRead,
                    availEnd, db(lv.maxPeak));
        }
        System.out.println("SUMMARY open: done");
    }

    // ------------------------------------------------------------------ stall

    private static AudioFormat bestFormat() {
        AudioFormat f = pcm16(44100f, 2);
        if (AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) {
            return f;
        }
        return pcm16(48000f, 2);
    }

    private static void stall() throws Exception {
        AudioFormat f = bestFormat();
        reserve("stall", 3.5);
        TargetDataLine line = openDefault(f, "stall");
        int frameBytes = f.getFrameSize();
        ReadStats before = new ReadStats();
        long t0 = System.nanoTime();
        line.start();
        readFor(line, 1.0, before, null, t0);
        long posA = line.getLongFramePosition();
        int availA = line.available();
        long readA = before.framesRead;
        Thread.sleep(1000); // the deliberate stall: no reads for 1 s
        long posB = line.getLongFramePosition();
        int availB = line.available();
        System.out.printf(Locale.ROOT, "STALL,bufferBytes=%d,availableBeforeStall=%d,availableAtStallEnd=%d,"
                + "availableFull=%s,positionBeforeStall=%d,positionAtStallEnd=%d,positionAdvanced=%d%n",
                line.getBufferSize(), availA, availB, availB >= line.getBufferSize() - frameBytes, posA, posB,
                posB - posA);
        // the first reads after the stall: do they return at once (old buffered data) and how many bytes?
        byte[] buf = new byte[BLOCK_FRAMES * frameBytes];
        long readAfter = 0;
        for (int i = 0; i < 6; i++) {
            long s = System.nanoTime();
            int n = line.read(buf, 0, buf.length);
            readAfter += n / frameBytes;
            System.out.printf(Locale.ROOT, "AFTERSTALL,read=%d,bytes=%d,returnMs=%.2f,available=%d%n", i, n,
                    (System.nanoTime() - s) / 1e6, line.available());
        }
        Arrays.fill(buf, (byte) 0);
        ReadStats after = new ReadStats();
        readFor(line, 1.0, after, null, System.nanoTime());
        long posC = line.getLongFramePosition();
        long totalRead = readA + readAfter + after.framesRead;
        line.stop();
        line.close();
        System.out.println(before.summary("stall-before"));
        System.out.println(after.summary("stall-after"));
        System.out.printf(Locale.ROOT, "SUMMARY stall: positionEnd=%d framesRead=%d positionMinusRead=%d "
                + "(before the stall %d) bufferFrames=%d%n", posC, totalRead, posC - totalRead, posA - readA,
                line.getBufferSize() / frameBytes);
    }

    // ------------------------------------------------------------------ stop / close behaviour

    private static void stopModes() throws Exception {
        AudioFormat f = bestFormat();
        int frameBytes = f.getFrameSize();
        reserve("stop", 3.5);
        TargetDataLine line = openDefault(f, "stop-a");
        line.start();
        firstData(line, "a-initial");
        // (a) stop() while another thread is blocked in a long read
        blockedRead(line, "a-stop", () -> line.stop(), (int) (f.getSampleRate() * frameBytes)); // 1 s of bytes
        System.out.printf(Locale.ROOT, "A_AFTERSTOP,available=%d,active=%s,running=%s%n", line.available(),
                line.isActive(), line.isRunning());
        // (e) after stop(): is the rest still readable without blocking?
        int rest = line.available();
        byte[] restBuf = new byte[Math.max(frameBytes, rest - rest % frameBytes)];
        long s = System.nanoTime();
        int got = rest > 0 ? line.read(restBuf, 0, restBuf.length) : 0;
        Arrays.fill(restBuf, (byte) 0);
        System.out.printf(Locale.ROOT, "E_DRAINAFTERSTOP,available=%d,read=%d,ms=%.2f%n", rest, got,
                (System.nanoTime() - s) / 1e6);
        // (c) start() again on the same line
        long t = System.nanoTime();
        line.start();
        firstData(line, "c-restart", t);
        // (b) close() while a read is blocked
        blockedRead(line, "b-close", line::close, (int) (f.getSampleRate() * frameBytes));
        System.out.printf(Locale.ROOT, "B_AFTERCLOSE,open=%s%n", line.isOpen());
        // (d) reopen the same format
        TargetDataLine again = openDefault(f, "stop-d");
        long t2 = System.nanoTime();
        again.start();
        firstData(again, "d-reopen", t2);
        // (e2) the candidate stop sequence: stop, read what is available, close
        long s2 = System.nanoTime();
        again.stop();
        int avail = again.available();
        byte[] tail = new byte[Math.max(frameBytes, avail - avail % frameBytes)];
        int tailRead = avail > 0 ? again.read(tail, 0, tail.length) : 0;
        Arrays.fill(tail, (byte) 0);
        again.close();
        System.out.printf(Locale.ROOT, "E_SEQUENCE,stop_then_read_available_then_close,availableAfterStop=%d,"
                + "read=%d,totalMs=%.2f%n", avail, tailRead, (System.nanoTime() - s2) / 1e6);
        System.out.println("SUMMARY stop: done");
    }

    private static void firstData(TargetDataLine line, String tag) {
        firstData(line, tag, System.nanoTime());
    }

    private static void firstData(TargetDataLine line, String tag, long since) {
        int frameBytes = line.getFormat().getFrameSize();
        byte[] buf = new byte[BLOCK_FRAMES * frameBytes];
        int n = line.read(buf, 0, buf.length);
        Arrays.fill(buf, (byte) 0);
        System.out.printf(Locale.ROOT, "FIRSTDATA,%s,bytes=%d,ms=%.1f%n", tag, n, (System.nanoTime() - since) / 1e6);
    }

    /** A reader thread blocks in a long read; after 300 ms the action runs; reports how the read ended. */
    private static void blockedRead(TargetDataLine line, String tag, Runnable action, int bytes) throws Exception {
        byte[] buf = new byte[bytes - bytes % line.getFormat().getFrameSize()];
        AtomicLong returnedAt = new AtomicLong();
        AtomicLong result = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                result.set(line.read(buf, 0, buf.length));
            } catch (Throwable e) {
                thrown.set(e);
            }
            returnedAt.set(System.nanoTime());
        }, "probe-blocked-reader");
        reader.setDaemon(true);
        reader.start();
        Thread.sleep(300);
        boolean stillBlocked = reader.isAlive();
        long actAt = System.nanoTime();
        action.run();
        reader.join(2000);
        boolean hung = reader.isAlive();
        Arrays.fill(buf, (byte) 0);
        System.out.printf(Locale.ROOT, "BLOCKED,%s,blockedBeforeAction=%s,returned=%s,returnMsAfterAction=%s,"
                + "bytes=%s,requested=%d,thrown=%s%n", tag, stillBlocked, !hung,
                hung ? "-" : String.format(Locale.ROOT, "%.1f", (returnedAt.get() - actAt) / 1e6),
                result.get() == Long.MIN_VALUE ? "-" : Long.toString(result.get()), buf.length,
                thrown.get() == null ? "no" : thrown.get().getClass().getName() + ":" + thrown.get().getMessage());
    }

    // ------------------------------------------------------------------ sustain

    private static void sustain() throws Exception {
        AudioFormat f = bestFormat();
        reserve("sustain", 8.5);
        TargetDataLine line = openDefault(f, "sustain");
        int frameBytes = f.getFrameSize();
        byte[] buf = new byte[BLOCK_FRAMES * frameBytes];
        ReadStats st = new ReadStats();
        long beforeStart = System.nanoTime();
        line.start();
        long first = -1;
        long firstPos = 0;
        long framesAtFirst = 0;
        long end = Long.MAX_VALUE;
        List<String> samplesOfClock = new ArrayList<>();
        while (System.nanoTime() < end) {
            st.available(line.available());
            int n = line.read(buf, 0, buf.length);
            long now = System.nanoTime();
            Arrays.fill(buf, (byte) 0);
            if (n <= 0) {
                continue;
            }
            st.returned(n, frameBytes, now);
            if (first < 0) {
                first = now;
                firstPos = line.getLongFramePosition();
                framesAtFirst = st.framesRead;
                end = now + 8_000_000_000L;
            }
            if (st.sizes.size() % 43 == 0) { // about once a second
                samplesOfClock.add(String.format(Locale.ROOT, "CLOCK,wallMs=%.1f,framesRead=%d,position=%d",
                        (now - first) / 1e6, st.framesRead, line.getLongFramePosition()));
            }
        }
        long lastNow = st.lastReturn;
        long pos = line.getLongFramePosition();
        line.stop();
        line.close();
        samplesOfClock.forEach(System.out::println);
        System.out.println(st.summary("sustain"));
        double wallS = (lastNow - first) / 1e9;
        double framesSinceFirst = st.framesRead - framesAtFirst;
        double expected = wallS * f.getSampleRate();
        double ppm = (framesSinceFirst - expected) / expected * 1e6;
        System.out.printf(Locale.ROOT, "SUMMARY sustain: firstDataMs=%.1f wallSeconds=%.3f framesSinceFirst=%.0f "
                + "expectedFromWall=%.0f driftPpm=%.0f positionDelta=%d positionMinusRead=%d bufferFrames=%d%n",
                (first - beforeStart) / 1e6, wallS, framesSinceFirst, expected, ppm, pos - firstPos,
                pos - st.framesRead, line.getBufferSize() / frameBytes);
    }

    // ------------------------------------------------------------------ diag

    private static void diag() throws Exception {
        AudioFormat f = pcm16(44100f, 2);
        reserve("diag", 2.75);
        TargetDataLine line = openDefault(f, "diag");
        int rate = (int) f.getSampleRate();
        int win = rate / 10;
        int channels = f.getChannels();
        int frameBytes = f.getFrameSize();
        float[][] window = new float[channels][win];
        float[][] block = new float[channels][BLOCK_FRAMES];
        byte[] buf = new byte[BLOCK_FRAMES * frameBytes];
        double[] sumFs = new double[channels];
        double[] sumAbsDc = new double[channels];
        List<List<Double>> zcrs = new ArrayList<>();
        int[][] totalHist = new int[channels][8];
        for (int c = 0; c < channels; c++) {
            zcrs.add(new ArrayList<>());
        }
        int fill = 0;
        int windows = 0;
        long first = -1;
        long deadline = Long.MAX_VALUE;
        long t0 = System.nanoTime();
        line.start();
        while (System.nanoTime() < deadline) {
            int n = line.read(buf, 0, buf.length);
            long now = System.nanoTime();
            if (n <= 0) {
                if (first < 0 && now - t0 > 3_000_000_000L) {
                    break;
                }
                continue;
            }
            if (first < 0) {
                first = now;
                deadline = now + 2_500_000_000L;
            }
            int frames = n / frameBytes;
            CaptureMath.pcm16LeToFloat(buf, 0, frames, channels, block);
            Arrays.fill(buf, (byte) 0);
            int at = 0;
            while (at < frames) {
                int k = Math.min(frames - at, win - fill);
                for (int c = 0; c < channels; c++) {
                    System.arraycopy(block[c], at, window[c], fill, k);
                }
                fill += k;
                at += k;
                if (fill == win) {
                    for (int c = 0; c < channels; c++) {
                        float[] w = window[c];
                        double dc = CaptureMath.dcMean(w, 0, win);
                        double fs = CaptureMath.fullScaleShare(w, 0, win);
                        double zcr = CaptureMath.perSecond(CaptureMath.zeroCrossings(w, 0, win), win, rate);
                        int[] h = CaptureMath.histogram8(w, 0, win);
                        sumFs[c] += fs;
                        sumAbsDc[c] += Math.abs(dc);
                        zcrs.get(c).add(zcr);
                        for (int b = 0; b < 8; b++) {
                            totalHist[c][b] += h[b];
                        }
                        System.out.printf(Locale.ROOT, "DIAG,win=%d,ch=%d,peak_dbfs=%s,rms_dbfs=%s,dc=%.4f,"
                                + "fullscale_share=%.4f,zcr_per_s=%.0f,hist=%s%n", windows, c,
                                db(CaptureMath.peakDbfs(w, 0, win)), db(CaptureMath.rmsDbfs(w, 0, win)), dc, fs, zcr,
                                Arrays.toString(h).replace(", ", "/"));
                        Arrays.fill(w, 0f); // drop the samples
                    }
                    windows++;
                    fill = 0;
                }
            }
        }
        line.stop();
        line.close();
        for (int c = 0; c < channels; c++) {
            double[] z = zcrs.get(c).stream().mapToDouble(Double::doubleValue).sorted().toArray();
            System.out.printf(Locale.ROOT, "DIAGSUM,ch=%d,windows=%d,mean_fullscale_share=%.4f,mean_abs_dc=%.4f,"
                    + "zcr_median=%.0f,zcr_min=%.0f,zcr_max=%.0f,hist_total=%s%n", c, windows,
                    windows == 0 ? 0 : sumFs[c] / windows, windows == 0 ? 0 : sumAbsDc[c] / windows,
                    z.length == 0 ? 0 : CaptureMath.percentile(z, 50), z.length == 0 ? 0 : z[0],
                    z.length == 0 ? 0 : z[z.length - 1], Arrays.toString(totalHist[c]).replace(", ", "/"));
        }
        System.out.println("SUMMARY diag: windows=" + windows);
    }

    // ------------------------------------------------------------------ buffers

    private static void buffers() throws Exception {
        AudioFormat f = pcm16(44100f, 2);
        int frameBytes = f.getFrameSize();
        for (int ms : new int[] {40, 100}) {
            int requestedFrames = (int) Math.round(f.getSampleRate() * ms / 1000.0);
            reserve("buffers-" + ms + "ms", 2.25);
            long t0 = System.nanoTime();
            TargetDataLine line = AudioSystem.getTargetDataLine(f);
            line.open(f, requestedFrames * frameBytes);
            System.out.printf(Locale.ROOT, "BUFFER,%dms,requestedFrames=%d,grantedFrames=%d,openMs=%.1f%n", ms,
                    requestedFrames, line.getBufferSize() / frameBytes, (System.nanoTime() - t0) / 1e6);
            ReadStats st = new ReadStats();
            long beforeStart = System.nanoTime();
            line.start();
            long first = readFor(line, 2.0, st, null, beforeStart)[0];
            line.stop();
            line.close();
            System.out.println(st.summary("buffer-" + ms + "ms"));
            // bursts: reads separated by less than 2 ms belong to one burst
            List<Integer> burstSizes = new ArrayList<>();
            List<Double> waits = new ArrayList<>();
            int run = 1;
            for (double g : st.gapsMs) {
                if (g < 2.0) {
                    run++;
                } else {
                    burstSizes.add(run);
                    waits.add(g);
                    run = 1;
                }
            }
            burstSizes.add(run);
            double[] b = burstSizes.stream().mapToDouble(Integer::doubleValue).sorted().toArray();
            double[] w = waits.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            System.out.printf(Locale.ROOT, "BURSTS,%dms,firstDataMs=%s,bursts=%d,reads_per_burst_median=%.0f,"
                    + "reads_per_burst_max=%.0f,wait_ms_median=%.2f,wait_ms_p95=%.2f,wait_ms_max=%.2f%n", ms,
                    first < 0 ? "none" : String.format(Locale.ROOT, "%.1f", (first - beforeStart) / 1e6), b.length,
                    CaptureMath.percentile(b, 50), b[b.length - 1], w.length == 0 ? 0 : CaptureMath.percentile(w, 50),
                    w.length == 0 ? 0 : CaptureMath.percentile(w, 95), w.length == 0 ? 0 : w[w.length - 1]);
        }
        System.out.println("SUMMARY buffers: done");
    }
}
