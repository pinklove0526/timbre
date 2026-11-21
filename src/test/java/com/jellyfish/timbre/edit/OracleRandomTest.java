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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.MixPlan;
import com.jellyfish.timbre.audio.StereoMixer;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.ui.viewmodel.LaneColumns;
import com.jellyfish.timbre.ui.viewmodel.LaneSampler;
import com.jellyfish.timbre.ui.viewmodel.TimeAxis;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Seeded random edit sequences checked step by step against {@link Oracle}: after every operation each
 * track's rendering equals the oracle's array, after every undo the state before, after every redo the state
 * after; clip lists stay well-formed and only reference the sources created at the start (by identity). For a
 * few seeds the final project also goes through the playback path (MixPlan + StereoMixer) and the waveform
 * path (LaneSampler DIRECT), both compared with the oracle.
 */
class OracleRandomTest {

    private static final int RATE = 8000;
    private static final int SEEDS = 40;
    private static final int OPS = 40;

    /** Everything one seed needs. */
    private static final class World {
        final Project project = new Project();
        final List<Track> tracks = new ArrayList<>();
        final List<Integer> channels = new ArrayList<>();
        final Set<SampleSource> originals = Collections.newSetFromMap(new IdentityHashMap<>());
        final History history = new History();
        final Clipboard clipboard = new Clipboard();
        final Editor editor = new Editor(project, history, clipboard);
        List<float[][]> state = new ArrayList<>();
        List<float[][]> oracleClipboard = List.of();
        final Deque<List<float[][]>> undo = new ArrayDeque<>();
        final Deque<List<float[][]>> redo = new ArrayDeque<>();
        int applied;
        int refused;
        int undos;
        int redos;
    }

    private static World build(Random rnd) {
        World w = new World();
        int nTracks = 1 + rnd.nextInt(3);
        int id = 1;
        for (int t = 0; t < nTracks; t++) {
            int ch = 1 + rnd.nextInt(2);
            Track track = new Track("t" + t, RATE, ch);
            int nSources = 3 + rnd.nextInt(4);
            long at = rnd.nextInt(6);
            for (int s = 0; s < nSources; s++) {
                int frames = 5 + rnd.nextInt(56);
                SampleSource src = Oracle.source(id++, ch, frames, RATE);
                w.originals.add(src);
                int start = rnd.nextInt(frames / 2);
                int count = 1 + rnd.nextInt(frames - start);
                track.addClip(new Clip(src, start, count, at));
                at += count + (rnd.nextInt(3) == 0 ? 0 : rnd.nextInt(16));
            }
            w.project.addTrack(track);
            w.tracks.add(track);
            w.channels.add(ch);
            w.state.add(Oracle.render(track.clips(), ch));
        }
        return w;
    }

    private static long contentEnd(World w) {
        long end = 0;
        for (float[][] a : w.state) {
            end = Math.max(end, Oracle.length(a));
        }
        return end;
    }

    private static Selection randomSelection(Random rnd, World w) {
        long limit = contentEnd(w) + 20;
        long a = rnd.nextInt((int) limit + 1);
        if (rnd.nextInt(7) == 0) {
            return Selection.cursor(a);
        }
        long b = rnd.nextInt((int) limit + 1);
        return Selection.between(a, b);
    }

    /** A random subset in project order; rarely empty. */
    private static List<Integer> randomTargets(Random rnd, World w) {
        List<Integer> out = new ArrayList<>();
        if (rnd.nextInt(20) == 0) {
            return out;
        }
        for (int i = 0; i < w.tracks.size(); i++) {
            if (rnd.nextBoolean()) {
                out.add(i);
            }
        }
        if (out.isEmpty()) {
            out.add(rnd.nextInt(w.tracks.size()));
        }
        return out;
    }

    private static List<Track> tracksOf(World w, List<Integer> idx) {
        List<Track> out = new ArrayList<>();
        for (int i : idx) {
            out.add(w.tracks.get(i));
        }
        return out;
    }

