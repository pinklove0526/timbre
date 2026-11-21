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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleRateMismatchException;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Seeded random add / remove / edit / undo / redo against an oracle-lite: a plain {@code List<Integer>} of
 * track ids plus the rendered samples of every track ({@link Oracle}). Checks after every step: the project's
 * tracks are exactly the oracle ids, as the SAME Track objects (identity), and every track renders as the
 * oracle says.
 */
class TrackCommandsRandomTest {

    private static final int SEEDS = 40;
    private static final int OPS = 50;

    /** One oracle state: the track order and each track's samples. */
    private record State(List<Integer> ids, Map<Integer, float[][]> audio) {
    }

    @Test
    void randomTrackAndEditSequencesMatchTheOracle() {
        int adds = 0;
        int removes = 0;
        int edits = 0;
        int undos = 0;
        int redos = 0;
        int refusedRates = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(300 + seed);
            Project project = new Project();
            History history = new History();
            Editor editor = new Editor(project, history, new Clipboard());
            Map<Integer, Track> byId = new HashMap<>();
            Map<Integer, Integer> channelsById = new HashMap<>();
            State state = new State(List.of(), Map.of());
            Deque<State> undo = new ArrayDeque<>();
            Deque<State> redo = new ArrayDeque<>();
            int nextId = 1;
            for (int op = 0; op < OPS; op++) {
                String where = "seed " + seed + " step " + op;
                int kind = rnd.nextInt(10);
                State before = state;
                State after = null;
                if (kind <= 2 || state.ids().isEmpty() && kind < 7) { // add
                    int id = nextId++;
                    int rate = state.ids().isEmpty() && rnd.nextInt(3) == 0 ? 48000 : 44100;
                    int currentRate = state.ids().isEmpty() ? rate : byId.get(state.ids().get(0)).sampleRate();
                    if (!state.ids().isEmpty() && rnd.nextInt(6) == 0) {
                        rate = currentRate == 44100 ? 48000 : 44100; // a mismatching import
                    }
                    int ch = 1 + rnd.nextInt(2);
                    Track t = new Track("t" + id, rate, ch);
                    long at = rnd.nextInt(5);
                    for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
                        int frames = 5 + rnd.nextInt(30);
                        t.addClip(new Clip(Oracle.source(id * 10 + k, ch, frames, rate), 0, frames, at));
                        at += frames + rnd.nextInt(6);
                    }
                    if (rate != currentRate) {
                        assertThrows(SampleRateMismatchException.class,
                                () -> history.execute(new AddTrackCommand(project, t)), where);
                        refusedRates++;
                    } else {
                        history.execute(new AddTrackCommand(project, t));
                        byId.put(id, t);
                        channelsById.put(id, ch);
                        List<Integer> ids = new ArrayList<>(state.ids());
                        ids.add(id);
                        Map<Integer, float[][]> audio = new HashMap<>(state.audio());
                        audio.put(id, Oracle.render(t.clips(), ch));
                        after = new State(List.copyOf(ids), audio);
                        adds++;
                    }
                } else if (kind <= 4) { // remove a random non-empty subset
                    List<Integer> chosen = new ArrayList<>();
                    for (int id : state.ids()) {
                        if (rnd.nextInt(3) == 0) {
                            chosen.add(id);
                        }
                    }
                    if (chosen.isEmpty()) {
                        chosen.add(state.ids().get(rnd.nextInt(state.ids().size())));
                    }
                    List<Track> tracks = new ArrayList<>();
                    for (int i = chosen.size() - 1; i >= 0; i--) { // any order is allowed
                        tracks.add(byId.get(chosen.get(i)));
                    }
                    history.execute(RemoveTrackCommand.forTracks(project, tracks));
                    List<Integer> ids = new ArrayList<>(state.ids());
                    ids.removeAll(chosen);
                    after = new State(List.copyOf(ids), state.audio());
                    removes++;
                } else if (kind <= 6) { // an edit on a random subset (delete or silence)
                    List<Integer> targets = new ArrayList<>();
                    for (int id : state.ids()) {
                        if (rnd.nextBoolean()) {
                            targets.add(id);
                        }
                    }
                    if (targets.isEmpty()) {
                        targets.add(state.ids().get(0));
                    }
                    long a = rnd.nextInt(60);
                    long b = a + 1 + rnd.nextInt(20);
                    boolean silence = rnd.nextBoolean();
                    Map<Integer, float[][]> audio = new HashMap<>(state.audio());
                    boolean changed = false;
                    for (int id : targets) {
                        float[][] old = audio.get(id);
                        float[][] neu = silence ? Oracle.zeroRange(old, a, b) : Oracle.removeRange(old, a, b);
                        changed |= !Oracle.same(old, neu);
                        audio.put(id, neu);
                    }
                    List<Track> tracks = targets.stream().map(byId::get).toList();
                    Optional<EditOutcome> out = assertDoesNotThrow(() -> silence
                            ? editor.silence(new Selection(a, b), tracks) : editor.delete(new Selection(a, b), tracks),
                            where);
                    assertEquals(changed, out.isPresent(), where + ": edit changed something?");
                    if (changed) {
                        after = new State(state.ids(), audio);
                        edits++;
                    }
                } else if (kind <= 8) { // undo
                    boolean did = history.undo();
                    assertEquals(!undo.isEmpty(), did, where + ": undo availability");
                    if (did) {
                        redo.push(state);
                        state = undo.pop();
                        undos++;
                    }
                } else { // redo
                    boolean did = history.redo();
                    assertEquals(!redo.isEmpty(), did, where + ": redo availability");
                    if (did) {
                        undo.push(state);
                        state = redo.pop();
                        redos++;
                    }
                }
                if (after != null) {
                    undo.push(before);
                    redo.clear();
                    state = after;
                }
                check(project, state, byId, channelsById, where);
            }
        }
        System.out.println("TrackCommandsRandomTest: adds " + adds + ", removes " + removes + ", edits " + edits
                + ", undos " + undos + ", redos " + redos + ", refused imports " + refusedRates);
        assertTrue(adds > 50 && removes > 30 && edits > 30 && undos > 30 && redos > 10 && refusedRates > 5,
                "the mix exercised too little");
    }

    private static void check(Project project, State state, Map<Integer, Track> byId,
            Map<Integer, Integer> channelsById, String where) {
        List<Track> tracks = project.tracks();
        assertEquals(state.ids().size(), tracks.size(), where + ": track count");
        for (int i = 0; i < tracks.size(); i++) {
            int id = state.ids().get(i);
            assertSame(byId.get(id), tracks.get(i), where + ": track " + i + " is not the oracle's object t" + id);
            Oracle.assertSame(state.audio().get(id), Oracle.render(tracks.get(i).clips(), channelsById.get(id)),
                    where + ", track t" + id);
        }
        assertEquals(tracks.isEmpty(), project.sampleRate().isEmpty(), where + ": the rate exists iff tracks exist");
    }
}
