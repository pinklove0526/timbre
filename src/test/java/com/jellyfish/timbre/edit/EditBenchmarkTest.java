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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Opt-in benchmark ({@code -Dtimbre.bench=true}; logs only, no assertions): 2000 random edits (cut, copy,
 * paste, delete, silence, split, undo, redo) on 3 tracks of 3000 clips each. Reports milliseconds per
 * operation (median and 95th percentile) and the approximate heap retained by the history (used heap with
 * the history minus used heap after clearing it, each after System.gc(); the JVM does not guarantee a full
 * collection, so the number is approximate). Writes {@code build-logs/m4a/bench.txt}.
 */
class EditBenchmarkTest {

    @Test
    void editBenchmark() throws IOException {
        assumeTrue("true".equals(System.getProperty("timbre.bench")), "opt-in: -Dtimbre.bench=true");
        Random rnd = new Random(42);
        Project project = new Project();
        List<Track> tracks = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            Track track = new Track("t" + t, 44100, 1 + t % 2);
            List<MemorySampleSource> sources = new ArrayList<>();
            for (int s = 0; s < 50; s++) {
                sources.add(MemorySampleSource.copyOf(44100, new float[1 + t % 2][2000]));
            }
            List<Clip> clips = new ArrayList<>();
            long at = 0;
            for (int c = 0; c < 3000; c++) {
                int count = 200 + rnd.nextInt(1000);
                clips.add(new Clip(sources.get(rnd.nextInt(50)), rnd.nextInt(2000 - count + 1), count, at));
                at += count + rnd.nextInt(300);
            }
            track.replaceClips(clips);
            project.addTrack(track);
            tracks.add(track);
        }
        History history = new History();
        Editor editor = new Editor(project, history, new Clipboard());
        long baseline = usedHeap();
        long[] nanos = new long[2000];
        int[] kinds = new int[8];
        for (int i = 0; i < nanos.length; i++) {
            long end = 0;
            for (Track t : tracks) {
                List<Clip> c = t.clips();
                end = Math.max(end, c.isEmpty() ? 0 : c.get(c.size() - 1).timelineEnd());
            }
            long a = (long) (rnd.nextDouble() * end);
            long b = Math.min(end, a + 1 + rnd.nextInt(20_000));
            Selection sel = new Selection(a, b);
            List<Track> targets = new ArrayList<>();
            for (Track t : tracks) {
                if (rnd.nextBoolean()) {
                    targets.add(t);
                }
            }
            if (targets.isEmpty()) {
                targets.add(tracks.get(rnd.nextInt(3)));
            }
            int kind = rnd.nextInt(8);
            kinds[kind]++;
            long t0 = System.nanoTime();
            switch (kind) {
                case 0 -> editor.cut(sel, targets);
                case 1 -> editor.copy(sel, targets);
                case 2 -> editor.paste(a, targets);
                case 3 -> editor.delete(sel, targets);
                case 4 -> editor.silence(sel, targets);
                case 5 -> editor.split(a, targets);
                case 6 -> editor.undo();
                default -> editor.redo();
            }
            nanos[i] = System.nanoTime() - t0;
        }
        long withHistory = usedHeap();
        int clipsNow = 0;
        for (Track t : tracks) {
            clipsNow += t.clips().size();
        }
        history.clear();
        long cleared = usedHeap();
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        String report = String.format(Locale.ROOT,
                "M4a edit benchmark: 3 tracks x 3000 clips, 2000 random operations (seed 42)%n"
                + "operation mix (cut, copy, paste, delete, silence, split, undo, redo): %s%n"
                + "ms per operation: median %.3f, p95 %.3f, max %.3f%n"
                + "clips at the end: %d%n"
                + "used heap MB: before the edits %.1f, with the history %.1f, after history.clear() %.1f%n"
                + "approximate heap retained by the history: %.1f MB (System.gc() based, approximate)%n",
                Arrays.toString(kinds), sorted[sorted.length / 2] / 1e6, sorted[(int) (sorted.length * 0.95)] / 1e6,
                sorted[sorted.length - 1] / 1e6, clipsNow, baseline / 1e6, withHistory / 1e6, cleared / 1e6,
                (withHistory - cleared) / 1e6);
        System.out.print(report);
        Path dir = Path.of("build-logs", "m4a");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("bench.txt"), report);
    }

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return r.totalMemory() - r.freeMemory();
    }
}
