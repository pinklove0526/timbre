# Milestone 3 - Waveform UI (scope map)

**Done when:** canvas, peak pyramid, zoom, scroll, ruler, selection (from MILESTONE_SCOPE_MAP.md).

Work is split in two phases. Phase A is the headless core (selection model, peak pyramid and cache, pure view-model classes, engine range playback) proven by headless tests. Phase B (JavaFX views, drawing, interaction) starts only after the user reviews Phase A.

## Settled decisions
1. **Scope.** In: peak pyramid, waveform canvas with one lane per channel, time ruler, horizontal zoom and scroll, time-range selection, playback cursor. Out: editing, track controls (M7), vertical zoom and amplitude ruler, spectrogram, labels, clip borders, snapping, clipping colours.
2. **Peak pyramid.** One per SOURCE (shared by every clip that uses it), built once on a background thread; a flat placeholder line until ready; each block stores min, max and a sum of squares (for RMS: light peaks plus a darker RMS body); base block 256 frames, each level 4x the one below; below about 1 frame per pixel the view draws the samples themselves as lines and dots.
3. **View.** Ctrl+wheel zooms at the pointer, Shift+wheel scrolls horizontally, horizontal scrollbar; Ctrl+1 zoom in, Ctrl+3 zoom out, Ctrl+F fit project; zoom limits from about 32 px per sample to the whole project plus a margin; during playback the view page-scrolls when the cursor reaches the right edge.
4. **Selection.** Immutable `Selection(startFrame, endFrame)`, empty = the cursor; a time range across all tracks (per-track selection comes with editing in M4); click sets the cursor, drag selects, Shift+click extends; Ctrl+A select all, Home/End move the cursor, Space toggles play/stop, Esc clears the selection. Added by the user, flagged: while PLAYING or PAUSED, mouse selection is locked (no seeking yet); zoom and scroll stay active.
5. **Layout and look.** Ruler on top; 120 px track header (name plus format info such as "Mono, 44100 Hz, 32-bit float", no mute/solo until M7); 90 px per channel lane; status bar with cursor and selection times; two stacked canvases (waveform layer redrawn only when something changed, overlay for cursor and selection redrawn per frame). HiDPI is verified later on the user's Retina Mac.
6. **Playback integration.** The cursor overlay replaces the harness slider; a click sets the start frame; Play plays the selection if there is one, otherwise from the cursor to the end of the project (cursor at or after the end: from frame 0). One engine change: `play(fromFrame, toFrame)`.
7. **Testing.** Pure view-model classes and the pyramid are checked headless against brute force; a manual visual checklist with generated reference WAVs comes in Phase B.

## Non-goals (M3)
Editing, mute/solo/gain/pan, vertical zoom, amplitude ruler, spectrogram, labels, clip borders and gap drawing, snapping, clipping colours, scrubbing and seeking during playback, per-track selection, dark theme.

## Design
Classes marked **(B)** belong to Phase B; everything else is Phase A. Nothing in `model`, `audio`, `dsp`, `edit` or `ui.viewmodel` imports JavaFX.

### package `com.jellyfish.timbre.model`
- `Selection` (record): `long startFrame, endFrame`; invariants `0 <= start <= end`; `isEmpty()`, `length()`, `cursor(long)`, `between(long a, long b)` (orders the two), `clampedTo(long maxFrame)`.

### package `com.jellyfish.timbre.dsp`
- `PeakPyramid` (final, immutable, thread-safe once built): `build(SampleSource)` reads in blocks; `BASE_BLOCK_FRAMES = 256`, `LEVEL_FACTOR = 4`; levels continue until a level has 1 block (empty source: 0 levels). Per channel and block: float min, float max, double sumSquares; non-finite samples are sanitized (NaN -> 0, +Inf -> Float.MAX_VALUE, -Inf -> -Float.MAX_VALUE) before aggregation. API: `channelCount`, `frameCount`, `levelCount`, `blockFrames(level)`, `blockCount(level)`, `framesInBlock(level, block)` (the last block may be partial), `levelFor(framesPerPixel)` = the largest level whose `blockFrames <= framesPerPixel/2`, or -1 if none (past the top: clamped to the top), `summarize(channel, level, firstBlock, endBlock, out)` with `out = {min, max, rms}` aggregated exactly over those whole blocks (`rms = sqrt(total sumSquares / total frames)`; ranges clamped to the existing blocks; an empty range gives zeros), plus block accessors for tests.
- `PeakPyramidCache` (final, `AutoCloseable`): keyed by source identity with weak keys (never keeps a source alive), thread-safe; `getIfReady(source)` returns `Optional<PeakPyramid>`; `request(source, onReady)` starts at most one build per source on a single daemon thread named `timbre-peaks`; each requester's callback runs on that thread after the build (immediately if already built); a failed build is recorded (`lastError()`) and the callback is not invoked; `close()` stops the thread and is idempotent.

