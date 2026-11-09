# Timbre - Approved Scope Map

Timbre is an Audacity-style audio editor in Java 17 + JavaFX. Clean-room: Audacity is GPL; no code, assets or icons are taken from it.

## Stack
- JDK 17, OpenJFX 17.x, Maven + javafx-maven-plugin, JUnit 5 (test scope only).
- No other dependencies (no Spring, no commons-lang3).

## Packages (com.jellyfish.timbre)
| Package | Contents |
|---|---|
| `model` | Project, Track, Clip, Selection; piece-table clips over immutable source buffers |
| `audio` | WAV I/O, float conversion, mixer, PlaybackEngine on SourceDataLine, Recorder on TargetDataLine |
| `dsp` | gain, normalize, fade, reverse, EQ, peak-pyramid builder |
| `edit` | Command with apply/revert, undo/redo |
| `ui` | main window, menu bar, transport toolbar, tool palette, track panel, waveform canvas, ruler, status bar |
| `app` | Application entry + wiring |

## Rules
- `model`, `audio`, `dsp`, `edit` never import JavaFX.
- Every edit is a Command.
- The audio thread never allocates or locks on the hot path.

## Decisions
- v1 = multi-track, non-destructive edits, 32-bit float internally.
- WAV first. MP3/FLAC/Ogg later via pure-Java SPI libraries after a license check, NOT ffmpeg.
- Waveform on a JavaFX Canvas with a min/max peak pyramid.
- Own project format (folder + JSON manifest).
- UI mimics Audacity's classic layout closely.

## Milestones
0. Skeleton - **done when:** Maven project builds, blank JavaFX window runs.
1. Audio core (WAV open/save through the clip model, headless tests)
2. Playback
3. Waveform UI
4. Editing + undo/redo + multi-track
5. Recording
6. Effects
7. Audacity-style polish + track controls + WAV mixdown + project save/load
8. More formats
