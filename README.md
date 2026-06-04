# XP Quest Time Tracker

A small, always-on-top desktop widget for tracking time against projects.
Register your projects (name, id/code, description, client), pick one from a
dropdown, and start/stop the timer. Built with **JavaFX** and an **embedded H2**
database, and compiled to **native executables** for Linux and Windows via
GraalVM / GluonFX.

## Features

- Always-on-top widget window (toggleable) titled *XP Quest Time Tracker*.
- Register projects: id/code, name, description, client name.
- Quick project dropdown + one-click Start/Stop timer.
- Manually log a block of time (HH:MM) onto the selected project (disabled while
  the timer is running).
- Lightweight single-file H2 database, administrable from DBeaver.
- The database engine starts and stops with the app.
- Auto-stops the running timer when the machine sleeps, trimming the slept time
  out (the entry ends at the last moment the machine was awake).

## Run during development (normal JVM)

No special toolchain needed — any JDK 21 and Maven:

```bash
mvn javafx:run
```

## Building native executables

The native-image toolchain is version-sensitive. These exact versions are known
to work together; mixing in newer ones will fail (see the notes below):

| Component   | Required version | Why |
|-------------|------------------|-----|
| GraalVM     | **for JDK 21** (e.g. `graalvm-jdk-21`) | Substrate needs Java ≥ 21; JDK 25 postdates the plugin and breaks. Set `GRAALVM_HOME` to it. |
| GluonFX plugin | **1.0.23** (pinned in `pom.xml`) | 1.0.24/1.0.25 removed Windows native-image support. |
| Maven       | **3.8.8** (use the bundled `./mvnw`) | Plugin 1.0.23 is built against 3.8.8 internals and breaks on 3.9.x. |
| C toolchain | Linux: gcc + dev libs · Windows: VS 2022 Build Tools | Native-image compiles and links C. |

Cross-compiling is not supported: build the **Linux** binary on Linux and the
**Windows** binary on Windows (`target=host`).

### Build command

Always use the wrapper (`./mvnw`), which pins Maven 3.8.8. **Do not** use a
system `mvn` for the `gluonfx:*` goals — `mvn javafx:run`/`compile` are fine.

```bash
# Linux:
./mvnw gluonfx:build

# Windows — from the "x64 Native Tools Command Prompt for VS 2022"
# (the x64 one; a plain or x86 prompt fails the toolchain check):
mvnw.cmd gluonfx:build
```

The runnable binary lands at `target/gluonfx/<arch>/XP Quest Time Tracker[.exe]`.
(`gluonfx:build` includes the link step and produces the executable — there is
no separate packaging/installer step.)

### Platform setup

**Linux** — gcc and the JavaFX native dev libraries:

```bash
sudo apt install gcc g++ zlib1g-dev libasound2-dev libavcodec-dev \
  libavformat-dev libavutil-dev libfreetype6-dev libgl-dev libglib2.0-dev \
  libgtk-3-dev libpango1.0-dev libx11-dev libxtst-dev
```

**Windows** — install **Build Tools for Visual Studio 2022** with the
**"Desktop development with C++"** workload (this provides `cl.exe`, the linker,
and the Windows SDK — you do *not* open the project in Visual Studio). Then build
from the **x64 Native Tools Command Prompt for VS 2022**. The `windows-native`
Maven profile (in `pom.xml`) auto-links the extra libraries the linker needs
(`management_ext.lib` from GraalVM + the SDK's `psapi.lib`); it reads
`GRAALVM_HOME`, so make sure that is set.

> **Regenerating native config:** H2 and JavaFX use reflection, so a native
> binary can need extra GraalVM config captured by running the app under the
> tracing agent. If a build/run hits reflection or resource errors, run
> `./mvnw gluonfx:runagent`, exercise the UI (register a project, start/stop the
> timer), close the app, and rebuild.

## Inspecting the database with DBeaver

While the app is running it exposes an H2 TCP server on `localhost:9092`.
Create a DBeaver connection with the **H2 Server** driver:

| Field    | Value                                         |
|----------|-----------------------------------------------|
| JDBC URL | `jdbc:h2:tcp://localhost:9092/./timetracker`  |
| Username | `sa`                                          |
| Password | *(blank)*                                     |

The data file lives at `~/.xpquest/timetracker.mv.db`.