### package `com.jellyfish.timbre.ui.viewmodel` (pure Java)
- `TimeAxis` (immutable): `sampleRate`, `framesPerPixel`, `scrollFrame`. `frameAtX(x) = scrollFrame + floor(x * framesPerPixel)`; `xAtFrame(frame) = (frame - scrollFrame) / framesPerPixel`; `visibleFrames(widthPx)`; `zoomedAt(factor, anchorX, limits)` keeps the frame under `anchorX` within 1 px of it; `scrolledTo(frame, limits)`; `fit(sampleRate, projectFrames, widthPx)`. Limits: framesPerPixel in `[1/32, max(fitFramesPerPixel * 1.25, 1/32)]`; scrollFrame in `[0, max(0, projectFrames - visibleFrames/10)]` and exactly 0 when the whole project fits in the window; default for an empty project 100 px per second.
- `TimeFormat`: `clock(frames, sampleRate)` -> `h:mm:ss.mmm` (one fixed format, hours never omitted, so widths do not jump); `ruler(seconds, stepSeconds)` -> label with only as many decimals as the step needs.
- `RulerTicks`: `generate(TimeAxis, widthPx)` -> immutable list of `Tick(x, frame, major, label)`; major step from the 1-2-5 sequence (decades from 1 microsecond to 1 hour) chosen so major ticks are at least 80 px apart; minor ticks only where at least 6 px apart; positions are exact multiples of the step in time.
- `LaneColumns` and `LaneSampler`: for ONE channel of ONE track, from the track's clips (`source`, `sourceStart`, `frameCount`, `timelineStart`), a pyramid lookup `Function<SampleSource, PeakPyramid>` (null = not ready), the `TimeAxis` and the width, compute reusable per-pixel arrays `min[]`, `max[]`, `rms[]`, a mode, and for SAMPLE_LINES a sample span. Modes: SAMPLE_LINES when framesPerPixel < 1 (one value per visible frame, read from the source); else PEAKS when `levelFor(framesPerPixel) >= 0` and the pyramid is ready (aggregate the WHOLE blocks covering the column's source range: a block-granular superset, error under half a pixel by construction); else DIRECT when framesPerPixel < 512 (exact min/max/rms from the samples); else PLACEHOLDER (pyramid not ready: zeros plus a flag). Gaps between clips and columns outside any clip are silence (they contribute 0 to min/max and to the sum of squares, and count in the frame total). Timeline frame t maps to source frame `sourceStart + (t - timelineStart)`. Multi-clip and gap handling is exact in DIRECT mode and documented-approximate in PEAKS mode. Invariant: PEAKS never aggregates more than 9 blocks per column.
- `SelectionController`: mouse rules on a `TimeAxis` and the project length: `press(x, shift)`, `drag(x)`, `release(x)`, the current `Selection`, the anchor; clamped to `[0, projectLength]`; movement under 3 px is a click (cursor, empty selection); a drag left of the anchor yields an ordered selection; shift-press extends from the existing anchor.
- `FollowPolicy`: given playhead frame, `TimeAxis`, widthPx, returns the new scrollFrame (unchanged unless the playhead is outside `[0, width)` or beyond 95% of the width, then the playhead goes to 10% from the left), clamped by the axis limits.
- `PlayRangePolicy`: `choose(Selection, projectFrames)` -> `(from, to)` or empty: a non-empty selection plays exactly that range (clamped to the project); otherwise from the cursor to the project end; a cursor at or after the end plays from 0; an empty project plays nothing.

