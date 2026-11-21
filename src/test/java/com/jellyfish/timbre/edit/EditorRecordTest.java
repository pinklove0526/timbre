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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jellyfish.timbre.audio.MixPlan;
import com.jellyfish.timbre.audio.StereoMixer;
import com.jellyfish.timbre.model.ChunkedSampleSource;
import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.MemorySampleSource;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * {@link Editor#addRecording}: one undoable "Record" entry. The oracle sample of frame f, channel c is
 * {@code (f * 3 + c * 1000 + 1) / 100_000f}, written by the test.
 */
class EditorRecordTest {

    private final Project project = new Project();
    private final History history = new History();
    private final Editor editor = new Editor(project, history, new Clipboard());

    private static float oracle(long f, int c) {
        return (f * 3 + c * 1000 + 1) / 100_000f;
    }

    private static ChunkedSampleSource recording(int rate, int channels, int frames) {
        ChunkedSampleSource.Builder b = new ChunkedSampleSource.Builder(rate, channels, 1000,
                ChunkedSampleSource.ChunkFactory.DEFAULT);
        float[][] block = new float[channels][700];
        for (int done = 0; done < frames; ) {
            int n = Math.min(700, frames - done);
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < n; i++) {
                    block[c][i] = oracle(done + i, c);
                }
            }
            b.append(block, n);
            done += n;
        }
        return b.build();
    }

    @Test
    void recordingAtFrameZeroOnAnEmptyProject() {
        ChunkedSampleSource src = recording(48000, 2, 3000);
        Optional<EditOutcome> out = editor.addRecording("Recording 1", src, 0);
        assertTrue(out.isPresent());
        assertEquals(Selection.between(0, 3000), out.get().selectionAfter());
        assertEquals("Record", out.get().command().name());
        assertEquals(Optional.of("Record"), history.undoName());
        assertEquals(OptionalInt.of(48000), project.sampleRate());
        assertEquals(1, project.tracks().size());
        Track t = project.tracks().get(0);
        assertEquals("Recording 1", t.name());
        assertEquals(2, t.channelCount());
        assertEquals(1, t.clips().size());
        assertEquals(new Clip(src, 0, 3000, 0), t.clips().get(0));
        assertTrue(editor.lastRefusal().isEmpty());
    }

    @Test
    void recordingAtANonZeroFrameRendersSilenceBeforeIt() {
        ChunkedSampleSource src = recording(44100, 2, 2500);
        EditOutcome out = editor.addRecording("Take", src, 5000).orElseThrow();
        assertEquals(Selection.between(5000, 7500), out.selectionAfter());
        Track t = project.tracks().get(0);
        assertEquals(5000, t.clips().get(0).timelineStart());
        SampleSource snap = t.snapshot();
        assertEquals(7500, snap.frameCount());
        float[][] all = new float[2][7500];
        snap.read(0, 7500, all, 0);
        for (int c = 0; c < 2; c++) {
            for (int i = 0; i < 7500; i++) {
                float expected = i < 5000 ? 0f : oracle(i - 5000, c);
                assertEquals(expected, all[c][i], "frame " + i + " channel " + c);
            }
        }
    }

    @Test
    void undoRemovesTheTrackAndFreesTheRateAndRedoRestoresTheSameTrack() {
        editor.addRecording("A", recording(44100, 1, 1200), 10).orElseThrow();
        Track t = project.tracks().get(0);
        assertTrue(editor.undo());
        assertTrue(project.tracks().isEmpty());
        assertEquals(OptionalInt.empty(), project.sampleRate());
        assertTrue(editor.redo());
        assertSame(t, project.tracks().get(0));
        assertTrue(editor.undo());
        // the rate is free again: another rate records fine
        assertTrue(editor.addRecording("B", recording(22050, 1, 100), 0).isPresent());
        assertEquals(OptionalInt.of(22050), project.sampleRate());
        assertFalse(history.canRedo(), "a new entry clears the redo");
    }

    @Test
    void anEmptyRecordingIsRefusedAndRecordsNothing() {
        ChunkedSampleSource empty = new ChunkedSampleSource.Builder(44100, 2).build();
        assertTrue(editor.addRecording("Empty", empty, 0).isEmpty());
        assertEquals(Optional.of("the recording is empty"), editor.lastRefusal());
        assertFalse(history.canUndo());
        assertTrue(project.tracks().isEmpty());
    }

    @Test
    void aSampleRateMismatchIsRefusedAndRecordsNothing() {
        editor.addRecording("48k", recording(48000, 2, 100), 0).orElseThrow();
        assertTrue(editor.addRecording("44k", recording(44100, 2, 100), 0).isEmpty());
        assertTrue(editor.lastRefusal().orElseThrow().startsWith("sample rate mismatch"),
                editor.lastRefusal().orElseThrow());
        assertEquals(1, project.tracks().size());
        assertTrue(editor.undo());
        assertFalse(history.canUndo(), "only the first recording was recorded");
    }

    @Test
    void negativeFrameThrowsAndTheRefusalIsClearedByTheNextCall() {
        assertTrue(editor.addRecording("x", new ChunkedSampleSource.Builder(44100, 1).build(), 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> editor.addRecording("x", recording(44100, 1, 10), -1));
        assertTrue(editor.lastRefusal().isEmpty());
        assertFalse(history.canUndo());
    }

    @Test
    void aMonoRecordingIntoAStereoProjectIsAccepted() {
        project.addTrack(Track.of("stereo", MemorySampleSource.copyOf(44100, new float[][] {{0.1f}, {0.2f}})));
        EditOutcome out = editor.addRecording("mono", recording(44100, 1, 50), 0).orElseThrow();
        assertEquals(2, project.tracks().size());
        assertEquals(1, project.tracks().get(1).channelCount());
        assertEquals(Selection.between(0, 50), out.selectionAfter());
    }

    @Test
    void thePlaybackPathMixesTheRecordedTrack() {
        editor.addRecording("stereo", recording(44100, 2, 3000), 100).orElseThrow();
        editor.addRecording("mono", recording(44100, 1, 1000), 2500).orElseThrow();
        MixPlan plan = MixPlan.of(project);
        assertEquals(3500, plan.length());
        StereoMixer mixer = new StereoMixer(plan, 512);
        float[] l = new float[512];
        float[] r = new float[512];
        for (long start = 0; start < 3500; start += 512) {
            int n = (int) Math.min(512, 3500 - start);
            mixer.mix(start, n, l, r);
            for (int i = 0; i < n; i++) {
                long f = start + i;
                float el = 0f;
                float er = 0f;
                if (f >= 100 && f < 3100) {
                    el += oracle(f - 100, 0);
                    er += oracle(f - 100, 1);
                }
                if (f >= 2500) {
                    el += oracle(f - 2500, 0);
                    er += oracle(f - 2500, 0);
                }
                assertEquals(el, l[i], "left " + f);
                assertEquals(er, r[i], "right " + f);
            }
        }
    }
}
