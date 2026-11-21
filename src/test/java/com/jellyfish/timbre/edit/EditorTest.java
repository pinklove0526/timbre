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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import com.jellyfish.timbre.model.TrackSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Three tracks: m1 = mono source 1 (20 frames, samples 1001..1020) at 0; m2 = mono source 2 (12 frames,
 * 2001..2012) at 5; st = stereo source 3 (16 frames, 3001..3016 / -3001.5..-3016.5) at 0.
 */
class EditorTest {

    private MemorySampleSource s1;
    private MemorySampleSource s2;
    private MemorySampleSource s3;
    private Track m1;
    private Track m2;
    private Track st;
    private Project project;
    private History history;
    private Clipboard clipboard;
    private Editor raw;
    private Checked editor;
    private final AtomicInteger historyCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        s1 = Oracle.source(1, 1, 20, 8000);
        s2 = Oracle.source(2, 1, 12, 8000);
        s3 = Oracle.source(3, 2, 16, 8000);
        m1 = new Track("m1", 8000, 1);
        m1.addClip(new Clip(s1, 0, 20, 0));
        m2 = new Track("m2", 8000, 1);
        m2.addClip(new Clip(s2, 0, 12, 5));
        st = new Track("st", 8000, 2);
        st.addClip(new Clip(s3, 0, 16, 0));
        project = new Project();
        project.addTrack(m1);
        project.addTrack(m2);
        project.addTrack(st);
        history = new History();
        history.addListener(historyCalls::incrementAndGet);
        clipboard = new Clipboard();
        raw = new Editor(project, history, clipboard);
        editor = new Checked(raw);
    }

    private static float[] mono(Track t) {
        return Oracle.mono(t.clips());
    }

    @Test
    void cutFillsTheClipboardAndRemovesTheRangeOnAllTargetsInOneEntry() {
        Optional<EditOutcome> out = editor.cut(new Selection(3, 8), List.of(m1, m2));
        assertTrue(out.isPresent());
        assertEquals(Selection.cursor(3), out.get().selectionAfter());
        assertEquals("Cut", out.get().command().name());
        assertEquals(1, historyCalls.get(), "one history entry");
        assertEquals(Optional.of("Cut"), history.undoName());
        // clipboard: m1 frames 3..7 (source 1 from 3), m2 has a gap [3,5) then source 2 frames 0..2
        assertEquals(List.of(new Fragment(5, List.of(new Clip(s1, 3, 5, 0))),
                new Fragment(5, List.of(new Clip(s2, 0, 3, 2)))), clipboard.get());
        assertEquals(List.of(new Clip(s1, 0, 3, 0), new Clip(s1, 8, 12, 3)), m1.clips());
        assertEquals(List.of(new Clip(s2, 3, 9, 3)), m2.clips(), "the gap [3,5) went too: m2 starts at 3");
        assertEquals(List.of(new Clip(s3, 0, 16, 0)), st.clips(), "the stereo track was not a target");
        assertTrue(editor.undo());
        assertEquals(List.of(new Clip(s1, 0, 20, 0)), m1.clips());
        assertEquals(List.of(new Clip(s2, 0, 12, 5)), m2.clips());
        assertEquals(2, clipboard.get().size(), "the clipboard survives undo");
        assertTrue(editor.redo());
        assertEquals(List.of(new Clip(s2, 3, 9, 3)), m2.clips());
    }

    @Test
    void copyChangesNoTrackAndNoHistory() {
        Optional<EditOutcome> out = editor.copy(new Selection(10, 30), List.of(m1));
        assertTrue(out.isEmpty());
        assertTrue(editor.lastRefusal().isEmpty(), "copy is not a refusal");
        assertEquals(List.of(new Fragment(20, List.of(new Clip(s1, 10, 10, 0)))), clipboard.get());
        assertEquals(0, historyCalls.get());
        assertEquals(List.of(new Clip(s1, 0, 20, 0)), m1.clips());
        assertTrue(editor.copy(Selection.cursor(4), List.of(m1)).isEmpty());
        assertTrue(editor.lastRefusal().isPresent());
        assertEquals(1, clipboard.get().size(), "an empty copy leaves the clipboard alone");
    }

    @Test
    void deleteLeavesTheClipboardUnchanged() {
        editor.copy(new Selection(0, 2), List.of(m2));
        List<Fragment> before = clipboard.get();
        Optional<EditOutcome> out = editor.delete(new Selection(2, 6), List.of(m1));
        assertEquals(Selection.cursor(2), out.get().selectionAfter());
        assertEquals("Delete", out.get().command().name());
        assertSame(before, clipboard.get());
        assertArrayEquals(new float[] {1001, 1002, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016,
            1017, 1018, 1019, 1020}, mono(m1));
    }

    @Test
    void pasteIsPairwiseInProjectOrder() {
        // one fragment, three targets: only the first target gets it (m2 and st are mono+stereo; skip st)
        editor.copy(new Selection(0, 3), List.of(m1)); // 1001 1002 1003
        Optional<EditOutcome> one = editor.paste(1, List.of(m1, m2, st));
        assertEquals(new Selection(1, 4), one.get().selectionAfter());
        assertEquals("Paste", one.get().command().name());
        assertArrayEquals(new float[] {1001, 1001, 1002, 1003, 1002, 1003}, java.util.Arrays.copyOf(mono(m1), 6));
        assertEquals(List.of(new Clip(s2, 0, 12, 5)), m2.clips());
        assertEquals(List.of(new Clip(s3, 0, 16, 0)), st.clips());
        editor.undo();

        // two fragments, two targets
        editor.copy(new Selection(5, 7), List.of(m1, m2)); // m1: 1006 1007 ; m2: 2001 2002
        editor.paste(0, List.of(m1, m2));
        assertArrayEquals(new float[] {1006, 1007, 1001, 1002}, java.util.Arrays.copyOf(mono(m1), 4));
        assertArrayEquals(new float[] {2001, 2002, 0, 0, 0, 0, 0, 2001}, java.util.Arrays.copyOf(mono(m2), 8));
        editor.undo();

        // two fragments, one target: only the first fragment
        editor.paste(20, List.of(m2));
        assertEquals(List.of(new Clip(s2, 0, 12, 5), new Clip(s1, 5, 2, 20)), m2.clips(),
                "m1's fragment went to m2 (both mono) at frame 20 = after m2's end 17: a gap of 3");
        assertEquals(List.of(new Clip(s1, 0, 20, 0)), m1.clips());
    }

    @Test
    void aChannelMismatchRefusesTheWholePaste() {
        editor.copy(new Selection(0, 4), List.of(m1, m2)); // two mono fragments; m2's is silence only
        int calls = historyCalls.get();
        Optional<EditOutcome> out = editor.paste(2, List.of(m2, st)); // mono -> mono ok, silence -> stereo ok
        assertTrue(out.isPresent(), "a fragment without clips fits any track");
        editor.undo();
        calls = historyCalls.get();
        editor.copy(new Selection(5, 9), List.of(m1, m2)); // both fragments hold audio now
        out = editor.paste(2, List.of(m1, st)); // the second pair is mono -> stereo
        assertTrue(out.isEmpty());
        assertTrue(editor.lastRefusal().orElse("").contains("channels"), editor.lastRefusal().toString());
        assertEquals(calls, historyCalls.get(), "no history entry");
        assertEquals(List.of(new Clip(s1, 0, 20, 0)), m1.clips(), "the first pair did not change either");
        assertEquals(List.of(new Clip(s3, 0, 16, 0)), st.clips());
    }

    @Test
    void pasteBeyondTheEndCreatesTheGap() {
        editor.copy(new Selection(0, 2), List.of(m1));
        Optional<EditOutcome> out = editor.paste(25, List.of(m1));
        assertEquals(List.of(new Clip(s1, 0, 20, 0), new Clip(s1, 0, 2, 25)), m1.clips());
        assertEquals(new Selection(25, 27), out.get().selectionAfter());
    }

    @Test
    void pasteWithAnEmptyClipboardAndNoOpEditsReturnEmptyAndRecordNothing() {
        assertTrue(editor.paste(3, List.of(m1)).isEmpty());
        assertEquals(Optional.of("the clipboard is empty"), editor.lastRefusal());
        assertTrue(editor.delete(new Selection(40, 50), List.of(m1, m2)).isEmpty(), "beyond the content");
        assertTrue(editor.silence(new Selection(0, 5), List.of(m2)).isEmpty(), "m2's leading gap is silent already");
        assertTrue(editor.split(5, List.of(m2)).isEmpty(), "at a clip start");
        assertTrue(editor.cut(new Selection(30, 40), List.of(m1)).isEmpty());
        assertTrue(clipboard.isEmpty(), "a cut that changed nothing does not fill the clipboard");
        assertTrue(editor.trim(new Selection(0, 20), List.of(m1)).isEmpty(), "trim to the whole content");
        assertTrue(editor.delete(Selection.cursor(3), List.of(m1)).isEmpty(), "empty selection");
        assertEquals(Optional.of("the selection is empty"), editor.lastRefusal());
        assertTrue(editor.delete(new Selection(1, 2), List.of()).isEmpty(), "no targets");
        assertEquals(0, historyCalls.get());
        assertFalse(history.canUndo());
    }

    @Test
    void theSelectionAfterEachOperation() {
        Selection sel = new Selection(4, 9);
        assertEquals(sel, editor.silence(sel, List.of(m1)).get().selectionAfter());
        editor.undo(); // each operation starts from the original tracks
        assertEquals(new Selection(0, 5), editor.trim(sel, List.of(m1)).get().selectionAfter());
        editor.undo();
        assertEquals(Selection.cursor(2), editor.split(2, List.of(m1)).get().selectionAfter());
        editor.undo();
        assertEquals(Selection.cursor(1), editor.delete(new Selection(1, 2), List.of(m1)).get().selectionAfter());
        editor.undo();
        assertEquals(Selection.cursor(0), editor.cut(new Selection(0, 1), List.of(m1)).get().selectionAfter());
        editor.undo();
        editor.copy(new Selection(0, 3), List.of(m2, st)); // lengths 3 and 3
        editor.copy(new Selection(0, 7), List.of(m2)); // length 7
        assertEquals(new Selection(1, 8), editor.paste(1, List.of(m2, st)).get().selectionAfter(),
                "one fragment of length 7");
        assertEquals("Paste", history.undoName().get());
    }

    @Test
    void theTrimAndSplitResults() {
        editor.trim(new Selection(6, 9), List.of(m1, m2));
        assertArrayEquals(new float[] {1007, 1008, 1009}, mono(m1));
        assertArrayEquals(new float[] {2002, 2003, 2004}, mono(m2));
        editor.undo();
        editor.split(10, List.of(m1, m2, st));
        assertEquals(List.of(new Clip(s1, 0, 10, 0), new Clip(s1, 10, 10, 10)), m1.clips());
        assertEquals(List.of(new Clip(s2, 0, 5, 5), new Clip(s2, 5, 7, 10)), m2.clips());
        assertEquals(List.of(new Clip(s3, 0, 10, 0), new Clip(s3, 10, 6, 10)), st.clips());
        assertEquals(Optional.of("Split"), history.undoName());
        editor.undo();
        assertEquals(List.of(new Clip(s3, 0, 16, 0)), st.clips(), "one undo reverts all three tracks");
    }

    @Test
    void targetsAreValidated() {
        Track stranger = new Track("x", 8000, 1);
        stranger.addClip(new Clip(s1, 0, 5, 0));
        Selection sel = new Selection(1, 3);
        assertThrows(IllegalArgumentException.class, () -> raw.delete(sel, List.of(m1, m1)), "duplicate");
        assertThrows(IllegalArgumentException.class, () -> raw.delete(sel, List.of(stranger)), "foreign");
        assertThrows(IllegalArgumentException.class, () -> raw.delete(sel, List.of(m2, m1)), "order");
        assertThrows(IllegalArgumentException.class, () -> raw.paste(-1, List.of(m1)));
        assertThrows(IllegalArgumentException.class, () -> raw.split(-1, List.of(m1)));
        assertEquals(0, historyCalls.get());
        assertEquals(List.of(new Clip(s1, 0, 20, 0)), m1.clips());
    }

    @Test
    void anEditOnASubsetLeavesTheOtherTracksUntouched() {
        List<Clip> m2Before = m2.clips();
        List<Clip> stBefore = st.clips();
        editor.delete(new Selection(0, 10), List.of(m1));
        editor.silence(new Selection(2, 4), List.of(m1));
        editor.trim(new Selection(1, 6), List.of(m1));
        assertEquals(m2Before, m2.clips());
        assertEquals(stBefore, st.clips());
    }

    @Test
    void aSnapshotTakenBeforeAnEditIsUnchangedAfterTheEditAndAfterUndo() {
        TrackSnapshot snap = st.snapshot();
        float[][] before = new float[2][16];
        snap.read(0, 16, before, 0);
        editor.cut(new Selection(2, 12), List.of(st));
        float[][] afterEdit = new float[2][16];
        snap.read(0, 16, afterEdit, 0);
        assertArrayEquals(before[0], afterEdit[0]);
        assertArrayEquals(before[1], afterEdit[1]);
        assertEquals(16, snap.frameCount());
        editor.undo();
        float[][] afterUndo = new float[2][16];
        snap.read(0, 16, afterUndo, 0);
        assertArrayEquals(before[1], afterUndo[1]);
        assertEquals(16, st.snapshot().frameCount(), "after undo the track is whole again");
    }

    /**
     * The editor seen through assertions: an unexpected exception (for example a broken fragment invariant
     * under a mutation) becomes a test failure instead of a test error.
     */
    private static final class Checked {
        private final Editor e;

        Checked(Editor e) {
            this.e = e;
        }

        Optional<EditOutcome> cut(Selection s, List<Track> t) {
            return assertDoesNotThrow(() -> e.cut(s, t), "cut");
        }

        Optional<EditOutcome> copy(Selection s, List<Track> t) {
            return assertDoesNotThrow(() -> e.copy(s, t), "copy");
        }

        Optional<EditOutcome> paste(long at, List<Track> t) {
            return assertDoesNotThrow(() -> e.paste(at, t), "paste");
        }

        Optional<EditOutcome> delete(Selection s, List<Track> t) {
            return assertDoesNotThrow(() -> e.delete(s, t), "delete");
        }

        Optional<EditOutcome> silence(Selection s, List<Track> t) {
            return assertDoesNotThrow(() -> e.silence(s, t), "silence");
        }

        Optional<EditOutcome> trim(Selection s, List<Track> t) {
            return assertDoesNotThrow(() -> e.trim(s, t), "trim");
        }

        Optional<EditOutcome> split(long at, List<Track> t) {
            return assertDoesNotThrow(() -> e.split(at, t), "split");
        }

        boolean undo() {
            return assertDoesNotThrow(e::undo, "undo");
        }

        boolean redo() {
            return assertDoesNotThrow(e::redo, "redo");
        }

        Optional<String> lastRefusal() {
            return e.lastRefusal();
        }
    }
}