### package `com.jellyfish.timbre.audio` (engine change only)
- `PlaybackEngine.play(long fromFrame, long toFrame)`: plays `[fromFrame, min(toFrame, plan length))`; returns false and stays STOPPED when the range is empty; `play(fromFrame)` keeps its behavior. Natural end at the range end behaves exactly like the end of the project (final partial block, the 250 ms silence pad, guarded drain, `finished()`, cursor back to the start frame). New accessor `endFrame()`. `positionFrames()` never exceeds the range end (the pad is not project time).

### Phase B classes (not built in Phase A) **(B)**
`ui.WaveformView` (two stacked canvases: waveform layer and overlay), `ui.RulerView`, `ui.TrackHeaderView`, `ui.TimelinePane` (scrollbar, wheel, keyboard shortcuts), `ui.StatusBar`, replacement of the harness slider by the cursor overlay, and the manual reference-WAV kit. These only call the pure classes above.

## Test plan
- Headless unit tests per class, against hand-written asymmetric literals or independent brute-force references written in the test (never the class under test).
- `PeakPyramidTest`: known-answer 600-frame case; brute force on seeded random sources (mono, stereo, 6 channels; lengths 0, 1, 255, 256, 257, 1023, 1024, 1025, a few thousand); `levelFor` thresholds at 512, 2048, 8192; non-finite sanitizing; an impulse is never lost.
- `PeakPyramidCacheTest`: one build per source, callbacks on `timbre-peaks`, failure recorded, idempotent close, weak keys.
- `TimeAxisTest`, `TimeFormatTest`, `RulerTicksTest`, `LaneSamplerTest` (modes, boundaries, clips with offsets and gaps, 9-block invariant, impulse visibility), `SelectionControllerTest`, `FollowPolicyTest`, `PlayRangePolicyTest`, `SelectionTest`.
- Engine range tests with the fake sink; architecture guard extended to `ui.viewmodel` and to audio/dsp not importing `ui`.
- Opt-in: real-device range test (`-Dtimbre.audioDevice=true`, about 1 s of tone) and a benchmark (`-Dtimbre.bench=true`, logs only).
- Mutation checks: pyramid blocks, zoom anchor sign, ruler step sequence and 80 px rule, backward-drag ordering, engine position clamp.

## Phase A results
Branch `feature/pinklove0526/m3a-headless-core`, from develop `19c55e83c70edee3f1ed9d9b6c209f3c379682a5`. Logs in `build-logs/m3a` (git-ignored).
- **Tests:** full suite 340 run, 0 failures, 0 errors, 6 skipped (the opt-in classes: real device with 4 tests, WAV corpus, benchmark), 34.5 s (`13-full-test`). Baseline was 248 tests, 4 skipped, 30.5 s. New: Selection 4, PeakPyramid 8, PeakPyramidCache 7, TimeAxis 8, TimeFormat 5, RulerTicks 10, LaneSampler 16, SelectionController 12, FollowPolicy 5, PlayRangePolicy 7, PlaybackEngineRange 8, real-device range 1, benchmark 1. The architecture guard now also scans `ui/viewmodel` for JavaFX imports and checks that audio and dsp do not import `ui`. No compiler warnings from our sources.
- **Benchmark** (opt-in `-Dtimbre.bench=true`, 10-minute stereo 44.1 kHz source = 26.46 million frames, 1500 columns, 2 lanes): `PeakPyramid.build` 232 ms cold, 205 to 225 ms warm (10 levels, 103360 level-0 blocks); `LaneSampler` PEAKS at 17640 frames per pixel 0.47 ms median (4.65 ms first call); DIRECT at 300 frames per pixel 3.5 ms median (24 ms first call); SAMPLE_LINES at 0.5 frames per pixel 0.12 ms median. Most blocks aggregated for one column in the benchmark: 6.
- **Real device** (opt-in, after consent): a 2.0 s project with a 1.0 s tone in 500 to 1500 ms, `play(500 ms, 1500 ms)`: `finished()` after 1.32 s, the position reached exactly the range end and never passed it, the cursor returned to 500 ms. Sound used: 1.0 s.
- **Mutation checks:** all five caught (details in `build-logs/m3a/mutations`).

