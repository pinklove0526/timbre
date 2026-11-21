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

import com.jellyfish.timbre.model.Clip;
import com.jellyfish.timbre.model.Project;
import com.jellyfish.timbre.model.SampleSource;
import com.jellyfish.timbre.model.Selection;
import com.jellyfish.timbre.model.Track;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * The edit facade: turns a user action on a selection and a list of target tracks into ONE history entry.
 *
 * <p>Every edit computes each target's new clip list with {@link ClipOps}, keeps only the tracks whose list
 * actually changes, and executes them as one {@link CompoundCommand} named "Cut", "Paste", "Delete",
 * "Silence", "Trim" or "Split". An edit that changes no track records nothing and returns an empty
 * {@link Optional}; so do refusals (an empty selection for a range operation, an empty clipboard, a paste
 * whose fragments do not fit the targets). A refusal throws nothing; {@link #lastRefusal()} says why.
 *
 * <p>Targets must be distinct tracks of the project, given in project order; otherwise the call throws
 * {@link IllegalArgumentException} before anything changes. An empty target list is a refusal.
 *
 * <p>The editor knows nothing about playback: the UI must not call it while the engine plays.
 *
 * <p><b>Threading:</b> single-threaded: use it on the JavaFX application thread only (the thread that owns
 * the tracks, the history and the clipboard).
 */
public final class Editor {

    /** The history name of {@link #addRecording}. */
    public static final String RECORD = "Record";

    private final Project project;
    private final History history;
    private final Clipboard clipboard;
    private String lastRefusal;

    /**
     * Creates the editor.
     *
     * @param project   the project whose tracks are edited
     * @param history   where edits are recorded
     * @param clipboard the app clipboard
     */
    public Editor(Project project, History history, Clipboard clipboard) {
        this.project = Objects.requireNonNull(project, "project");
        this.history = Objects.requireNonNull(history, "history");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    /** @return the history */
    public History history() {
        return history;
    }

    /** @return the clipboard */
    public Clipboard clipboard() {
        return clipboard;
    }

    /** @return why the last call returned empty, or empty if it did not refuse */
    public Optional<String> lastRefusal() {
        return Optional.ofNullable(lastRefusal);
    }

    // ------------------------------------------------------------------ operations

    /**
     * Copies the selection of every target to the clipboard (one fragment per target) and deletes it with
     * ripple, as one history entry. If no target changes, nothing happens (the clipboard keeps its content).
     *
     * @param selection the range
     * @param targets   the target tracks
     * @return the outcome; the selection after is the cursor at the range start
     */
    public Optional<EditOutcome> cut(Selection selection, List<Track> targets) {
        begin(selection, targets);
        if (refuseEmpty(selection) || refuseNoTargets(targets)) {
            return Optional.empty();
        }
        List<Fragment> fragments = new ArrayList<>(targets.size());
        for (Track t : targets) {
            fragments.add(ClipOps.copy(t.clips(), selection.startFrame(), selection.endFrame()));
        }
        Optional<EditOutcome> out = run("Cut", targets,
                t -> ClipOps.delete(t.clips(), selection.startFrame(), selection.endFrame()),
                Selection.cursor(selection.startFrame()));
        if (out.isPresent()) {
            clipboard.set(fragments);
        }
        return out;
    }

    /**
     * Copies the selection of every target to the clipboard (one fragment per target). Copy changes no
     * track and records nothing, so it always returns empty; with an empty selection the clipboard is left
     * alone and {@link #lastRefusal()} says so.
     *
     * @param selection the range
     * @param targets   the target tracks
     * @return always empty
     */
    public Optional<EditOutcome> copy(Selection selection, List<Track> targets) {
        begin(selection, targets);
        if (refuseEmpty(selection) || refuseNoTargets(targets)) {
            return Optional.empty();
        }
        List<Fragment> fragments = new ArrayList<>(targets.size());
        for (Track t : targets) {
            fragments.add(ClipOps.copy(t.clips(), selection.startFrame(), selection.endFrame()));
        }
        clipboard.set(fragments);
        return Optional.empty();
    }

    /**
     * Inserts the clipboard at a frame. Fragments go to the targets pairwise in order, for the first
     * {@code min(fragments, targets)} pairs. If any pair does not fit (a fragment clip's source has another
     * channel count or sample rate than its target track) the whole paste is refused. A fragment without
     * clips (copied silence) fits any track.
     *
     * @param atFrame the insert position, at least 0
     * @param targets the target tracks
     * @return the outcome; the selection after spans the longest pasted fragment from {@code atFrame}
     * @throws IllegalArgumentException if {@code atFrame} is negative or the targets are invalid
     */
    public Optional<EditOutcome> paste(long atFrame, List<Track> targets) {
        begin(Selection.cursor(Math.max(0, atFrame)), targets);
        if (atFrame < 0) {
            throw new IllegalArgumentException("atFrame must be >= 0: " + atFrame);
        }
        if (refuseNoTargets(targets)) {
            return Optional.empty();
        }
        List<Fragment> fragments = clipboard.get();
        if (fragments.isEmpty()) {
            lastRefusal = "the clipboard is empty";
            return Optional.empty();
        }
        int pairs = Math.min(fragments.size(), targets.size());
        Map<Track, Fragment> byTrack = new IdentityHashMap<>();
        long longest = 0;
        for (int i = 0; i < pairs; i++) {
            Track t = targets.get(i);
            Fragment f = fragments.get(i);
            for (Clip c : f.clips()) {
                if (c.source().channelCount() != t.channelCount() || c.source().sampleRate() != t.sampleRate()) {
                    lastRefusal = "clipboard fragment " + (i + 1) + " has " + c.source().channelCount()
                            + " channels at " + c.source().sampleRate() + " Hz, track \"" + t.name() + "\" has "
                            + t.channelCount() + " channels at " + t.sampleRate() + " Hz";
                    return Optional.empty();
                }
            }
            byTrack.put(t, f);
            longest = Math.max(longest, f.length());
        }
        List<Track> pasted = targets.subList(0, pairs);
        return run("Paste", pasted, t -> ClipOps.insert(t.clips(), atFrame, byTrack.get(t)),
                Selection.between(atFrame, atFrame + longest));
    }

    /**
     * Deletes the selection on every target with ripple; the clipboard is not touched.
     *
     * @param selection the range
     * @param targets   the target tracks
     * @return the outcome; the selection after is the cursor at the range start
     */
    public Optional<EditOutcome> delete(Selection selection, List<Track> targets) {
        begin(selection, targets);
        if (refuseEmpty(selection) || refuseNoTargets(targets)) {
            return Optional.empty();
        }
        return run("Delete", targets, t -> ClipOps.delete(t.clips(), selection.startFrame(), selection.endFrame()),
                Selection.cursor(selection.startFrame()));
    }

    /**
     * Turns the selection into silence (a gap) on every target; nothing moves.
     *
     * @param selection the range
     * @param targets   the target tracks
     * @return the outcome; the selection after is the same selection
     */
    public Optional<EditOutcome> silence(Selection selection, List<Track> targets) {
        begin(selection, targets);
        if (refuseEmpty(selection) || refuseNoTargets(targets)) {
            return Optional.empty();
        }
        return run("Silence", targets, t -> ClipOps.silence(t.clips(), selection.startFrame(), selection.endFrame()),
                selection);
    }

    /**
     * Keeps only the selection on every target and moves it to frame 0.
     *
     * @param selection the range
     * @param targets   the target tracks
     * @return the outcome; the selection after is {@code [0, selection length)}
     */
    public Optional<EditOutcome> trim(Selection selection, List<Track> targets) {
        begin(selection, targets);
        if (refuseEmpty(selection) || refuseNoTargets(targets)) {
            return Optional.empty();
        }
        return run("Trim", targets, t -> ClipOps.trim(t.clips(), selection.startFrame(), selection.endFrame()),
                Selection.between(0, selection.length()));
    }

    /**
     * Splits, on every target, the clip that strictly contains the frame.
     *
     * @param atFrame the split position
     * @param targets the target tracks
     * @return the outcome; the selection after is the cursor at {@code atFrame}
     * @throws IllegalArgumentException if {@code atFrame} is negative or the targets are invalid
     */
    public Optional<EditOutcome> split(long atFrame, List<Track> targets) {
        begin(Selection.cursor(Math.max(0, atFrame)), targets);
        if (atFrame < 0) {
            throw new IllegalArgumentException("atFrame must be >= 0: " + atFrame);
        }
        if (refuseNoTargets(targets)) {
            return Optional.empty();
        }
        return run("Split", targets, t -> ClipOps.split(t.clips(), atFrame), Selection.cursor(atFrame));
    }

    /**
     * Adds a recording as a new track at the end of the project: one clip covering the whole source
     * ({@code sourceStart} 0) at timeline frame {@code atFrame}, executed as ONE history entry named "Record" (an
     * {@link AddTrackCommand}). Undo removes the track; redo adds the same track again.
     *
     * <p>Refused (empty result, {@link #lastRefusal()} says why, nothing recorded) when the source has no frames or
     * when the project has tracks at another sample rate ("sample rate mismatch ..."). Any channel count is fine
     * (playback spreads mono to both sides).
     *
     * @param trackName the new track's name
     * @param source    the recorded audio
     * @param atFrame   the timeline position, at least 0 (the cursor or selection start)
     * @return the outcome; the selection after spans the recording {@code [atFrame, atFrame + frames)}
     * @throws IllegalArgumentException if {@code atFrame} is negative
     */
    public Optional<EditOutcome> addRecording(String trackName, SampleSource source, long atFrame) {
        Objects.requireNonNull(trackName, "trackName");
        Objects.requireNonNull(source, "source");
        lastRefusal = null;
        if (atFrame < 0) {
            throw new IllegalArgumentException("atFrame must be >= 0: " + atFrame);
        }
        long frames = source.frameCount();
        if (frames == 0) {
            lastRefusal = "the recording is empty";
            return Optional.empty();
        }
        OptionalInt rate = project.sampleRate();
        if (rate.isPresent() && rate.getAsInt() != source.sampleRate()) {
            lastRefusal = "sample rate mismatch: the project is at " + rate.getAsInt() + " Hz, the recording at "
                    + source.sampleRate() + " Hz";
            return Optional.empty();
        }
        Track track = new Track(trackName, source.sampleRate(), source.channelCount());
        track.addClip(new Clip(source, 0, frames, atFrame));
        Command command = new AddTrackCommand(project, track, RECORD);
        history.execute(command);
        return Optional.of(new EditOutcome(command, Selection.between(atFrame, atFrame + frames)));
    }

    /** @return true if an edit was undone */
    public boolean undo() {
        return history.undo();
    }

    /** @return true if an edit was redone */
    public boolean redo() {
        return history.redo();
    }

    // ------------------------------------------------------------------ helpers

    private void begin(Selection selection, List<Track> targets) {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(targets, "targets");
        lastRefusal = null;
        List<Track> all = project.tracks();
        int previous = -1;
        for (Track t : targets) {
            Objects.requireNonNull(t, "target track");
            int index = indexOf(all, t);
            if (index < 0) {
                throw new IllegalArgumentException("track \"" + t.name() + "\" is not in the project");
            }
            if (index == previous) {
                throw new IllegalArgumentException("track \"" + t.name() + "\" is given twice");
            }
            if (index < previous) {
                throw new IllegalArgumentException("targets are not in project order at \"" + t.name() + "\"");
            }
            previous = index;
        }
    }

    private static int indexOf(List<Track> tracks, Track t) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i) == t) {
                return i;
            }
        }
        return -1;
    }

    private boolean refuseEmpty(Selection selection) {
        if (selection.isEmpty()) {
            lastRefusal = "the selection is empty";
            return true;
        }
        return false;
    }

    private boolean refuseNoTargets(List<Track> targets) {
        if (targets.isEmpty()) {
            lastRefusal = "no target tracks";
            return true;
        }
        return false;
    }

    private Optional<EditOutcome> run(String name, List<Track> targets, Function<Track, List<Clip>> op,
            Selection after) {
        List<Command> parts = new ArrayList<>();
        for (Track t : targets) {
            List<Clip> before = t.clips();
            List<Clip> next = op.apply(t);
            if (!next.equals(before)) {
                parts.add(new TrackClipsCommand(name, t, before, next));
            }
        }
        if (parts.isEmpty()) {
            lastRefusal = "nothing to change";
            return Optional.empty();
        }
        Command command = new CompoundCommand(name, parts);
        history.execute(command);
        return Optional.of(new EditOutcome(command, after));
    }
}
