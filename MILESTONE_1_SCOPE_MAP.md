# Milestone 1 - Audio Core (scope map)

**Done when:** a WAV file can be opened and saved back through the clip model, proven by headless tests. No JavaFX, no playback, no UI.

## Rules
- `model` and `audio` must not import JavaFX (a test enforces it).
- Planar float32 samples in memory (`float[channel][frame]`); interleaving happens only at the file boundary.
- Frame positions and lengths are `long`. Everything shared is immutable.
- `javax.sound.sampled` is not used in main code (test-scope oracle only).

## package `com.jellyfish.timbre.model`
- **SampleSource** (interface): `sampleRate()`, `channelCount()`, `frameCount()`, `read(long startFrame, int frames, float[][] dest, int destOffset)`. `dest` has at least `channelCount` arrays, each with room for `destOffset + frames`; the range `[startFrame, startFrame+frames)` must lie within `[0, frameCount()]` else `IndexOutOfBoundsException`; bad `dest` shape -> `IllegalArgumentException`. Implementations are thread-safe for concurrent reads and do not allocate in `read()`.
- **MemorySampleSource** (final, immutable): `copyOf(rate, channels)` (defensive copy) and `adopt(rate, channels)` (takes ownership; used by the WAV reader). Validates sampleRate > 0, channelCount >= 1, equal channel lengths, frames <= Integer.MAX_VALUE - 8. Empty source allowed.
- **Clip** (record): `source`, `sourceStart`, `frameCount`, `timelineStart`. Invariants: sourceStart >= 0, frameCount > 0, sourceStart + frameCount <= source.frameCount(), timelineStart >= 0. Methods: `timelineEnd()`, `withTimelineStart(long)`, `split(long)` returning the nested record `Clip.Split(Clip left, Clip right)` (position strictly inside the clip; halves cover the source range exactly).
- **Track** (final, mutable, single-thread use in M1): `Track(name, sampleRate, channelCount)`, `Track.of(name, source)`, `addClip` (rate/channel match, no overlap, sorted by timelineStart), `clips()` (unmodifiable copy), `snapshot()`. Gaps are silence.
- **TrackSnapshot** (final, immutable, `SampleSource`): `frameCount()` = end of last clip; `read()` zero-fills gaps, finds the first relevant clip by binary search, no allocation per call.
- **Project** (final): `sampleRate()` (OptionalInt), `addTrack`, `tracks()`. The first track fixes the sample rate; a different rate -> `SampleRateMismatchException` (extends `IllegalArgumentException`, names both rates). Channel counts may differ per track.
- Not in M1: Selection (M3), edit commands (M4).