### Deviations from the design (settled design, small and recorded)
1. **9 blocks per column.** The design invariant "PEAKS never aggregates more than 9 blocks per column" holds for each clip piece of a column (proved by a sweep over zoom and scroll, at most 9). A column that crosses a clip boundary aggregates the blocks of two pieces and can reach 11 (`aColumnAcrossAClipBoundaryAggregatesAtMostElevenBlocks` observed 10 or 11). The cost is two extra blocks of work at a boundary; no effect on correctness.
2. **Zoom anchor.** `scrollFrame` is a whole frame, so "the frame under the anchor stays within 1 px" holds from 0.5 frames per pixel up; below that it holds to half a frame (0.5 / framesPerPixel pixels, 16 px at the 32 px per sample limit). Also not kept when the scroll limit is reached. The test measures the exact anchor time before and after.
3. **Added API (small, needed by the rules):** `TimeAxis.Limits(sampleRate, projectFrames, widthPx)` (the limits object the design calls `limits`), `PlayRangePolicy.Range`, `RulerTicks.Tick`, `LaneColumns.Mode`, `SelectionController.setAxis` and `setProjectFrames`, `PlaybackEngine.endFrame()` returns the end as it was when the last run started.
4. **Rules fixed where the design left a choice:** ruler minor ticks divide a major step into 10, 2 or 5 parts (steps starting with 1, 2, 5), and into 6 for the one-hour step; the 1-2-5 sequence runs from 1 microsecond to 1000 seconds, then 3600 seconds; ruler ticks cover `[0, width]` inclusive; a selection entirely after the project end counts as a cursor there for `PlayRangePolicy`; in `LaneSampler`, PEAKS needs every visible clip's pyramid, otherwise PLACEHOLDER, and clips outside the window need none.


## Phase B1 - read-only JavaFX display
Branch `feature/pinklove0526/m3b-javafx-views`, from develop `421e0a9`. Phase B is split: **B1** (this section) draws the project and takes no interaction except dragging the horizontal scrollbar; **B2** adds mouse selection, wheel zoom, shortcuts, play-range wiring and playback follow. No sound is played in B1.

### Step 0 - RulerTicks safety cap
`RulerTicks.generate` stops its loop as soon as the list holds `widthPx + 2` ticks (a valid result has at most `widthPx / 6 + 1`). The cap ends the generating loop itself; nothing is truncated afterwards. It exists because the original mutation M3b (`MIN_MAJOR_SPACING_PX = 0.0`) exhausted the heap. A package-private overload `generate(axis, widthPx, minMajorSpacingPx, minMinorSpacingPx)` lets a test force spacing 0 (the public method always passes the two constants).

### Lane geometry (package `ui.viewmodel`, `LaneGeometry`, no JavaFX)
- A lane is 90 px high (rows 0..89), followed by a 1 px separator (row 90): row stride 91 px per channel.
- Centre row `yc = 45`. Amplitude `v` in [-1, 1] maps to row `yOf(v) = clamp(round(45.0 - 44.0 * v), 1, 89)`, where `round` is `Math.round` (half rounds up: `yOf(0.125) = 40`, `yOf(-0.125) = 51`). Values outside [-1, 1] clamp to rows 1 and 89; NaN maps to 45.
- Peak band of a column: rows `yOf(max)` to `yOf(min)` inclusive (at least 1 px, because `yOf` is monotonic decreasing). RMS band: rows `max(peakTop, yOf(+rms))` to `min(peakBottom, yOf(-rms))` inclusive, drawn on top of the peak band in the RMS colour.
- SAMPLE_LINES: the polyline runs through `(xAtFrame(frame), yOf(sample) + 0.5)`; a 3x3 px dot centred on `(round(x), yOf(sample))` is added when `framesPerPixel <= 1/6` (6 or more px per frame).
- Drawing order per lane: background (rows 0..89), centre line (row 45), then bands or polyline and dots, then the separator row (row 90). PLACEHOLDER lanes and silence show background and centre line only.

