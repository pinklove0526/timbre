# Timbre - Project Status (source of truth)

## Environment (from build-logs/00-preflight.txt)
- Dev machine: Mageia 10, x86_64, Wayland session (DISPLAY=:1 / XWayland present), `xvfb-run` not installed.
- WORKDIR: /run/media/jellyfishng/Elements/Mageia/Desktop/DATA/Projects/personal-projects (project in ./timbre)
- Drive "Elements": /dev/sda2, **exfat**, rw, nosuid,nodev (no noexec), 115G free.
- JAVA_HOME: /home/jellyfishng/Desktop/DATA/Software/jdk-17.0.2 (OpenJDK 17.0.2+8, Oracle build)
- MAVEN_HOME: /home/jellyfishng/Desktop/DATA/Software/Maven (Apache Maven 3.10.0, bin/mvn directly under it)
- Maven local repo: /run/media/jellyfishng/Elements/jar-lib (via ~/.m2/settings.xml; the directory already existed and was empty)
- ~/.m2/settings.xml did not exist before; created (local repo + oracle-public repo/pluginRepo, active profile).
- GTK3/X11/GL libraries present. Audio: /dev/snd present, PulseAudio server (`pactl info`), no ALSA PipeWire plugin (only alsa pulse plugins).
- Network: repo1.maven.org 200; maven.oracle.com/public 301 (redirect).

## Decisions
- JavaFX 17.0.21: newest 17.0.x on Maven Central (list checked 2026-11-09). Stays on the 17 line so it works on JDK 17.0.2.
- JUnit Jupiter 5.14.4 (newest 5.x on Central; 6.x exists but the prompt asks for 5.x).
- javafx-maven-plugin 0.0.8 (newest 0.0.x).
- Plugins: compiler 3.14.0, resources 3.3.1, surefire 3.5.4, shade 3.6.0.

## Deviations
- ./timbre already existed as a git clone (branch `feature/pinklove0526/project-scaffolding`, one "Initial commit", remote origin https://github.com/pinklove0526/timbre.git) containing LICENSE and a one-line README.md. WORKDIR itself is not inside a repository, so this was not the stop case; `git init` was skipped and the existing repo is used. Nothing is committed or pushed.
- The pre-existing README.md ("# timbre") is replaced by the README required by the prompt.
- Session's shell started in /home/jellyfishng; commands were run after `cd` to WORKDIR/timbre.
- `rm` is aliased to interactive here, so preflight probe files were first left behind and then removed with /bin/rm -f.

- LICENSE: existing file differed from the official Apache-2.0 text only by one leading blank line; replaced with the official download.
- The pom's dist profile does not contain an explicit aarch64 exclude: only win/linux/mac (x64) classifiers are declared, so aarch64 jars are never on the classpath.
- Oracle repo (kept as requested): Maven tries oracle-public first for every artifact and logs ~150 "cached not-found ... expiring" WARNINGs per cold build (no 401/403); everything is served by Central. Options if the noise bothers you: drop the repo, or keep it only as pluginRepository. Not changed.
- repository.apache.org/snapshots (default repo of Maven 3.10) timed out once (warning only).
- JavaFX 17.0.21 class files are version 55 (Java 11), so it supports JDK 17.0.2.

## Risks
- The Maven local repo is on removable exFAT media. If the drive is unplugged/unmounted, builds must not run (dev-env.sh refuses to continue when the mount point is missing). exFAT has no Unix permissions/symlinks, so executable bits and some tooling behave differently.

## Milestone status
- M0 Skeleton: implemented; automated checks passed (see build-logs 01-07). Window visibility NOT yet confirmed by a human - awaiting visual check.

## Deferred
- Apple Silicon (mac-aarch64) and Linux aarch64 universal jars: their native file names collide with the x64 ones, so they are excluded and need separate jars.
