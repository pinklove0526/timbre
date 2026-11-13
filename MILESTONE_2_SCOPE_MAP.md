# Milestone 2 - Playback (scope map)

**Done when:** mixer and transport (play, pause, stop) with the cursor moving.

Work is split in two phases. Phase A (this document's "Phase A findings") is an audio-device probe that only measures. Phase B (the engine) starts after the findings are reviewed.

## Settled decisions
1. Two phases: Phase A is the probe; Phase B (engine) starts only after the user reviews the findings, and its details are finalized then.
2. Output format: 16-bit signed little-endian stereo at the project's sample rate through `javax.sound.sampled` `SourceDataLine`; floats clamped to [-1, 1) on conversion; no dither; if the device rejects the rate, fail with a clear error (no resampling until a later milestone).
3. Engine shape: a dedicated audio thread with preallocated buffers writing straight to the line with blocking writes (no extra ring buffer; the line's buffer already decouples timing); default 200 ms line buffer and 1024-frame render blocks; later edits swap an immutable snapshot in at block boundaries.
4. Mixer scope: unity-gain sum of all tracks; a mono track is duplicated to left and right at full level; tracks with more than 2 channels play channels 1 and 2 only; mute, solo, gain and pan wait for Milestone 7.
5. Transport: play from a frame, pause and resume, stop. Stop returns the cursor to where playback started (a "stop and set cursor" may come later). Position comes from the device's frame clock, with a fallback of frames written minus frames still buffered if the probe shows the device clock is unreliable. At the end of the project it drains and stops. Listeners are plain Java callbacks so the audio package stays JavaFX-free.
6. UI and tests: a minimal TEMPORARY JavaFX harness (File > Open WAV, Play/Pause/Stop buttons, a time readout, a position slider driven by `AnimationTimer`), replaced by the waveform cursor in Milestone 3. Headless tests use an `AudioSink` abstraction with a fake sink; a real-device test is opt-in (`-Dtimbre.audioDevice=true`) and plays a short quiet tone.

## Non-goals (M2)
Recording, resampling, device-selection UI, mute/solo/gain/pan, looping, scrubbing, selection playback, effects.

## Phase A findings
Measured on 2026-11-10 with the dev tool `AudioProbe` (JDK 17.0.2, Linux 6.18, PulseAudio 17.0 with the ALSA pulse plugin; default sink `alsa_output.pci-0000_00_1f.3.analog-stereo`, 44100 Hz s16 stereo default, HDA Intel PCH / Realtek ALC256). Raw data: `build-logs/m2-probe/` (git-ignored). Audible budget used: 16.8 s of -24 dBFS tone.

**Devices and formats**
- Java Sound lists 6 mixers. `AudioSystem.getSourceDataLine` picks `default [default]` for both 44100 and 48000 Hz. The analog `PCH [plughw:0,0]` offers no SourceDataLine (probably because the sound server holds the card; not verified); `plughw:0,3/7/8/9` are HDMI outputs; `Port PCH [hw:0]` is a port mixer with no data lines.
- `default [default]` supports 8000, 22050, 44100, 48000, 96000 Hz, mono and stereo, 16-, 24- and 32-bit signed PCM. 32-bit float is not supported by any mixer.
- Open takes 2.1 to 10.6 ms. The requested 200 ms buffer is granted exactly: 8820 frames at 44100, 9600 at 48000, 19200 at 96000. All five HDMI/default lines opened and closed without error.

**Position clock (3 s of tone per rate, sampled every ~5 ms)**
| | 44100 | 48000 | 96000 |
|---|---|---|---|
| monotonic (max backward step) | 0 | 0 | 0 |
| steady step size (median / smallest / largest while playing) | 882 / 768 / 1316 | 976 / 784 / 1440 | 2048 / 1792 / 2657 |
| steady position minus wall time | -11 .. +21 ms | -10 .. +28 ms | -11 .. +18 ms |
| jitter (max distance from fitted line) | 18.1 ms | 19.8 ms | 17.2 ms |
| 3.000 s of audio reached at (wall) | 2992.8 ms | 2991.9 ms | 3000.6 ms |
- The position moves in steps of about 20 ms. `getMicrosecondPosition` is just the frame position converted.
- Device position and the estimate "frames written minus (buffer minus available)" agree (mean difference 0.3 to 2.0 ms, mean absolute 0.3 to 2.1 ms). They are not independent measurements: the JDK derives the position from the same available-space query. The maximum difference (21 to 70 ms) occurs at the start.
- Drift could not be resolved: the 20 ms steps over a 2.5 s window limit the fitted slope to about +-5000 ppm (the fits gave -535, -5412 and -312 ppm; the 3 s end-to-end check puts all three within 8 ms of wall time).
- **Start artifact:** within 5 ms of `start()` plus the first write the position jumps to one full buffer (8820, 9600, 19200 frames = 200 ms) while nothing has played, stays flat for 175 to 193 ms, then moves at the real rate. The position therefore leads wall time by up to 200 ms during the first ~190 ms. The "latency to first nonzero position" of 5 ms is this artifact, not a real latency.

**Pause (`stop()` 500 ms, then `start()`)**
- While stopped: `isRunning` and `isActive` false, position frozen (spread 0 frames), written frames stop growing once the buffer is full (write calls then return 0: 402 zero-length and 403 short returns in this run).
- At the `stop()` call the position jumps forward from 35596 to 44100 (+193 ms) while the frames written are only 41984, and `available()` reads 10936 (more than the 8820-frame buffer), then 0 once the writer refills the buffer.
- After `start()` the position continues from the frozen value (+757 frames at once), then stays nearly flat for ~200 ms and then moves normally. Total timeline stays right: 2.5 s of audio plus 0.5 s pause reached its end position at 2999.5 ms.

**Flush (while running)**
- The position does not reset and never goes backward, but it jumps forward by the discarded audio: 31483 to 46708 (+15225 frames = 345 ms). `available()` reads 11496 (above the 8820-frame buffer, buffer treated as empty). The position then stays flat for ~190 ms before moving; the final position equals the total frames written (88200), so discarded frames are counted as played.

**Drain:** `drain()` took 182.6 ms for 186.2 ms of buffered audio (8213 frames); afterwards the position equals the frames written and `available()` equals the buffer size.

**Underrun (0.5 s written, 400 ms starvation, 200 ms buffer):** no exception; `isRunning` and `isActive` stay true throughout; the position advances to the frames written (22050) and stays there (spread 0) for the rest of the gap, never counting the silence; after writing resumes the largest position step is one block (1024 frames); the final position equals the frames written.

**Reuse:** `open()` took 2.9 to 6.0 ms on a reopened line and on a fresh line, at 44100 and 48000 Hz; the position is 0 after every open; the same line object reopened three times behaved like a fresh one (final position equals frames written each time).

### Recommendations
(a) **Position source.** Use the device position, but cap it by the elapsed playing time (monotonic clock since `start()`, minus paused time) and by the frames written. Evidence: steady-state the device position tracks wall time within about -11 to +28 ms and is monotonic; the estimate is the same quantity so it adds nothing; the start, stop and flush artifacts all make the device position lead true playback by up to one buffer (200 ms), which the cap removes (replaying the pause run, the capped value is never ahead of the elapsed playing time; the largest remaining lag was 45 ms). Interpolate between the 20 ms position steps in the UI.
(b) **Buffer and block size.** Keep a 200 ms line buffer (granted exactly at 44100, 48000 and 96000) and 1024-frame blocks (23 ms, never produced a short write while running). Smaller blocks gain nothing: the position only moves every 20 ms.
(c) **Pause and stop.** Pause with `line.stop()` / `line.start()` on the same open line (no audio is discarded; position freezes); do not use `flush()` for pause. Stop with `line.stop()` followed by `line.close()` and reopen on the next play: reopening costs under about 11 ms and resets the position to 0, which avoids the flush jump. The audio thread must not rely on blocking `write()` while paused (it returns short or 0 on a stopped line): park the thread on a condition instead.
(d) **Keep the line open between plays?** Not needed for speed (open is 2 to 11 ms). Keep it open across pause; close it on stop and on a sample-rate change. Reopening the same line object and opening a fresh one behaved identically.
(e) **Hazards and mitigations.**
1. Position leads by up to 200 ms after `start()`, `stop()` and `flush()`, then freezes (cursor jump and stall): cap by elapsed playing time.
2. `available()` can exceed the buffer size (10936 and 11496 of 8820 observed): clamp it and never use it for control flow.
3. `write()` on a stopped or just-flushed line returns short or zero: loop with a pause gate, no busy spin.
4. Underrun is silent: no exception, line stays active, position stalls at frames written and resumes without a jump. Detect starvation in the audio thread (time since the last completed write versus buffered audio) and report it; do not rely on an exception.
5. Clock drift is unmeasured (needs a run of minutes): the capped-position design does not depend on it, and the end-of-project `drain()` gives an exact end.
6. Only the `default [default]` route reaches the speakers; the analog hardware mixer offers no data line, so device selection (a non-goal) would be limited to the default route and HDMI outputs.
(f) **The six settled decisions.**
1. Two phases: confirmed.
2. Output format 16-bit stereo through `SourceDataLine`: confirmed; the default route also accepts 8000 to 96000 Hz and 24/32-bit; float is not available, so keep the float to 16-bit conversion. Rate rejection still needs the clear error (this machine accepted every rate tried).
3. Engine shape: confirmed with one change: the audio thread must handle short and zero-length writes and park while paused instead of relying on a blocking write.
4. Mixer scope: not affected by the probe.
5. Transport: change proposed: position is the device position capped by elapsed playing time (see a); stop becomes stop plus close; pause stays stop/start. Drain at the end of the project is confirmed (it took 182.6 ms for 186.2 ms of audio and ended with position equal to frames written).
6. UI and tests: confirmed; the `AudioSink` fake should be able to reproduce the start/stop/flush position artifacts so the cursor logic can be tested headlessly; the harness should interpolate between the 20 ms steps.
