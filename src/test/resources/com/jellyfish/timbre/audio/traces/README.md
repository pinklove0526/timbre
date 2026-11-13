# Recorded device traces (Milestone 2 Phase A)

Numbers only, recorded with the dev tool `AudioProbe` on the default Java Sound output of the
development machine (PulseAudio through the ALSA pulse plugin, 200 ms line buffer, 16-bit stereo).
Used to replay `PlaybackClock` and to validate the `PULSE_LIKE` mode of `FakeAudioSink`.

Files `<name>.csv` (sampled every ~5 ms by a separate thread):

| column | meaning |
|---|---|
| `t_ms` | milliseconds since the first sample row |
| `written_frames` | frames handed to the line so far (counted after each write call returned) |
| `pos_frames` | `SourceDataLine.getLongFramePosition()` |
| `pos_micros` | `getMicrosecondPosition()` |
| `avail_frames` | `available()` converted from bytes to frames (can exceed the buffer size) |
| `running`, `active` | `isRunning()`, `isActive()` |

Files `<name>-events.csv`: `event,t_ms` on the same time axis (`start-call`, `first-write`, `write-end`,
`drain-end`, and for the pause trace `stop-call`, `stop-returned`, `start-call`, `start-returned`).

* `clock-44100`, `clock-48000`, `clock-96000`: 3 s of continuous playback from the first write.
* `pause`: 44100 Hz, `stop()` at 801.5 ms, `start()` at 1302.4 ms, then playback to the end.
* `flush`: 44100 Hz with a `flush()` at 701 ms. Documentation only: the engine never flushes, so it
  is not replayed against the clock thresholds.

## Tail traces (Milestone 2 hardening, H1)
`tail-h1.csv` and `tailpad-h1.csv`: results of the silent `AudioProbe tail` and `tailpad` modes (zeros only,
44100 Hz, 200 ms buffer). One row per case: `scenario` (A = stop() 20 ms into a drain, B = stop() halfway through
the tail, P = stop() 60 ms in with a pre-queued tail), `T_ms` (zeros written), `K_blocks` (1024-frame zero blocks
written after the restart), `expected_remaining_ms` (audio left at the restart plus K blocks), `drain_total_ms`
(time from start() until drain() returned) and `verdict` (OK if drain returned within the expected time + 250 ms,
STUCK if it waited for the JDK's ~500 ms give-up). Used to validate the `RESTART_NEEDS_DEPTH` quirk of `FakeAudioSink`.
