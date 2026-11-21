# Timbre - Project Status (source of truth)

## Environment
- Mageia 10, x86_64; Oracle OpenJDK 17.0.2; Apache Maven 3.10.0; JavaFX 17.0.21.
- Maven local repository lives on removable exFAT media (see Risks).
- Machine-specific details are kept in the git-ignored ENVIRONMENT.local.md.

## Decisions
- JavaFX 17.0.21: newest 17.0.x on Maven Central (list checked 2026-11-09). Stays on the 17 line so it works on JDK 17.0.2.
- JUnit Jupiter 5.14.4 (newest 5.x on Central; 6.x exists but the prompt asks for 5.x).
- javafx-maven-plugin 0.0.8 (newest 0.0.x).
- Plugins: compiler 3.14.0, resources 3.3.1, surefire 3.5.4, shade 3.6.0.

- Planar float32 samples in memory; interleaving only at the file boundary.
- Own RIFF/WAVE codec in `audio` (no `javax.sound.sampled` in main code).
- Gaps between clips are silence.
- Project sample rate is fixed by the first track added.
- `Clip.split` returns the record `Clip.Split(Clip left, Clip right)` (changed from `List<Clip>` during M1 hardening, see Recorded retractions).
- Writer accepts at most 64 channels (same cap as the reader).
- Writer rejects over-4 GiB output with `UnsupportedAudioFormatException`.