    private static boolean anyChanged(List<float[][]> a, List<float[][]> b) {
        for (int i = 0; i < a.size(); i++) {
            if (!Oracle.same(a.get(i), b.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static void checkAll(World w, String where) {
        for (int i = 0; i < w.tracks.size(); i++) {
            Track t = w.tracks.get(i);
            List<Clip> clips = t.clips();
            assertTrue(ClipOps.isWellFormed(clips), where + ": track " + i + " is not well-formed: " + clips);
            for (Clip c : clips) {
                assertTrue(c.frameCount() > 0, where + ": zero-length clip");
                assertTrue(w.originals.contains(c.source()), where + ": a clip references a foreign source");
            }
            Oracle.assertSame(w.state.get(i), Oracle.render(clips, w.channels.get(i)), where + ", track " + i);
        }
    }

    /** Applies one random step to both the editor and the oracle and checks the result. */
    private static void step(Random rnd, World w, String where) {
        int kind = rnd.nextInt(10);
        List<float[][]> before = w.state;
        List<float[][]> next = new ArrayList<>(before);
        Optional<EditOutcome> out;
        boolean expectChange;
        switch (kind) {
            case 0, 1 -> { // undo or redo
                boolean isUndo = kind == 0;
                boolean did = assertDoesNotThrow(() -> isUndo ? w.editor.undo() : w.editor.redo(), where);
                Deque<List<float[][]>> from = isUndo ? w.undo : w.redo;
                Deque<List<float[][]>> to = isUndo ? w.redo : w.undo;
                assertEquals(!from.isEmpty(), did, where + ": " + (isUndo ? "undo" : "redo") + " availability");
                if (did) {
                    to.push(w.state);
                    w.state = from.pop();
                    if (isUndo) {
                        w.undos++;
                    } else {
                        w.redos++;
                    }
                }
                checkAll(w, where + (isUndo ? " undo" : " redo"));
                return;
            }
            case 2 -> { // copy
                Selection sel = randomSelection(rnd, w);
                List<Integer> idx = randomTargets(rnd, w);
                out = assertDoesNotThrow(() -> w.editor.copy(sel, tracksOf(w, idx)), where);
                assertTrue(out.isEmpty(), where + ": copy never records");
                if (!sel.isEmpty() && !idx.isEmpty()) {
                    List<float[][]> blocks = new ArrayList<>();
                    for (int i : idx) {
                        blocks.add(Oracle.block(before.get(i), sel.startFrame(), sel.endFrame()));
                    }
                    w.oracleClipboard = blocks;
                    assertEquals(idx.size(), w.clipboard.get().size());
                }
                checkAll(w, where + " copy");
                return;
            }
            case 3 -> { // paste
                long at = rnd.nextInt((int) contentEnd(w) + 21);
                List<Integer> idx = randomTargets(rnd, w);
                out = assertDoesNotThrow(() -> w.editor.paste(at, tracksOf(w, idx)), where);
                int pairs = Math.min(w.oracleClipboard.size(), idx.size());
                boolean refuse = w.oracleClipboard.isEmpty() || idx.isEmpty();
                for (int p = 0; p < pairs && !refuse; p++) {
                    float[][] block = w.oracleClipboard.get(p);
                    if (Oracle.hasAudio(block) && block.length != w.channels.get(idx.get(p))) {
                        refuse = true;
                    }
                }
                if (refuse) {
                    assertTrue(out.isEmpty(), where + ": paste must be refused");
                    assertTrue(w.editor.lastRefusal().isPresent(), where + ": a refusal has a reason");
                    w.refused++;
                    checkAll(w, where + " refused paste");
                    return;
                }
                long longest = 0;
                for (int p = 0; p < pairs; p++) {
                    int ti = idx.get(p);
                    float[][] block = w.oracleClipboard.get(p);
                    float[][] fitted = block.length == w.channels.get(ti) ? block
                            : new float[w.channels.get(ti)][block[0].length]; // silence fits any track
                    next.set(ti, Oracle.insertBlock(before.get(ti), at, fitted));
                    longest = Math.max(longest, block[0].length);
                }
                expectChange = anyChanged(before, next);
                if (out.isPresent()) {
                    assertEquals(Selection.between(at, at + longest), out.get().selectionAfter(), where);
                }
            }
            case 9 -> { // split
                long at = rnd.nextInt((int) contentEnd(w) + 5);
                List<Integer> idx = randomTargets(rnd, w);
                expectChange = false;
                for (int i : idx) {
                    for (Clip c : w.tracks.get(i).clips()) {
                        if (c.timelineStart() < at && at < c.timelineStart() + c.frameCount()) {
                            expectChange = true;
                        }
                    }
                }
                out = assertDoesNotThrow(() -> w.editor.split(at, tracksOf(w, idx)), where);
            }
            default -> { // 4 cut, 5 delete, 6 silence, 7 trim, 8 delete again (more ripple)
                Selection sel = randomSelection(rnd, w);
                List<Integer> idx = randomTargets(rnd, w);
                List<Track> targets = tracksOf(w, idx);
                long f = sel.startFrame();
                long t = sel.endFrame();
                List<float[][]> blocks = new ArrayList<>();
                for (int i : idx) {
                    float[][] a = before.get(i);
                    blocks.add(Oracle.block(a, f, t));
                    float[][] r = switch (kind) {
                        case 6 -> Oracle.zeroRange(a, f, t);
                        case 7 -> Oracle.keepRange(a, f, t);
                        default -> Oracle.removeRange(a, f, t);
                    };
                    if (!sel.isEmpty()) {
                        next.set(i, r);
                    }
                }
                expectChange = !sel.isEmpty() && anyChanged(before, next);
                out = assertDoesNotThrow(() -> switch (kind) {
                    case 4 -> w.editor.cut(sel, targets);
                    case 6 -> w.editor.silence(sel, targets);
                    case 7 -> w.editor.trim(sel, targets);
                    default -> w.editor.delete(sel, targets);
                }, where);
                if (kind == 4 && out.isPresent()) {
                    w.oracleClipboard = blocks;
                }
            }
        }
        assertEquals(expectChange, out.isPresent(), where + ": op " + kind + " changed something?");
        if (out.isPresent()) {
            w.undo.push(before);
            w.redo.clear();
            if (kind != 9) {
                w.state = next;
            }
            w.applied++;
        }
        checkAll(w, where + " op " + kind);
    }

    @Test
    void randomSequencesMatchTheOracleIncludingUndoAndRedo() {
        int applied = 0;
        int undos = 0;
        int redos = 0;
        int refused = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(1000 + seed);
            World w = build(rnd);
            checkAll(w, "seed " + seed + " start");
            for (int op = 0; op < OPS; op++) {
                step(rnd, w, "seed " + seed + " step " + op);
            }
            // unwind everything and come back: the start and the end states must reappear exactly
            List<float[][]> end = w.state;
            int unwound = 0;
            while (w.editor.undo()) {
                w.redo.push(w.state);
                w.state = w.undo.pop();
                unwound++;
            }
            assertTrue(w.undo.isEmpty(), "seed " + seed + ": the editor undid fewer steps than recorded");
            checkAll(w, "seed " + seed + " fully undone");
            for (int k = 0; k < unwound; k++) {
                assertTrue(w.editor.redo(), "seed " + seed + ": redo " + k + " of " + unwound);
                w.undo.push(w.state);
                w.state = w.redo.pop();
            }
            assertTrue(w.state == end, "seed " + seed + ": redo did not return to the end state");
            checkAll(w, "seed " + seed + " redone to the end state");
            applied += w.applied;
            undos += w.undos;
            redos += w.redos;
            refused += w.refused;
        }
        System.out.println("OracleRandomTest: " + SEEDS + " seeds x " + OPS + " ops: applied " + applied
                + ", undos " + undos + ", redos " + redos + ", refused pastes " + refused);
        assertTrue(applied > SEEDS * 5, "too few edits were applied: " + applied);
        assertTrue(refused > 0, "no refused paste was exercised");
    }

    @Test
    void thePlaybackPathMatchesTheOracle() {
        for (int seed = 0; seed < 6; seed++) {
            Random rnd = new Random(5000 + seed);
            World w = build(rnd);
            for (int op = 0; op < OPS; op++) {
                step(rnd, w, "seed " + seed + " step " + op);
            }
            MixPlan plan = MixPlan.of(w.project);
            int n = (int) plan.length();
            float[] expL = new float[n];
            float[] expR = new float[n];
            for (int t = 0; t < w.tracks.size(); t++) { // the mixer's rule: mono to both sides, else ch 0 and 1
                float[][] a = w.state.get(t);
                for (int i = 0; i < Math.min(n, Oracle.length(a)); i++) {
                    expL[i] += a[0][i];
                    expR[i] += a.length > 1 ? a[1][i] : a[0][i];
                }
            }
            int block = 37;
            StereoMixer mixer = new StereoMixer(plan, block);
            float[] l = new float[block];
            float[] r = new float[block];
            for (int start = 0; start < n; start += block) {
                int k = Math.min(block, n - start);
                mixer.mix(start, k, l, r);
                for (int i = 0; i < k; i++) {
                    assertEquals(expL[start + i], l[i], 0f, "seed " + seed + " left at " + (start + i));
                    assertEquals(expR[start + i], r[i], 0f, "seed " + seed + " right at " + (start + i));
                }
            }
            long longest = 0;
            for (float[][] a : w.state) {
                longest = Math.max(longest, lastNonZero(a));
            }
            assertTrue(plan.length() >= longest, "the plan is shorter than the audio");
        }
    }

    private static long lastNonZero(float[][] a) {
        for (int i = Oracle.length(a) - 1; i >= 0; i--) {
            for (float[] ch : a) {
                if (ch[i] != 0f) {
                    return i + 1;
                }
            }
        }
        return 0;
    }

    @Test
    void theWaveformPathMatchesTheOracle() {
        LaneSampler sampler = new LaneSampler();
        for (int seed = 0; seed < 6; seed++) {
            Random rnd = new Random(7000 + seed);
            World w = build(rnd);
            for (int op = 0; op < OPS; op++) {
                step(rnd, w, "seed " + seed + " step " + op);
            }
            for (double fpp : new double[] {1.0, 2.5, 3.0}) { // DIRECT mode: 1 <= framesPerPixel < 512
                for (int t = 0; t < w.tracks.size(); t++) {
                    float[][] a = w.state.get(t);
                    int width = (int) Math.ceil((Oracle.length(a) + 4) / fpp) + 1;
                    TimeAxis axis = new TimeAxis(RATE, fpp, 0);
                    for (int ch = 0; ch < a.length; ch++) {
                        LaneColumns cols = sampler.sample(w.tracks.get(t).clips(), ch, s -> null, axis, width);
                        assertEquals(LaneColumns.Mode.DIRECT, cols.mode());
                        for (int x = 0; x < width; x++) {
                            long f0 = (long) Math.floor(x * fpp);
                            long f1 = (long) Math.floor((x + 1) * fpp);
                            float lo = Float.POSITIVE_INFINITY;
                            float hi = Float.NEGATIVE_INFINITY;
                            for (long f = f0; f < f1; f++) {
                                float v = f < Oracle.length(a) ? a[ch][(int) f] : 0f;
                                lo = Math.min(lo, v);
                                hi = Math.max(hi, v);
                            }
                            String m = "seed " + seed + " fpp " + fpp + " track " + t + " ch " + ch + " column " + x;
                            assertEquals(lo, cols.min(x), 0f, m + " min");
                            assertEquals(hi, cols.max(x), 0f, m + " max");
                        }
                    }
                }
            }
        }
    }
}
