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

## Manual acceptance (user)
(stub; written with Phase B)