## Deviations
- ./timbre already existed as a git clone (branch `feature/pinklove0526/project-scaffolding`, one "Initial commit", remote origin https://github.com/pinklove0526/timbre.git) containing LICENSE and a one-line README.md. WORKDIR itself is not inside a repository, so this was not the stop case; `git init` was skipped and the existing repo is used.
- The pre-existing README.md ("# timbre") is replaced by the README required by the prompt.
- The session was started outside ./timbre; commands were run after `cd` into the project directory (approved by the user for the housekeeping task too).
- `rm` is aliased to interactive here, so preflight probe files were first left behind and then removed with /bin/rm -f.
- LICENSE: M0 replaced the initial-commit file with the official download (differed only by one leading blank line); during housekeeping the initial commit's version was restored.
- M0's pom had no explicit aarch64 exclude (only win/linux/mac classifiers declared); explicit excludes for mac-aarch64 and linux-aarch64 were added to the dist shade artifactSet during housekeeping.
- Oracle repo was initially configured as requested; see Recorded retractions.
- M1: offline Maven runs skip the `clean` goal (the maven-clean-plugin was never downloaded); the git-ignored `target/` directory was removed by hand instead.
- M1: the opt-in real-world corpus test could not be exercised: the directory the user supplied contained no files. The test now reports "skipped" when no .wav files are found.
- M1: `WavReader` initial-allocation size guard was missing in streaming mode; caught by `WavReaderTest.sizeGuardRejectsFilesAboveTheLimit` and fixed before verification.
- repository.apache.org/snapshots (default repo of Maven 3.10) timed out once (warning only).
- JavaFX 17.0.21 class files are version 55 (Java 11), so it supports JDK 17.0.2.

## M1 hardening (branch feature/pinklove0526/m1-hardening, 2026-11-10)
- T1: gap proven (the two 24-bit mapping tests survived a low/mid byte swap). New `WavKnownAnswerTest` (15 tests, hand-written byte literals); all six 24-bit byte-swap mutations (decode and encode) are caught.
- T2: `ExternalToolInteropTest` (opt-in, `-Dtimbre.interop=true`) against ffmpeg 7.1.5 / ffprobe / sox 14.4.2: 69 cases (5 encodings x 4 channel counts x 3 rates, plus bext, id3 and non-seekable pipe variants), about 30 s. Read and write matched ffmpeg exactly (maximum difference 0.0) for every encoding including 32-bit PCM; sox (informational) differs from ours only on float32 files, by up to 2.3e-10.
- T3: `Clip.Split` record; new test that the halves tile the source range at every split position; mutation check done.
- T4: `WavDeclaredSizeTest` covers `read(Path)` and `read(InputStream)` with declared sizes 0x7FFFFFFF and 0xFFFFFFFF (about 0.3 MB and 0.8 MB allocated, budget 4 MiB).
- T5: full suite with `java.io.tmpdir` on the exFAT drive: all green, nothing left behind; `ATOMIC_MOVE` with `REPLACE_EXISTING` works on exFAT, so WavWriter's plain-move fallback is not taken there.
- Deviations: the interop class is skipped silently at class level (surefire does not count it as skipped); the T5 scratch directory could not be removed by the tool (see the hardening report) and is left for the user to delete.

## Risks
- The Maven local repo is on removable exFAT media. If the drive is unplugged/unmounted, builds must not run (dev-env.sh refuses to continue when the mount point is missing). exFAT has no Unix permissions/symlinks, so executable bits and some tooling behave differently.

## Milestone status
- M0 Skeleton: closed, window confirmed by user.
- M1 Audio core: closed (M1 and its hardening merged to develop by the user).
- M2 Playback: closed (M2 and its hardening merged to develop by the user; the user confirmed on 2026-11-13 that the three listening checks passed and the time readout stayed within the total at the end of playback).
- M3 Waveform UI: closed (accepted by the user, merged to develop; the user confirmed all eight manual acceptance items on 2026-11-21). Decisions: see MILESTONE_3_SCOPE_MAP.md (one peak pyramid per source with min, max and sum of squares, base block 256 frames, factor 4; one lane per channel; Ctrl+wheel zoom, Shift+wheel scroll; selection is a time range across all tracks; mouse selection locked while PLAYING or PAUSED; Play plays the selection, else cursor to end; engine gained play(fromFrame, toFrame)).
- M4 Editing: implemented, verified (see build-logs/m4a and m4b), awaiting user acceptance (the manual checklist is at the end of MILESTONE_4_SCOPE_MAP.md). Decisions: see MILESTONE_4_SCOPE_MAP.md (non-destructive: commands hold each track's clip list before and after, no samples copied; cut, copy, paste, delete, silence, trim, split; ripple delete and insert-paste; edits target an explicit track list (per-track selection in Phase B); internal clipboard of clip fragments; unlimited undo/redo with a modified flag; edits locked during playback (Phase B); Claude-decided rules a-e: trim re-bases to 0, paste inserts without replacing, pairwise paste refused on any channel mismatch, clipboard survives undo, paste beyond the end leaves a gap).

## Recorded retractions
- 2026-11-09: Oracle public Maven repository (oracle-public) removed from the Maven settings (repository, pluginRepository, profile and activeProfile). Reason: every artifact came from Maven Central; the Oracle repo supplied nothing and only added ~150 not-found warnings per cold build. Re-add it only if an Oracle-hosted artifact is ever needed.
- Earlier plan to use `javax.sound.sampled` for WAV I/O is replaced by an own RIFF codec (reasons: headless testability, 24-bit/float/extensible control, no JDK quirks). `javax.sound.sampled` remains planned for playback/recording only.

- 2026-11-10: `Clip.split` returning `List<Clip>` is replaced by the nested record `Clip.Split(Clip left, Clip right)`. Reason: a two-element list says nothing about which element is which and allows indexing mistakes; the record names the halves and cannot have the wrong size.
- 2026-11-13: M2 decision 3 (engine shape) amended to 3': short and zero writes are retried, the thread parks while paused, `flush()` is never called, the first block is rendered before `start()`. Reason: Phase A showed `write()` on a stopped line returns short/zero and `flush()` makes the position jump by the discarded frames (build-logs/m2-probe/07-pause.log, 08-flush.log).
- 2026-11-13: M2 decision 5 (transport) amended to 5': stop is `stop()` plus `close()` (reopen on next play), and the position is the device position capped by elapsed playing time and frames written during the first two buffer lengths after each start/resume, instead of the raw device clock with a written-minus-buffered fallback. Reason: after start, stop and flush the device position leads true playback by up to 200 ms and then stays flat; the written-minus-buffered estimate is the same quantity as the device position on this stack (build-logs/m2-probe/06-clock.log, 07-pause.log).
- 2026-11-13: M2 decision 6 (tests) amended to 6': the fake sink reproduces the Phase A position artifacts and is validated against recorded traces. Reason: the artifacts decide whether the cursor logic is correct.
- 2026-11-13 (M2 hardening): `PlaybackEngine` no longer treats `drain()` returning as proof that the tail played, and it appends a silence pad of 1.25 device buffers (250 ms) after the last real audio. Before: a pause during the final drain with a quick resume could end the run early, and a resume with less than about one buffer of audio queued did not restart the line, so `finished()` fired with the tail unplayed. Evidence: build-logs/m2h/03-tail.log, 04-tailpad.log, 06-tail-tests-before.log. Side effect: `finished()` arrives up to 250 ms later and the sink receives 250 ms of trailing zeros.

- 2026-11-17 (M3 Phase B1): the design rule "draw the peak and RMS band for every non-placeholder column" is amended: a silent column (min and max exactly 0) draws no band. Reason: it would paint a dark row 45 over the centre line, so silence and gaps would not look like a placeholder lane, which the design requires.

- 2026-11-18 (M3 Phase B2): the B1 overlay (canvas as tall as the whole track stack, inside the scrolled content) is replaced by a viewport-sized overlay that is not scrolled and draws only over the track rows. Reason: the canvas texture limit for many tracks, and the B1 finding that the playhead line overshot the tracks. Also changed: Play is enabled only when STOPPED and starts at the selection or cursor through `PlayRangePolicy` (B1 Play restarted from the start); Clear tracks stops the engine first and is enabled while playing.

- 2026-11-21 (M4 Phase B): the project sample rate is now derived from the tracks instead of being fixed by the first track forever (decision "the first track added fixes the rate, and it stays fixed", flagged in M1 as "to be revisited in Milestone 4"). Reason: removing every track must allow a file of another rate, and undo must restore the old state exactly.

## M4 Phase B (branch feature/pinklove0526/m4b-editing-ui, 2026-11-21)
- Results, adaptations and every changed existing assertion: see MILESTONE_4_SCOPE_MAP.md, "Phase B". Raw logs: build-logs/m4b (git-ignored). 475 run, 0 failures, 40 skipped (opt-in), 0 compiler warnings; `EditorEditingFxTest` 13/13 three times, B1 and B2 FX classes green; all mutation groups caught cleanly (one equivalent mutant replaced).
- Added: `Project.insertTrack`/`removeTrack`/`indexOf`; `edit.AddTrackCommand`, `edit.RemoveTrackCommand`; `ui.viewmodel.TrackSelection`, `ClipBoundaries`, `SelectionTrail`, `TransportRules.editsLocked`/`copyAllowed`; Edit and Track menus, track selection in the headers, clip borders, the after-edit pipeline, undoable import and removal, the modified title marker, the status-bar message.
- Deviations: Step 0 code before the docs; a real defect (axis re-clamp changed the zoom) found by the FX test and fixed; the status-bar readouts could be squeezed by a long message (fixed, asserted).

## M4 Phase A (branch feature/pinklove0526/m4a-edit-core, 2026-11-21)
- Results and adaptations: see MILESTONE_4_SCOPE_MAP.md, "Phase A results" and "A adaptations". Raw logs: build-logs/m4a (git-ignored). 438 run, 0 failures, 27 skipped (opt-in), 0 compiler warnings; the random oracle (40 seeds x 40 operations), the playback path and the waveform path match exactly; six mutation runs caught cleanly.
- Added: `Track.replaceClips` (the only model change); package `edit`: `Command`, `TrackClipsCommand`, `CompoundCommand`, `History`, `HistoryListener`, `Fragment`, `Clipboard`, `ClipOps`, `EditOutcome`, `Editor`.
- Deviations: the benchmark leaves out trim; one mutation (copy re-basing) first failed with errors and needed a test wrapper to fail cleanly.

## M3 Phase B2 (branch feature/pinklove0526/m3b2-interaction, 2026-11-18)
- Results: see MILESTONE_3_SCOPE_MAP.md, "Phase B2". Raw logs: build-logs/m3b2 (git-ignored). 395 run, 0 failures, 26 skipped (20 are the opt-in FX classes), 0 compiler warnings; `EditorInteractionFxTest` 10/10 three times; nine mutation runs caught cleanly (one equivalent mutant replaced).
- Added: `WheelMapping`, `ZoomAnchorPolicy`, `TransportRules`, `SelectionController.setSelection`, `EditorActions`; `EditorPane` gained the input pane, key filter, View and Select menus, transport rules, follow and the viewport-sized overlay; `OverlayView.setViewport`; test support `FakeEngineRig`.
- Deviations: bounded 1 ms polling sleeps in two test helpers (never on the FX thread); Space release consumed although redundant.

## M3 Phase B1 (branch feature/pinklove0526/m3b-javafx-views, 2026-11-17)
- Results and the adaptation list: see MILESTONE_3_SCOPE_MAP.md, "Phase B1". Raw logs: build-logs/m3b1 (git-ignored). 370 run, 0 failures, 16 skipped (10 are the opt-in FX class), 0 compiler warnings; FX render test 10/10 three times; FX and RulerTicks mutations caught cleanly.
- Added: `RulerTicks` safety cap (`widthPx + 2` ticks, stops the loop), `LaneGeometry`, `ScrollbarMapping`, `Theme`, `WaveformPainter`, `LaneView`, `OverlayView`, `RulerView`, `TrackHeaderView`, `StatusBarView`, `TrackRowView`, `EditorModel`, `EditorPane`; `TransportHarnessPane` deleted; `ManualKitGenerator` writes the six new display files (kit now in `build-logs/manual`).
- Deviations: silent columns draw no band (see retractions); the dist jar could not be built offline (missing `maven-jar-plugin`); the old manual directory was on the external drive, so the kit was regenerated.

## M3 Phase A (branch feature/pinklove0526/m3a-headless-core, 2026-11-13)
- Results: see MILESTONE_3_SCOPE_MAP.md, "Phase A results". Raw logs: build-logs/m3a (git-ignored). 340 tests, 6 skipped (opt-in), 0 failures; all five mutation checks caught.
- Deviations: (1) the 9-blocks-per-column invariant holds per clip piece; a column across a clip boundary can reach 11. (2) The zoom anchor stays within 1 px only from 0.5 frames per pixel up (the scroll position is a whole frame); below that, within half a frame. (3) Small API additions listed in the scope map. The M2 closure was confirmed by the user on 2026-11-13.
- New engine behavior (not a retraction; additive): `PlaybackEngine.play(from, to)` and `endFrame()`; `play(from)` is unchanged.

## M2 Phase B (branch feature/pinklove0526/m2-playback, 2026-11-13)
- Implemented: `AudioSink`/`JavaSoundSink`, `MixPlan`, `StereoMixer`, `PlaybackClock`, `PlaybackEngine`, `PlaybackListener`, `EngineStats`, `AudioDeviceException`, and the temporary `ui.TransportHarnessPane`. Tests: 236 in total, 2 skipped (opt-in corpus and real-device); results and logs in build-logs/m2 (git-ignored).
- B0 findings (retain, unblock, drift) are in MILESTONE_2_SCOPE_MAP.md. The gate passed: queued audio survives `stop()`/`start()` when the next write follows, no call hung, drift is -0.1 ppm (44.1 kHz) and -3.4 ppm (48 kHz).
- Dev tool additions: `AudioProbe` modes `retain`, `unblock`, `drift` (silent, zeros only) and `ManualKitGenerator` (writes the manual listening files).
- Deviations: (1) `mvn -o javafx:run` could not be run offline because the javafx-maven-plugin dependencies are missing from the local repository; the universal jar was started instead (process alive, no errors; UI not verified). (2) The `PlaybackClock` resume-window check holds the frozen value instead of requiring the estimate to be at or below elapsed time (the estimate is monotonic, so it can sit up to 6.25 ms above elapsed time right after a resume; measured on the recorded pause trace). (3) The suspected-underrun counter measures a late audio thread (gap between write calls), so the test stalls the render, not the fake consumer. (4) `retain` variants 1 and 2 as specified were inconclusive (the JDK drain gives up after 500 ms when no write follows the restart); extra variants 3 to 5 with a write after the restart settled it. (5) The 2x-buffer write in `unblock` never blocked (the downstream queue holds about two buffers); a 10x buffer write was added. (6) The first real-device run reported the position as 55 ms behind wall time in the first 400 ms because the wall clock starts before `play()` opens the device.
- Former hazard (pausing in the last ~100 ms and resuming could leave the line unstarted so the tail never played): RESOLVED in the M2 hardening, see "Tail handling" in MILESTONE_2_SCOPE_MAP.md and the M2 hardening section below. Residual: a pause during the 250 ms silence pad itself can leave the line stuck for up to ~500 ms; only silence is lost.

## M2 hardening (branch feature/pinklove0526/m2-hardening, 2026-11-13)
- H1: new silent probe modes `tail` and `tailpad` (AudioProbe). Result: a stopped-then-restarted line stays stuck (drain waits ~500 ms, audio unplayed) when 50 to 180 ms of audio is queued, restarts from about 200 ms (3 of 3 at 200 ms or more); tails of 30 ms were fine.
- H2: engine drain guard (a pause counter plus the `paused` flag) and a 250 ms silence pad; details and evidence in MILESTONE_2_SCOPE_MAP.md, "Tail handling".
- H3: `FakeAudioSink` quirks DRAIN_RETURNS_WHEN_STOPPED and RESTART_NEEDS_DEPTH, validated against the recorded rows (43 rows, all verdicts agree, worst drain-time difference 25.9 ms); new `PlaybackEngineTailTest` (8 tests) and `FakeAudioSinkTailValidationTest` (2 tests); two new opt-in real-device tests (tail pause; 48 kHz then 44.1 kHz in one engine). Existing engine tests now expect the pad.
- H4: `ManualKitGenerator` writes `tone_mono_48000.wav` instead of `bass_mono_48000.wav`; the manual checklist covers the 48 kHz path. `mvn -B -o javafx:run` now runs offline (process alive at 46 s, UI not driven).
- Deviations: (1) the suspected pause+resume race could not be reproduced on the fake (0 of 150 tries) and is fixed from the code analysis only; (2) the first versions of two tail tests were too shallow or too weak (a stop/close test survived the drain-guard mutation by luck) and were strengthened; (3) the race test was shortened (200 iterations, 20 ms virtual steps) to keep the suite fast.

## M2 Phase A (branch feature/pinklove0526/m2-audio-probe, 2026-11-10)
- New dev tool: `src/test/java/com/jellyfish/timbre/audio/probe/AudioProbe.java` (with `ProbeSampler`, `ProbeTone`); it is not a test and not in the shipped jar. Audible modes need `--consent`; a ledger caps total sound at 30 s.
- Findings and recommendations: see MILESTONE_2_SCOPE_MAP.md, "Phase A findings". Raw logs and CSVs: build-logs/m2-probe (git-ignored).
- Deviations: the 96000 Hz clock run was added because the default route supports it (16.8 s of sound used, under the 30 s cap); drift could not be resolved with 3 s runs (stated in the findings).

## Deferred
- M4 deferred (named): dragging clips (time shift); system clipboard; per-clip gain and crossfades; join or merge of adjacent clips; labels; snapping; sync-lock between tracks; replace-selection paste; a history size cap; saving the history (M7); range-selected track selection by keyboard; a Track menu beyond Remove Track; selecting no tracks again without Ctrl+clicking each one (no "select none" for tracks); a status-bar message that also clears on mouse-only actions such as zoom.
- M3 deferred (named): selection-edge dragging; auto-scroll while dragging past the edge; ruler click or scrub; zoom to selection; touchpad pinch zoom; seeking during playback; vertical zoom and amplitude ruler; clip borders and gap drawing; clipping colours; dark theme; per-track selection (with editing, M4); HiDPI check on the Retina Mac; pyramid for files too large for memory (needs a file-backed source); the scrollbar thumb look near the fit zoom (if the user does not accept it); follow fighting a manual scroll during playback (a playhead scrolled out of view is paged back).
- M3 (after Phase A): vertical zoom and amplitude ruler; clip borders and gap drawing; clipping colours; dark theme; per-track selection (with editing, M4); scrubbing and seeking during playback; selection auto-scroll while dragging past the edge; zoom to selection; HiDPI check on the user's Retina Mac; pyramid for files too large for memory (needs the file-backed source); touchpad pinch zoom.
- Apple Silicon (mac-aarch64) and Linux aarch64 jars: separate per-architecture jars are needed because their native file names collide with the x64 ones.
- Runtime check of the universal jar on Windows and macOS: not done.
- Audio output probe: done in M2 Phase A (findings in MILESTONE_2_SCOPE_MAP.md); clock drift over minutes measured in B0. Still unprobed: capture devices (forbidden so far; needed at the start of Milestone 5), behavior under CPU load, other buffer sizes.
- Audible-latency measurement and a latency-compensation setting (the probe only sees the line clock, not the sound).
- Clock and artifact behavior on macOS and Windows (rerun the probe there).
- Looping (selection playback: the engine plays a range since M3 Phase A; the UI comes in Phase B).
- Mute/solo/gain/pan (Milestone 7).
- Device selection.
- A click when stopping abruptly (queued audio cannot be faded).
- Underrun recovery beyond the diagnostics in `EngineStats`.
- File-backed `SampleSource` (needed before hour-long files).
- Resampler (needed to import files with a different rate than the project).
- RF64 and files over 4 GiB.
- 64-bit float WAV.
- AIFF.
- Metadata chunk preservation (LIST/INFO/bext/cue are dropped on write).
- Dither on integer export.
- Progress and cancellation for long reads and writes.
- Project save/load (Milestone 7).
- Real-world WAV corpus run (`-Dtimbre.testWavDir=<dir>`) once a directory with real files is available.
