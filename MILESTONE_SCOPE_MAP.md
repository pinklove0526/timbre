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

## Distribution and platform
- Cross-platform (Windows, Linux, macOS), shipped as a single runnable jar built with `-Pdist`; x64 only for now.
- Apache License 2.0; name: Timbre.

## Rules
- `model`, `audio`, `dsp`, `edit` never import JavaFX.
- Every edit is a Command.
- The audio thread never allocates or locks on the hot path.
- The UI talks to the audio thread only through immutable snapshots.
- The peak pyramid is built on a background thread.
- Effects render to new buffers that are swapped in.

## Decisions
- v1 = multi-track, non-destructive edits, 32-bit float internally.
- WAV first. MP3/FLAC/Ogg later via pure-Java SPI libraries after a license check, NOT ffmpeg.
- Waveform on a JavaFX Canvas with a min/max peak pyramid.
- Own project format (folder + JSON manifest).
- UI mimics Audacity's classic layout closely.

## Non-goals for v1
- Spectrogram view, labels, noise reduction.
- Plugin support (VST, Nyquist-style).
- Compressed formats before Milestone 8.

## Milestones
| # | Name | Done when |
|---|---|---|
| 0 | Skeleton | Maven project builds, blank JavaFX window runs. |
| 1 | Audio core | Open and save WAV through the clip model, with headless tests. |
| 2 | Playback | Mixer and transport (play, pause, stop) with the cursor moving. |
| 3 | Waveform UI | Canvas, peak pyramid, zoom, scroll, ruler, selection. |
| 4 | Editing | Cut, copy, paste, delete, silence, trim, undo/redo, multi-track. |
| 5 | Recording | Record from the microphone into a new track. |
| 6 | Effects | Gain, normalize, fade in/out, reverse, simple EQ. |
| 7 | Audacity-style polish | Close layout match, track controls (mute, solo, gain, pan), shortcuts, WAV mixdown export, project save/load. |
| 8 | More formats | MP3, FLAC and Ogg through pure-Java SPI libraries, after a license check. |

## Branching
Work happens on feature branches created from `develop`; `develop` is updated by merge.
