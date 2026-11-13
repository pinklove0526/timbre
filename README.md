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
```
Both properties are opt-in; without them those tests are skipped.

### Developer tools
`AudioProbe` measures this machine's audio output path (not a test, not in the jar):
```
mvn -B -o test-compile
java -cp target/classes:target/test-classes com.jellyfish.timbre.audio.probe.AudioProbe <env|mixers|open|clock|pause|flush|drain|underrun|reuse|all> [--out DIR]
```
The modes env, mixers and open are silent. The others play quiet tones through the default output and only run with `--consent`; give it only after you have agreed to the sound. Capture devices are never opened.

The universal jar bundles JavaFX natives for Windows, Linux and macOS (x64 only).

## Layout
- `src/main/java/com/jellyfish/timbre/` - `model`, `audio`, `dsp`, `edit` (no JavaFX), `ui`, `app`
- `MILESTONE_SCOPE_MAP.md` - scope and milestones
- `PROJECT_STATUS.md` - living status document

## License
Apache License 2.0, see `LICENSE` and `NOTICE`.
