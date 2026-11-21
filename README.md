# Timbre

An Audacity-style audio editor written in Java 17 and JavaFX. Early development: only the project skeleton exists (Milestone 0).

## Requirements
- JDK 17 (tested with 17.0.2)
- Maven 3.6.3 or newer
- Linux: GTK 3 (required by JavaFX)

## Build, test, run
```
mvn test                 # run unit tests
mvn javafx:run           # start the app from Maven
mvn -Pdist package       # build target/timbre-<version>-universal.jar
java -jar target/timbre-<version>-universal.jar            # run the jar
java -jar target/timbre-<version>-universal.jar --version  # print version, no window
```
Do not run `javafx:run` together with `-Pdist`.

## Testing
```
mvn test
mvn test -Dtimbre.testWavDir=<directory>     # also decode every .wav in a directory (needs real WAV files)
mvn test -Dtest=ExternalToolInteropTest -Dtimbre.interop=true   # compare against ffmpeg (ffmpeg must be installed)
mvn test -Dtest=RealDevicePlaybackTest -Dtimbre.audioDevice=true   # plays quiet tones through the default output
```
Three properties are opt-in; without them those tests are skipped. `timbre.audioDevice=true` runs `RealDevicePlaybackTest`, which plays about 5 s of quiet tone through the default output (48 kHz and 44.1 kHz) (run it only if you are happy to hear that).

### Developer tools
`AudioProbe` measures this machine's audio output path (not a test, not in the jar):
```
mvn -B -o test-compile
java -cp target/classes:target/test-classes com.jellyfish.timbre.audio.probe.AudioProbe <env|mixers|open|clock|pause|flush|drain|underrun|reuse|retain|unblock|drift|tail|tailpad|all> [--out DIR]
```
The modes env, mixers, open, retain, unblock, drift, tail and tailpad are silent (the last five write only digital zeros; drift runs for minutes and takes `--rate HZ --seconds N`). The others play quiet tones through the default output and only run with `--consent`; give it only after you have agreed to the sound. `AudioProbe` never opens a capture device.

`CaptureProbe` measures this machine's audio INPUT path (Milestone 5; not a test, not in the jar):
```
java -cp target/classes:target/test-classes com.jellyfish.timbre.audio.probe.CaptureProbe <inputs|open|diag|buffers|stall|stop|sustain> [--consent] [--out DIR]
```
`inputs` only lists the input mixers and formats and never opens a line. The other modes open the default input for a few seconds each and only run with `--consent` (give it only after you agreed); they keep only level and timing statistics, never write or print audio, and stop when the 40 s capture budget in `capture-ledger.txt` is used up. `diag` prints per-window level diagnostics (DC mean, full-scale share, zero-crossing rate, magnitude histogram); `buffers` opens with small requested buffers and reports the read burst pattern.

The universal jar bundles JavaFX natives for Windows, Linux and macOS (x64 only).

## Layout
- `src/main/java/com/jellyfish/timbre/` - `model`, `audio`, `dsp`, `edit` (no JavaFX), `ui`, `app`
- `MILESTONE_SCOPE_MAP.md` - scope and milestones
- `PROJECT_STATUS.md` - living status document

## License
Apache License 2.0, see `LICENSE` and `NOTICE`.