### Classes
`ui.viewmodel`: `LaneGeometry`, `ScrollbarMapping` (scrollFrame <-> scrollbar `value`, `max` = the scroll limit, `visibleAmount` = visible frames; round-trips within 1 frame). `ui`: `Theme` (all colours), `WaveformPainter` (stateless drawing), `LaneView` (one Canvas per channel), `TrackHeaderView` (120 px; name and format line), `TrackRowView`, `RulerView`, `StatusBarView`, `OverlayView` (playhead or cursor line and selection fill), `EditorModel` (project, axis, selection, playhead, dirty counters, owns the `PeakPyramidCache`), `EditorPane` (replaces `TransportHarnessPane`; keeps File > Open WAV, Clear tracks and the Play / Pause / Stop buttons; slider and old time readout removed).

Layout: menu and transport row, ruler row (120 px spacer plus ruler), a vertically scrolling stack of track rows with the overlay canvas stacked on the lane area, the horizontal scrollbar aligned with the lane area, the status bar. The waveform layer repaints only when axis, project, pyramid readiness or size change; the overlay repaints when dirty and on every animation pulse while PLAYING or PAUSED. On load the axis is `TimeAxis.fit` for the project and lane width; resizing keeps `framesPerPixel` and re-clamps the scroll. While PLAYING or PAUSED the overlay draws at `engine.positionFrames()`, otherwise at the cursor (selection start, 0 by default).

### Test plan
Headless: RulerTicks cap tests, `ScrollbarMappingTest` (literals, round trip, fits-in-window, end limit), `LaneGeometryTest` (yOf, band rows, dot rule against hand-computed literals), architecture guard. Opt-in FX render test `EditorRenderFxTest` (`-Dtimbre.fx=true`): cases (a) to (i) with pixel checks against `Theme` constants and PNG dumps. FX mutations: swap min/max rows, shift columns by +1 x, playhead at `frame + 1000`, plus the Step 0 mutation. Manual kit: `ManualKitGenerator` writes sweep, clipped sine, silence, impulses, stereo difference (and with `-Dtimbre.kit.long=true` the 10-minute file).

### B1 adaptations
Design versus the real Phase A APIs. No model, audio, dsp or viewmodel code changed beyond what is listed.
1. **Track name.** `Track.name()` exists, so no name map is needed; `EditorPane.baseName` strips the extension of the file name before `Track.of`.
2. **Pyramid lookup.** `LaneSampler` takes `Function<SampleSource, PeakPyramid>`; `LaneView` passes `src -> cache.getIfReady(src).orElse(null)`.
3. **RulerTicks overload.** Package-private `generate(axis, widthPx, minMajor, minMinor)` added only for the cap test.
4. **Silent columns draw no band (changes the design text).** The design says to fill the peak and RMS band for every non-placeholder column. A silent column (min and max both exactly 0) would then draw a dark row 45 over the faint centre line, so silence would differ from a placeholder lane and from a gap, while the design also says gaps and silence show only the centre line and that a placeholder shows the same. `WaveformPainter` therefore skips the bands of columns with `min == 0 && max == 0` (`LaneGeometry.isSilent`). Recorded under Recorded retractions in PROJECT_STATUS.md.
5. **Peak band is order-insensitive.** `LaneGeometry.peakBand` orders its two rows, so swapping min and max in the painter is an equivalent mutant; the FX mutation for the painter is the sign flip of `yOf`.
6. **Scrollbar thumb.** The mapping follows the design (`max` = scroll limit, `visibleAmount` = visible frames). JavaFX derives the thumb size from `visibleAmount` and `max` itself; I did not check how it looks near the fit zoom (the PNG only shows the disabled bar).
7. **Lane width.** The lane area width is the content width of the vertical scroll pane minus the 120 px header; the ruler row and the scrollbar row get a right pad equal to the vertical scrollbar width (bound), so all four line up (checked in `jTheAssembledPaneAlignsRulerLanesOverlayAndScrollbar`). The pane starts with `DEFAULT_LANE_WIDTH` 800 until the first layout pass; tracks opened before the first layout would fit to 800 px (the real app opens files only after the window is shown).
8. **Overlay size.** The overlay canvas height is bound to the track stack height and its width follows the lane width. A Canvas has a hard texture limit (a few thousand pixels), so a project with very many tracks would exceed it; not addressed in B1.
9. **Harness replaced.** `TransportHarnessPane` is deleted; `EditorPane` keeps File > Open WAV, Clear tracks, Exit and the Play, Pause, Stop buttons, the state label and the underrun counter. Play starts at the selection start (frame 0 unless set from code); no range wiring (B2).
10. **Opt-in class counts.** The FX class is skipped as a class, but surefire reports its 10 methods as run and skipped (+10 run, +10 skipped).
11. **Manual kit directory.** The earlier manual directory lived on the external drive and is gone; the kit is generated into the git-ignored `build-logs/manual`.

