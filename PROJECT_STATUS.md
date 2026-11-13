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
- M2 Playback: implemented and verified (headless, see build-logs/m2 and build-logs/m2h); the user ran the manual checklist on 2026-11-13 and it passed except that the bass file was inaudible (replaced by a 440 Hz tone file); hardening done on branch feature/pinklove0526/m2-hardening, awaiting user close.

## Recorded retractions
- 2026-11-09: Oracle public Maven repository (oracle-public) removed from the Maven settings (repository, pluginRepository, profile and activeProfile). Reason: every artifact came from Maven Central; the Oracle repo supplied nothing and only added ~150 not-found warnings per cold build. Re-add it only if an Oracle-hosted artifact is ever needed.
- Earlier plan to use `javax.sound.sampled` for WAV I/O is replaced by an own RIFF codec (reasons: headless testability, 24-bit/float/extensible control, no JDK quirks). `javax.sound.sampled` remains planned for playback/recording only.

- 2026-11-10: `Clip.split` returning `List<Clip>` is replaced by the nested record `Clip.Split(Clip left, Clip right)`. Reason: a two-element list says nothing about which element is which and allows indexing mistakes; the record names the halves and cannot have the wrong size.
- 2026-11-13: M2 decision 3 (engine shape) amended to 3': short and zero writes are retried, the thread parks while paused, `flush()` is never called, the first block is rendered before `start()`. Reason: Phase A showed `write()` on a stopped line returns short/zero and `flush()` makes the position jump by the discarded frames (build-logs/m2-probe/07-pause.log, 08-flush.log).
- 2026-11-13: M2 decision 5 (transport) amended to 5': stop is `stop()` plus `close()` (reopen on next play), and the position is the device position capped by elapsed playing time and frames written during the first two buffer lengths after each start/resume, instead of the raw device clock with a written-minus-buffered fallback. Reason: after start, stop and flush the device position leads true playback by up to 200 ms and then stays flat; the written-minus-buffered estimate is the same quantity as the device position on this stack (build-logs/m2-probe/06-clock.log, 07-pause.log).
- 2026-11-13: M2 decision 6 (tests) amended to 6': the fake sink reproduces the Phase A position artifacts and is validated against recorded traces. Reason: the artifacts decide whether the cursor logic is correct.
- 2026-11-13 (M2 hardening): `PlaybackEngine` no longer treats `drain()` returning as proof that the tail played, and it appends a silence pad of 1.25 device buffers (250 ms) after the last real audio. Before: a pause during the final drain with a quick resume could end the run early, and a resume with less than about one buffer of audio queued did not restart the line, so `finished()` fired with the tail unplayed. Evidence: build-logs/m2h/03-tail.log, 04-tailpad.log, 06-tail-tests-before.log. Side effect: `finished()` arrives up to 250 ms later and the sink receives 250 ms of trailing zeros.

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
- Apple Silicon (mac-aarch64) and Linux aarch64 jars: separate per-architecture jars are needed because their native file names collide with the x64 ones.
- Runtime check of the universal jar on Windows and macOS: not done.
- Audio output probe: done in M2 Phase A (findings in MILESTONE_2_SCOPE_MAP.md); clock drift over minutes measured in B0. Still unprobed: capture devices (forbidden so far; needed at the start of Milestone 5), behavior under CPU load, other buffer sizes.
- Audible-latency measurement and a latency-compensation setting (the probe only sees the line clock, not the sound).
- Clock and artifact behavior on macOS and Windows (rerun the probe there).
- Scrubbing, looping and selection playback.
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
