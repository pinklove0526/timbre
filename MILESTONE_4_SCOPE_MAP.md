# Milestone 4 - Editing (scope map)

**Done when:** cut, copy, paste, delete, silence, trim, undo/redo, multi-track (from MILESTONE_SCOPE_MAP.md).

Work is split in two phases. **Phase A** is the headless edit core (clip operations, commands, history, clipboard, an `Editor` facade) proven against an independent array-based oracle. **Phase B** wires it into the UI (Edit menu, shortcuts, per-track selection, clip borders, plan rebuild). Phase B starts only after the user reviews Phase A.

## Settled decisions (user-approved defaults)
1. **Non-destructive editing.** Cut, delete and paste only split, remove and move clips that reference the SAME sources; no samples are ever copied. An edit is a command holding, per affected track, the clip list BEFORE and AFTER (clips are immutable records, so the snapshots are cheap); apply and revert just set the clip list, so there is no inverse logic to get wrong. History is unlimited.
2. **Operations.** Cut, copy, paste, delete, silence (turns the range into a gap, which already plays as silence), trim (keep only the selection) and split at the cursor. Delete and cut close the gap (ripple) on every target track; paste inserts and pushes later audio to the right.
3. **Per-track selection** (UI in Phase B): edits apply to the selected tracks, or to all tracks if none is selected; the `Editor` API therefore takes an explicit list of target tracks.
4. **Clipboard:** internal to the app only (clip fragments), no system clipboard.
5. **History:** one user action = one history entry; undo and redo; a "modified" flag (save point). Shortcuts and menus are Phase B.
6. **Locking:** all edits are locked while the engine is PLAYING or PAUSED (a UI rule in Phase B); after an edit the playback plan is rebuilt while stopped (Phase B).
7. **Shortcuts (Phase B):** Ctrl+X, Ctrl+C, Ctrl+V, Delete, Ctrl+L (silence), Ctrl+T (trim), Ctrl+I (split), Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z; an Edit menu.
8. **Clip borders (Phase B):** a thin boundary line at each clip start and end in the lanes. Dragging clips (time shift) is NOT in M4.
9. **Phases:** A headless core, B UI wiring.

### Rules decided by Claude (the user may veto)
- **a.** Trim removes everything outside `[from, to)` and moves the kept audio to start at timeline frame 0 (ripple, no leading gap).
- **b.** Paste inserts at the cursor or selection start and does NOT delete or replace a selected range.
- **c.** Paste maps clipboard fragments to the target tracks pairwise in project order, for the first `min(fragments, targets)` tracks; if ANY pair has a different channel count the whole paste is refused (nothing changes, nothing is recorded).
- **d.** The clipboard survives undo; cut and copy replace its content; edits that change no track record no history entry.
- **e.** Paste at a frame beyond the end of a track's content creates a silent gap before the pasted clips (no ripple needed).

## Non-goals (M4)
Dragging clips (time shift), the system clipboard, per-clip gain, crossfades, join or merge of adjacent clips, labels, snapping, sync-lock between tracks.

## Design
Classes marked **(B)** belong to Phase B; everything else is Phase A. Nothing in `edit` imports JavaFX, `ui` or `audio`; `model`, `audio` and `dsp` never import `edit`.

### package `com.jellyfish.timbre.model` (the only model change)
- `Track.replaceClips(List<Clip>)`: atomically replaces the clip list. Validates that the list is well-formed (sorted by `timelineStart`, non-overlapping, every `frameCount > 0`, `timelineStart >= 0`, each source's channel count and sample rate equal to the track's, exactly as `addClip` checks); throws `IllegalArgumentException` otherwise and leaves the track unchanged. Threading (documented): mutation only on one thread (the FX thread in the app); `snapshot()` returns an immutable `TrackSnapshot` that a later edit never changes.