### Phase B1 results
Branch `feature/pinklove0526/m3b-javafx-views`, from develop `421e0a9dd72589ec6a4e9d32156c81b86c615c45`. Logs and predictions in `build-logs/m3b1` (git-ignored; `predictions.txt` holds every prediction next to its result).
- **Suite:** 370 run, 0 failures, 0 errors, 16 skipped (6 older opt-in plus the 10 FX methods), 34.5 s (`12-full-test`); 354 executed, up from 334 (+3 RulerTicks cap, +11 LaneGeometry, +6 ScrollbarMapping). 0 compiler warnings after deleting `target/classes` and `target/test-classes`. The new headless classes plus the architecture guard ran three times (32 tests each, 0 failures).
- **FX render test** `EditorRenderFxTest` (`-Dtimbre.fx=true`, DISPLAY :1, no Stage, no sound): 10 tests (cases a to i plus the assembled pane), 0 failures, three consecutive runs; the PNGs are byte-identical across runs (except `j-pane.png`, which contains text and is not hashed). PNGs in `build-logs/m3b1/png`.
- **Step 0:** the original mutation M3b now fails cleanly: 13 run, 10 failures (assertion failures), no OutOfMemoryError; sha256 restored.
- **FX mutations:** yOf sign flip caught by 5 FX tests (a, c, d, e, h); every column shifted +1 x caught by 4 (a, c, d, h); playhead at frame + 1000 caught by 1 (g). All as predicted; sha256 restored each time.
- **Manual kit** (`build-logs/manual`): 9 files generated with `-Dtimbre.kit.long=true`; contents spot-checked independently with a Python reader (impulse positions and values, clipping at the rails, sweep peak -12.0 dBFS, channel peaks, silence). The 10-minute file is 105,840,044 bytes.
- **Memory** (headless, `18-heap-10min`): reading the 10-minute stereo file costs about 204 MB of heap (26.46 million frames as two float arrays), its pyramid about 4 MB more; read 224 ms, pyramid 187 ms. Canvas and scene memory not measured.
- **Not verified:** the dist jar (`mvn -o -Pdist package` fails offline: `maven-jar-plugin:3.5.1` is not in the relocated local repository; nothing was downloaded), HiDPI, real window behaviour and the manual items below.

### Manual acceptance (user)
Run `source ./dev-env.sh; mvn -o javafx:run`, then File > Open WAV for each file in `build-logs/manual`:
1. The lane shows the expected shape: `sweep_mono_44100` an even band growing denser; `clipped_sine_mono_44100` flat tops touching the lane edges; `silence_stereo_44100` only the centre line in both lanes; `impulses_mono_44100` four thin spikes at 1.0, 2.5, 5.0 and 9.0 s that never vanish when zoomed out (zooming needs B2; at the fit zoom check they are visible); `stereo_difference_44100` the left lane clearly taller than the right.
2. The ruler labels grow sensibly when the window is resized (the zoom stays, so more time becomes visible) and the horizontal scrollbar drags smoothly.
3. `long_stereo_44100_10min` stays responsive: the lane shows the flat line first, then the waveform appears. Measured headless: about 204 MB heap for the samples.
4. Opening several files stacks tracks and scrolls vertically.
5. The track header shows the name (file name without extension) and the format line.
B2 (mouse selection, wheel zoom, shortcuts, play range, follow) completes this checklist.

