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

The universal jar bundles JavaFX natives for Windows, Linux and macOS (x64 only).

## Layout
- `src/main/java/com/jellyfish/timbre/` - `model`, `audio`, `dsp`, `edit` (no JavaFX), `ui`, `app`
- `MILESTONE_SCOPE_MAP.md` - scope and milestones
- `PROJECT_STATUS.md` - living status document

## License
Apache License 2.0, see `LICENSE` and `NOTICE`.