## package `com.jellyfish.timbre.audio`
- **WavEncoding**: `PCM_8` (unsigned), `PCM_16`, `PCM_24`, `PCM_32` (signed little-endian), `FLOAT_32`; `bitsPerSample()`, `isFloat()`.
- **WavFile** (record): `MemorySampleSource source`, `WavEncoding encoding` (the file's original encoding).
- **UnsupportedAudioFormatException** (extends `IOException`): unsupported or malformed files; message states what was found and what is supported. Plain `IOException` = real I/O failure.
- **WavReader**: `read(Path)`, `read(InputStream)`.
  - RIFF/WAVE only; RF64 and RIFX rejected ("not supported yet").
  - Walks chunks, skips unknown ones (pad byte after odd sizes); `fmt` must precede `data`; anything after `data` ignored; metadata not preserved.
  - Format tags 1 (PCM), 3 (float), 0xFFFE (EXTENSIBLE, mapped via subformat GUID; channel mask ignored). 8/16/24/32-bit PCM and 32-bit float, 1..64 channels. EXTENSIBLE with validBits < container bits: left-justified, read at container width.
  - Validates sampleRate > 0 and blockAlign == channels * bytesPerSample; a wrong byteRate is tolerated.
  - Data size 0, larger than the remaining bytes, or 0xFFFFFFFF: use bytes actually present; trailing partial frame dropped; allocation never based on the declared size alone.
  - Size guard: decoded float size > half of `Runtime.maxMemory()` -> `UnsupportedAudioFormatException` (a file-backed source is planned). A package-private overload takes an explicit byte limit for tests.
  - Mapping: signed N-bit / 2^(N-1) (-32768 -> -1.0f); 8-bit unsigned (v-128)/128f; float32 verbatim (NaN/Infinity kept). Decoded in blocks.
- **WavWriter**: `write(Path, SampleSource, WavEncoding)`. Accepts at most 64 channels (same cap as the reader) and rejects output larger than 4 GiB - 1 with `UnsupportedAudioFormatException`.
  - Float -> integer: scale 2^(N-1) in double, `Math.rint` (half-even), clamp to [-2^(N-1), 2^(N-1)-1], NaN -> 0; 8-bit adds 128. No dither. FLOAT_32 verbatim (bit-exact).
  - Mono/stereo: plain fmt chunk (tag 1 or 3); more than 2 channels: EXTENSIBLE with the right subformat GUID. Float files get a `fact` chunk. Pad byte when the data size is odd. Outputs with RIFF size > 4 GiB - 1 are rejected before anything is written.
  - Streams the source in blocks. Atomic result: temp file in the target's directory, then move (REPLACE_EXISTING, ATOMIC_MOVE with plain-move fallback); on any failure the target is unchanged and no temp file remains.
- **SampleCodec** (package-private): int <-> float conversions, allocation-free inner loops.
- **Precision note:** 32-bit PCM is NOT lossless through float32 (24-bit mantissa). 8, 16 and 24-bit PCM and float32 round-trip exactly.

## Non-goals (M1)
Playback, mixing, recording, effects, edit commands and undo, UI, peak pyramid, resampling, file-backed sample sources, RF64, 64-bit float, AIFF, metadata preservation, dither, progress/cancel, project save/load.

## Tests (headless, JUnit 5)
- Independence: reader tests build input with an independent test-only RIFF byte builder (never `WavWriter`); writer tests inspect raw bytes. No binary fixtures.
- Reader: formats/channels, EXTENSIBLE variants, chunk handling and padding, data-size edge cases, truncation/malformed inputs, rejected formats, size guard, no huge allocation from a tiny file.
- Value mapping and writer rules (header bytes, clamping, NaN, half-even rounding, 8-bit, 4 GiB rejection before file creation, atomic write, overwrite).
- Round trips: exact for 8/16/24-bit and float32 (bit-exact incl. -0.0f), 32-bit within float32 tolerance, sample rates preserved.
- JDK oracle (test scope): JDK-written WAV read by `WavReader`; `WavWriter` output read through `AudioSystem`; supported cases logged.
- Model: Clip, Track, TrackSnapshot (immutability, gaps, random-split property test, boundaries, range errors), Project rate policy.
- Allocation guard (`ThreadMXBean`), architecture guard (no `import javafx` in model/audio/dsp/edit), end-to-end test.
- Known-answer tests (`WavKnownAnswerTest`): hand-written distinct-byte literals for 8/16/24/32-bit, float32, stereo and 3-channel interleave, EXTENSIBLE 24-in-32; writer output checked as raw bytes.
- Declared-size tests (`WavDeclaredSizeTest`): a data chunk declaring 0x7FFFFFFF or 0xFFFFFFFF with a few bytes present, through both `read(Path)` and `read(InputStream)`, with a measured allocation budget.
- Opt-in real-world corpus (`-Dtimbre.testWavDir=<dir>`).
- Opt-in real-encoder interop (`-Dtimbre.interop=true`, needs `ffmpeg`; `ffprobe` and `sox` used when present).

### Testing
Two opt-in system properties, both skipped when absent:
- `timbre.testWavDir=<directory>`: runs `WavCorpusTest` over every `.wav` file in the directory (needs a directory of WAV files).
- `timbre.interop=true`: runs `ExternalToolInteropTest` (needs `ffmpeg` on the PATH; it only writes into JUnit temp directories and never touches an audio device).