### package `com.jellyfish.timbre.edit` (imports model only)
- `Command` (interface): `name()`, `apply()`, `revert()`.
- `TrackClipsCommand` (final): a `Track`, the clip list before and after; apply sets after, revert sets before.
- `CompoundCommand` (final): a name and a list of commands; apply applies all in order and, if one throws, reverts the ones already applied and rethrows (all-or-nothing); revert reverts in reverse order.
- `History` (final): `execute(Command)` (applies it, pushes it, clears redo), `undo()`, `redo()`, `canUndo()`, `canRedo()`, `undoName()`, `redoName()` (`Optional<String>`), `markSaved()`, `isModified()` (true when the current position differs from the save point; a save point lost because the redo stack past it was discarded counts as always modified until the next `markSaved()`), `clear()`, listeners (`HistoryListener.changed()`, invoked after every state change). Single-threaded (FX thread).
- `Fragment` (record): `long length`, `List<Clip> clips` (timeline starts relative to 0, inside `[0, length)`); immutable copy.
- `Clipboard` (final): an immutable `List<Fragment>` (one per source track in project order) or empty; set, get, clear; it keeps the referenced sources alive.
- `ClipOps` (final, static, pure functions on the clip list of ONE track): `copy(clips, from, to)` -> `Fragment` (the pieces intersecting `[from, to)`, re-based by `-from`, `length = to - from`, gaps preserved); `delete(clips, from, to)` (remove the range and shift everything after it left by `to - from`; clips straddling a boundary are cut: the left piece keeps `sourceStart`, the right piece gets `sourceStart` advanced); `silence(clips, from, to)` (remove the range, no shift); `trim(clips, from, to)` (keep `[from, to)`, re-based to start at 0); `insert(clips, at, Fragment)` (split a clip that straddles `at`, shift every clip that starts at or after `at` right by `fragment.length`, add the fragment's clips re-based by `+at`); `split(clips, at)` (split the clip with `timelineStart < at < timelineEnd` into two; no change otherwise); `isWellFormed(clips)`. Zero-length pieces are never produced; results are always sorted and non-overlapping.
- `EditOutcome` (record): `Command command`, `Selection selectionAfter`. Selection after: cut and delete -> `cursor(from)`; paste -> `between(at, at + longest pasted fragment length)`; silence -> the same selection; trim -> `between(0, kept length)`; split -> `cursor(at)`.
- `Editor` (final): `Editor(Project, History, Clipboard)`; `cut`, `copy`, `delete`, `silence`, `trim` take `(Selection, List<Track> targets)`, `paste` and `split` take `(long atFrame, List<Track> targets)`; each returns `Optional<EditOutcome>` (empty = nothing changed: an empty selection for range operations, no track changed, an empty clipboard, or a refused paste; a refusal throws nothing, its reason is available through `lastRefusal()`). Targets must be distinct tracks of the project in project order (else `IllegalArgumentException`). A multi-track edit is ONE `CompoundCommand` and ONE history entry named "Cut", "Paste", "Delete", "Silence", "Trim" or "Split"; `undo()` and `redo()` delegate to the history and return whether anything happened. Copy never touches the history. Cut = copy to the clipboard + delete as one history entry. The editor knows nothing about playback; the UI locks it.

### Phase B **(B)**
Edit menu and shortcuts (decision 7) routed through the M3 `EditorActions`; per-track selection in the track headers (click to select, none selected = all tracks); edits locked while PLAYING or PAUSED; after every edit, undo or redo: rebuild the `MixPlan` while stopped, re-request pyramids for new sources (none in M4, since no samples are copied), repaint, apply `EditOutcome.selectionAfter`; clip borders in `WaveformPainter`; the window title shows the modified flag. Detailed design when Phase B is scoped.

## Test plan (Phase A)
- **Oracle** (test tree): per track a `float[][]` of the rendered samples over `[0, N)` with silence in gaps, built brute force from the clips with `SampleSource.read` (independent of `ClipOps` and `MixPlan`), plus array versions of every operation (`System.arraycopy`). Test sources have unique, asymmetric sample values per source and index.
- `ClipOpsTest` (hand-written literals), `OracleRandomTest` (at least 30 seeds, 1 to 3 tracks, mono and stereo, 3 to 6 sources per track, 40 operations each with random undo and redo), a playback-path check (`MixPlan` and `StereoMixer` against the oracle), a waveform-path check (`LaneSampler` DIRECT against brute-force min and max), `TrackSnapshot` immutability, `EditorTest`, `HistoryTest`, `TrackReplaceClipsTest`, the architecture guard extended for `edit`.
- Opt-in benchmark (`-Dtimbre.bench=true`): 2000 random operations on 3 tracks of 3000 clips; ms per operation and the approximate retained heap of the history.
- Mutation checks: `ClipOps.delete` shift, `insert`/`split` right-half `sourceStart`, history redo clearing or compound undo order, the lost save point, `copy` re-basing.

## A adaptations
Design versus the real model APIs. The only model change is `Track.replaceClips`; `Project`, `Clip`, `TrackSnapshot`, `MixPlan`, `StereoMixer` and `LaneSampler` are untouched.
1. **`Track`.** `addClip` and `replaceClips` share one compatibility check (sample rate and channel count, as before). Frame counts above 0 and starts of at least 0 are already guaranteed by the `Clip` record, so `replaceClips` checks order and overlap itself. The class comment now states the threading contract (mutation on one thread, snapshots immutable); the earlier "Milestone 1" wording is gone. `TrackSnapshot` already copied the clip array, so snapshots were immutable before; tests now prove it across edits and undo.
2. **`HistoryListener`** is the listener interface the design names; "exactly once per state change" means a `markSaved()` or `clear()` that changes nothing notifies nobody, and an undo or redo with nothing to do returns false without a notification.
3. **Copy returns an empty `Optional` always.** It records no command, so there is no `EditOutcome` to return; `lastRefusal()` stays empty after a successful copy and names the reason after an empty selection (the clipboard is then left alone).
4. **Paste fit check.** `Fragment` has no channel count (the design fixes its two components), so the check looks at the sources of the fragment's clips: channel count and sample rate must match the target track. A fragment without clips (copied silence) fits any track. A sample-rate mismatch is refused too (otherwise `Track.replaceClips` would throw inside the command).
5. **A cut that changes nothing** (for example a range beyond the content) records nothing and leaves the clipboard unchanged, like every other no-op.
6. **An empty target list** is a refusal ("no target tracks"), not an exception; invalid targets (foreign, duplicate, out of order) and a negative paste or split frame throw `IllegalArgumentException` before anything changes.
7. **Every edit is a `CompoundCommand`**, also for a single track; its parts are only the tracks whose clip list actually changed.
8. **Trim's selection after** is `[0, selection length)`, even when the kept audio is shorter (the selected range is kept as a range, its tail is silence).
9. **Ranges** with `from < 0` are clamped to 0 inside `ClipOps` (a `Selection` can never be negative, so this only matters for direct calls).
10. **Tests.** The oracle lives in the test tree (`edit/Oracle`). `ClipOpsTest`, `EditorTest` and `OracleRandomTest` call the code under test through `assertDoesNotThrow`, so an exception caused by a broken invariant shows up as a clean failure (needed for mutation 5, see results).

## Phase A results
Branch `feature/pinklove0526/m4a-edit-core`, from develop `12f37a3` (merge of PR #11, Phase B2). Logs and predictions in `build-logs/m4a` (git-ignored; `predictions.txt` pairs each prediction with its result).
- **Suite:** 438 run, 0 failures, 0 errors, 27 skipped (the 26 earlier opt-in methods plus the edit benchmark), 36.3 s (`11-full-test`); executed 411, up from 369 (+42: `TrackReplaceClipsTest` 4, `ClipOpsTest` 15, `HistoryTest` 7, `EditorTest` 12, `OracleRandomTest` 3, architecture guard +1). 0 compiler warnings after deleting the compiled classes. The edit classes, `TrackReplaceClipsTest` and the architecture guard ran three times (44 tests each, 0 failures).
- **Oracle:** 40 seeds x 40 operations (1 to 3 tracks, mono and stereo, 3 to 6 sources per track): 452 applied edits, 160 undos, 32 redos, 64 refused pastes, every step equal to the array oracle; a full unwind and redo per seed. Playback path (`MixPlan` + `StereoMixer`, 6 seeds) and waveform path (`LaneSampler` DIRECT at 1, 2.5 and 3 frames per pixel, 6 seeds) are exactly equal to the oracle (tolerance 0).
- **Benchmark** (opt-in, `bench.txt`): 3 tracks x 3000 clips, 2000 random operations: median 0.029 ms, p95 0.302 ms, max 16.6 ms per operation; the history retained about 44 MB (used heap 50.5 MB with the history, 6.8 MB after `clear()`; `System.gc()`-based, approximate).
- **Mutations** (37 tests of the four edit classes; sha256 restored each time):

  | Mutation | Failing tests |
  |---|---|
  | `delete` forgets the ripple shift | 10 (5 ClipOps, 2 Editor, 3 Oracle) |
  | `insert` does not advance the right half's `sourceStart` | 6 |
  | `History.execute` keeps the redo stack | 4 |
  | `CompoundCommand.revert` in forward order | 1 |
  | the lost save point is ignored | 1 |
  | `copy` forgets the re-basing | 12 (after the test fix below) |

  The `copy` mutation was first caught with 8 failures and 4 errors: `Fragment`'s own invariant check threw inside `EditorTest`. `EditorTest` now goes through an `assertDoesNotThrow` wrapper, and the rerun gave 12 failures, 0 errors. The variant "delete shifts by `to - from + 1`" was not used: it would build a clip with a negative start, which throws in `Clip` instead of failing an assertion.
- **Dist:** `-Pdist package` exit 0, `--version` prints `Timbre 0.1.0-SNAPSHOT`, the edit classes and the glass natives are in the universal jar, none of our test classes (`13-dist` to `15-jar-list`).
- **Deviations:** the benchmark mix leaves out trim (a random trim collapses a 3000-clip track and makes the rest of the run meaningless); tests were run per group of classes rather than strictly per class.

## Phase B - editing UI
Branch `feature/pinklove0526/m4b-editing-ui`, from develop `8f9d4bc` (merge of PR #12, Phase A). No sound in this phase; every playing test uses the fake sink.

### Settled decisions (user-approved defaults; decisions 6 and 11 partly added by the user and flagged)
1. **Track selection.** A plain click on a track header selects only that track; Ctrl/Cmd+click toggles a track and sets the anchor; Shift+click selects the range from the anchor to the clicked track (Ctrl/Cmd+Shift+click adds the range); with no anchor Shift behaves as a plain click. Selected headers are drawn darker. Clicking in a lane or pressing Esc does not change the track selection. Removed tracks drop out of the selection. Edits apply to the selected tracks, or to ALL tracks when none is selected. Track selection is not part of the undo history.
2. **Undo/redo restore the time selection** from before/after the edit (a selection trail beside the History); the track selection is not restored.
3. **Add/remove tracks**, headless first (Step 0): `Project` removal and insertion; undoable `AddTrackCommand` / `RemoveTrackCommand`. File > Open WAV is an undoable "Import". Track > Remove Track removes the SELECTED tracks (needs an explicit selection, never "all"), one history entry "Remove Track".
4. **Clear tracks stays non-undoable:** it stops the engine first, clears the History, the Clipboard, the selection trail and the track selection, resets the time selection to the cursor at 0 and the axis to the empty-project default, and marks the History saved.
5. **Edit menu:** Undo <name> / Redo <name> with live names ("Undo Cut"; plain "Undo" when empty and disabled), then Cut, Copy, Paste, Delete, Silence, Trim, Split. Shortcuts: Ctrl/Cmd+X, C, V; Delete and Backspace (both delete); Ctrl/Cmd+L (silence), T (trim), I (split); Ctrl/Cmd+Z undo; Ctrl/Cmd+Y and Ctrl/Cmd+Shift+Z redo. Menu accelerators where the B2 design used them; the scene filter only for keys that cannot be accelerators; everything calls the same `EditorActions` methods.
6. **Locking:** while PLAYING or PAUSED, Cut, Paste, Delete, Silence, Trim, Split, Undo, Redo, Import and Remove Track are disabled/ignored. Flagged (added by the user): Copy stays allowed while playing or paused, and track selection by header click stays allowed.
7. **Enabling rules:** Cut/Delete/Silence/Trim/Copy need a non-empty time selection and at least one track; Paste needs a non-empty clipboard and at least one target track; Split needs at least one track; Remove Track needs at least one explicitly selected track; Undo/Redo follow the History. `EditorActions.refreshEnabled()` runs after every action, History change, engine state change and track-selection change (a disabled item's accelerator does not fire).
8. **Clip borders:** a 1 px vertical line in `Theme.CLIP_BORDER` at each visible clip start and end inside the lane rows (rows 0..89), drawn after the bands/polyline and before the separator row; coinciding lines once.
9. **After every edit** (cut, paste, delete, silence, trim, split, undo, redo, import, remove): rebuild the playback plan from fresh snapshots (engine stopped), recompute the project length, re-clamp the axis, mark the waveform dirty, update the scrollbar, apply the time selection (the outcome's for edits, the trail's for undo/redo) and scroll its start into view by the minimum amount if needed. Pyramids are requested only for new sources (import).
10. **Window title:** "Timbre" plus " *" while `History.isModified()`; saved at startup and after Clear tracks (file saving is M7).
11. **Refused edits** show their reason in a message label at the right of the status bar until the next action; never blocking. Flagged (added by the user).

Non-goals: dragging clips, system clipboard, join/merge of clips, labels, snapping, per-clip gain, crossfades, project save/load (M7), HiDPI check.

### Design
- **Step 0 (headless):** `Project.insertTrack(index, track)`, `removeTrack(track)` (returns the index), `indexOf(track)`; the project sample rate is now DERIVED from the tracks (before: a field set by the first `addTrack` and never cleared), so removing the last track frees it and re-inserting restores it. `edit.AddTrackCommand` ("Import"), `edit.RemoveTrackCommand` (index recorded at apply time; `forTracks` builds one `CompoundCommand` "Remove Track" in descending index order).
- **`ui.viewmodel`:** `TrackSelection` (identity, anchor, `click`, `retain`, `clear`, `selected`, `targets`), `ClipBoundaries` (border columns), `SelectionTrail` (before/after selections in lockstep with the History), `TransportRules.editsLocked` and `copyAllowed`.
- **`ui`:** `EditorModel` gains the `Editor`, `History`, `Clipboard`, `TrackSelection`, `SelectionTrail`, a message property and a modified property; `EditorActions` gains cut, copy, paste, deleteSelection, silence, trim, split, undo, redo, removeSelectedTracks, selectTrack, importFile(Path), clearTracks and refreshEnabled, plus the after-edit pipeline; `EditorPane` gains the Edit and Track menus, Open WAV through `importFile`, a title property and the status-bar message label; `TrackHeaderView` shows the selected state (`Theme.TRACK_SELECTED_BACKGROUND`, style class "selected") and forwards header presses; `LaneView`/`WaveformPainter` draw the clip borders; `Theme` gains `CLIP_BORDER` and `TRACK_SELECTED_BACKGROUND`.

### Test plan
Headless: `TrackCommandsTest`, `TrackCommandsRandomTest` (oracle-lite, 40 seeds), `TrackSelectionTest`, `ClipBoundariesTest`, `SelectionTrailTest` (with a lockstep test against a real History), `TransportRulesTest` update, architecture guard. Opt-in `EditorEditingFxTest` (`-Dtimbre.fx=true`), cases (a) to (m), PNGs in `build-logs/m4b/png`. Mutations: `insertTrack` off by one and the removal order (Step 0); the Cut lock, the plan rebuild, `SelectionTrail.undo`, the track range, the `ClipBoundaries` end line (FX and headless).

### B adaptations
Design versus the real code. Beyond the Step 0 `Project` change, model, audio and dsp are untouched.
1. **Project sample rate.** Before, `Project` kept the rate in a field set by the first `addTrack` and never cleared. It is now derived from the tracks (none for an empty project, otherwise the first track's rate), so removing the last track frees it and undo restores it with no extra state. `addTrack` now also refuses a track that is already in the project (it goes through `insertTrack`).
2. **`RemoveTrackCommand.forTracks`** builds the one "Remove Track" compound (descending index order). Each part records its index at apply time, so the apply order does not actually matter (the ascending-order mutation is equivalent, see results).
3. **Import path.** `EditorActions.importFile(Path)` reads the file on the calling thread (the test hook). File > Open WAV keeps reading on its background thread and then calls `EditorActions.importTrack(Track)` on the FX thread; from there both paths are the same.
4. **Messages.** `EditorActions` no longer takes a message consumer: every message goes to `EditorModel.setMessage` and shows in the status bar's right-hand label. The B2 message label in the transport row is gone. Any `Editor.lastRefusal()` is shown, including "nothing to change".
5. **Plan ownership.** `EditorActions` owns the plan (`rebuildPlan()`, `plan()`); `EditorPane` no longer keeps its own.
6. **Clear tracks** empties the SAME `Project` object (the `Editor` keeps its reference) and resets history (saved), clipboard, trail, track selection, time selection, axis and message in `EditorModel.clear()`.
7. **Axis re-clamp keeps the zoom.** After an edit the scroll position is limited to the new range and the zoom is kept (the M3 rule: a project-length change never re-fits). A new sample rate or an empty project gets the fitted axis. A first version also clamped the zoom; the FX test caught it (see results).
8. **Rows follow the project.** `EditorPane` keeps one `TrackRowView` per `Track` by identity and re-syncs on `EditorModel.tracksVersion()`. It installs the header press handler and shows the selected state with `Theme.TRACK_SELECTED_BACKGROUND` / `TRACK_HEADER_BACKGROUND` as a `Background`. The old CSS string constant was replaced by the colour.
9. **Title.** `EditorPane.titleProperty()` plus `setTitleBase(String)`: `TimbreApp` passes the app name and binds the stage title, so `ui` does not depend on `app`.
10. **Enabling.** `EditorActions` exposes enabling properties that the menu items bind (Delete, Silence and Trim share Cut's rule) and live Undo/Redo labels.
11. **Shortcuts.** The edit shortcuts are Edit-menu accelerators (they do not fire while the item is disabled). Backspace and Ctrl/Cmd+Shift+Z cannot be a second accelerator, so the B2 scene filter handles them and calls the same methods, which check the same rules.
12. **Clip borders of touching clips.** With the settled formula, two touching clips give one shared column only when the lines coincide (a left clip narrower than the column). Usually they give two neighbouring columns (end line at `c - 1`, start line at `c`). `ClipBoundariesTest` asserts both.
13. **Ctrl/Cmd+Shift+click without an anchor** is a plain click, as Shift+click is.
14. **Track menu.** Decision 3 reads "New Track > Remove Track"; implemented as a "Track" menu with "Remove Track" (the menu that list items in the prompt name).
15. **Status bar.** The cursor and selection readouts keep their preferred width; a long message is cut with an ellipsis. Found visually in the refusal PNG, now asserted in case (l).

### Phase B results
Branch `feature/pinklove0526/m4b-editing-ui`, from develop `8f9d4bc`. Logs and predictions in `build-logs/m4b` (git-ignored; `predictions.txt` pairs every prediction with its result).
- **Suite:** 475 run, 0 failures, 0 errors, 40 skipped (27 earlier plus the 13 methods of the opt-in `EditorEditingFxTest`), 36.2 s (`14-full-test`), 0 compiler warnings after deleting the compiled classes. Executed 435, up from 411 (+24: `TrackCommandsTest` 5, `TrackCommandsRandomTest` 1, `TrackSelectionTest` 9, `ClipBoundariesTest` 6, `SelectionTrailTest` 3; `TransportRulesTest` gained assertions, not tests). After Step 0 alone: 444 run, 27 skipped. The new headless classes plus `TransportRulesTest` and the architecture guard ran three times (31 tests each, 0 failures).
- **Step 0 random test:** 40 seeds x 50 steps: 567 imports, 350 removals, 201 edits, 379 undos, 59 redos, 168 refused (mismatching-rate) imports; track order, Track identity and rendered samples match the oracle after every step.
- **FX:** `EditorEditingFxTest` 13/13 in three consecutive final runs (cases a to m); B1 `EditorRenderFxTest` 10/10 and B2 `EditorInteractionFxTest` 10/10. The first run had 3 failures: one literal of mine (case f), and two (c, d) from a real defect (the axis re-clamp also clamped the zoom, adaptation 7), fixed in the code.
- **Mutations** (assertion failures only, sha256 restored each time):

  | Mutation | Failing tests |
  |---|---|
  | S1 `insertTrack` at index + 1 | 4 headless |
  | S2 removal in ascending order | none: equivalent mutant (indices recorded at apply time) |
  | S2b removal revert appends at the end (replaces S2) | 3 headless |
  | F1 Cut skips the editing lock | FX e |
  | F2 no plan rebuild after an edit | FX b |
  | F3 `SelectionTrail.undo` returns "after" | FX b, c, d + `SelectionTrailTest` 3 |
  | F4 track range one short | FX a, h + `TrackSelectionTest` 4 |
  | F5 end line at `column(end)` | FX c, d, k, m + `ClipBoundariesTest` 5 of 6 |
  | F6 (extra) status readouts may shrink | FX l |
- **Changed existing assertions** (all in B1 `EditorRenderFxTest`, because clip borders now draw over rows 0..89 of each clip's first and last column; every other assertion is unchanged): (a) DIRECT and PEAKS, columns 0 and 999; (b) placeholder and silence, columns 0 and 999; (c) both stereo lanes, columns 0 and 999; (d) columns 100, 299, 500 and 599; (e) the left part of dot 0 at column 0 (the start border of clip [0, 16)); (h) before and after readiness, columns 0 and 999. Each now expects `Theme.CLIP_BORDER` on rows 0..89 and the separator on row 90.
- **PNGs** (`build-logs/m4b/png`): `a-header-selected`, `b-after-cut`, `c-after-paste`, `d-silence`, `d-split`, `k-after-cut`, `l-refusal`, `m-borders`.
- **Dist:** `-Pdist package` exit 0, `--version` prints `Timbre 0.1.0-SNAPSHOT`, natives for all three platforms and the new classes in the jar, none of our test classes (`18-dist` to `20-jar-list`).
- **Deviations:** the Step 0 code was written before the docs (Step 1); the test helper `awaitState` polls with 1 ms sleeps on the test thread (as in B2) while the engine changes state; the status-bar readout fix and its assertion were added beyond the prompt.
- **Not verified:** real mouse and keyboard (all events synthetic), real audio, HiDPI, the macOS Command key mapping, a real window title bar, very large histories (the Phase A benchmark covered 2000 operations).

## Manual acceptance (user)
Run `source ./dev-env.sh; mvn -o javafx:run`, files from `build-logs/manual`:
1. `beeps_stereo_44100`: select the second beep, Ctrl+X: it vanishes, later beeps move left, clip borders show; play it; Ctrl+Z restores picture, sound and the selection; Ctrl+Y cuts again.
2. Copy a beep, click elsewhere, Ctrl+V: the beep is inserted and selected, borders appear; play it; undo.
3. Delete and Backspace both delete; Silence on a beep leaves a silent gap; Trim keeps only the selection, moved to the start; Split at the cursor adds a border and the sound is unchanged.
4. Open a second file as another track: a header click selects it, Ctrl+click toggles, Shift+click selects a range; with one track selected only that track is edited, with none selected all tracks are; pasting a stereo fragment into a mono track shows the reason in the status bar and changes nothing.
5. While playing or paused: the Edit items except Copy are greyed and their shortcuts do nothing, Copy works, header selection works; after Stop everything works; the labels read "Undo Cut" and so on.
6. File > Open WAV is undoable ("Undo Import"); Track > Remove Track removes the selected tracks and undo restores them in place; Clear tracks wipes the history (Undo greyed) and the title loses its "*"; the title shows "*" after an edit and loses it when undone back to the start.
7. `long_stereo_44100_10min`: cut a chunk from the middle: the UI stays responsive, and playback of the edited result starts and ends correctly.
8. Zoom in to sample level: clip borders stay on the right columns while scrolling.
9. Apply many edits, then undo them all: sound and picture are back to the original.
