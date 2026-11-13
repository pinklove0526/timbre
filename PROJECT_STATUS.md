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
- M2 Playback: in progress, Phase A (device probe).

## Recorded retractions
- 2026-11-09: Oracle public Maven repository (oracle-public) removed from the Maven settings (repository, pluginRepository, profile and activeProfile). Reason: every artifact came from Maven Central; the Oracle repo supplied nothing and only added ~150 not-found warnings per cold build. Re-add it only if an Oracle-hosted artifact is ever needed.
- Earlier plan to use `javax.sound.sampled` for WAV I/O is replaced by an own RIFF codec (reasons: headless testability, 24-bit/float/extensible control, no JDK quirks). `javax.sound.sampled` remains planned for playback/recording only.

- 2026-11-10: `Clip.split` returning `List<Clip>` is replaced by the nested record `Clip.Split(Clip left, Clip right)`. Reason: a two-element list says nothing about which element is which and allows indexing mistakes; the record names the halves and cannot have the wrong size.

## M2 Phase A (branch feature/pinklove0526/m2-audio-probe, 2026-11-10)
- New dev tool: `src/test/java/com/jellyfish/timbre/audio/probe/AudioProbe.java` (with `ProbeSampler`, `ProbeTone`); it is not a test and not in the shipped jar. Audible modes need `--consent`; a ledger caps total sound at 30 s.
- Findings and recommendations: see MILESTONE_2_SCOPE_MAP.md, "Phase A findings". Raw logs and CSVs: build-logs/m2-probe (git-ignored).
- Deviations: the 96000 Hz clock run was added because the default route supports it (16.8 s of sound used, under the 30 s cap); drift could not be resolved with 3 s runs (stated in the findings).

## Deferred
- Apple Silicon (mac-aarch64) and Linux aarch64 jars: separate per-architecture jars are needed because their native file names collide with the x64 ones.
- Runtime check of the universal jar on Windows and macOS: not done.
- Audio output probe: done in M2 Phase A (findings in MILESTONE_2_SCOPE_MAP.md). Still unprobed: capture devices (forbidden in Phase A; needed at the start of Milestone 5), clock drift over minutes, the true audible output latency (the probe only sees the line clock), behavior under CPU load, other buffer sizes, Windows and macOS.
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
