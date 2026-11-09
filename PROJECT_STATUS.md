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
- `Clip.split` returns a `List<Clip>` of two clips (left, right); the spec did not name a return type.
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

## Risks
- The Maven local repo is on removable exFAT media. If the drive is unplugged/unmounted, builds must not run (dev-env.sh refuses to continue when the mount point is missing). exFAT has no Unix permissions/symlinks, so executable bits and some tooling behave differently.

## Milestone status
- M0 Skeleton: closed, window confirmed by user.
- M1 Audio core: implemented, verified (see build-logs/m1), awaiting user review.

## Recorded retractions
- 2026-11-09: Oracle public Maven repository (oracle-public) removed from the Maven settings (repository, pluginRepository, profile and activeProfile). Reason: every artifact came from Maven Central; the Oracle repo supplied nothing and only added ~150 not-found warnings per cold build. Re-add it only if an Oracle-hosted artifact is ever needed.
- Earlier plan to use `javax.sound.sampled` for WAV I/O is replaced by an own RIFF codec (reasons: headless testability, 24-bit/float/extensible control, no JDK quirks). `javax.sound.sampled` remains planned for playback/recording only.

## Deferred
- Apple Silicon (mac-aarch64) and Linux aarch64 jars: separate per-architecture jars are needed because their native file names collide with the x64 ones.
- Runtime check of the universal jar on Windows and macOS: not done.
- Audio stack on the development machine is PulseAudio with the ALSA pulse plugin and no ALSA PipeWire plugin. At the very start of Milestone 2, probe the Java Sound mixers (list them, test a short playback and a short capture) instead of assuming it works.
- File-backed `SampleSource` (needed before hour-long files).
- Resampler (needed to import files with a different rate than the project).
- RF64 and files over 4 GiB.
- 64-bit float WAV.
- AIFF.
- Metadata chunk preservation (LIST/INFO/bext/cue are dropped on write).
- Dither on integer export.
- Progress and cancellation for long reads and writes.
- Project save/load (Milestone 7).
- Milestone 2 audio-device probe (see the audio stack note above).
- Real-world WAV corpus run (`-Dtimbre.testWavDir=<dir>`) once a directory with real files is available.
