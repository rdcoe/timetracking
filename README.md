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
- Lightweight single-file H2 database, administrable from DBeaver.
- The database engine starts and stops with the app.
- Auto-stops the running timer when the machine sleeps, trimming the slept time
  out (the entry ends at the last moment the machine was awake).

## Requirements

- JDK 21 (a [GraalVM](https://www.graalvm.org/) build is required for native images).
- Maven 3.9+.
- For native builds: a working C toolchain (see the
  [GluonFX prerequisites](https://docs.gluonhq.com/#_platforms)). Windows
  executables must be built **on Windows**; Linux executables **on Linux**.

## Run during development (normal JVM)

```bash
mvn javafx:run
```

## Build native executables

The GluonFX plugin (1.0.23, the last version that builds native images on
Windows) was compiled against Maven 3.8.8 internals and breaks on Maven 3.9.x.
Use the bundled Maven Wrapper, which is pinned to 3.8.8, so the version is
correct regardless of your system Maven:

```bash
# Linux / macOS:
./mvnw gluonfx:build gluonfx:package
# Windows (from the x64 Native Tools Command Prompt):
mvnw.cmd gluonfx:build gluonfx:package
```

Builds for whichever OS you run it on (`target=host`). Plain `mvn` works for
day-to-day `compile` / `javafx:run`; only the `gluonfx:*` goals need 3.8.8.

Output lands under `target/gluonfx/<arch>/`. Run on Linux and Windows
respectively to produce each platform's binary.

> H2 uses reflection in spots, so the first native build may need additional
> GraalVM config. If the native binary throws reflection/resource errors, run
> the app once under the GraalVM tracing agent and commit the generated config:
>
> ```bash
> mvn gluonfx:runagent   # exercise the UI, then close it
> ```

## Inspecting the database with DBeaver

While the app is running it exposes an H2 TCP server on `localhost:9092`.
Create a DBeaver connection with the **H2 Server** driver:

| Field    | Value                                         |
|----------|-----------------------------------------------|
| JDBC URL | `jdbc:h2:tcp://localhost:9092/./timetracker`  |
| Username | `sa`                                          |
| Password | *(blank)*                                     |

The data file lives at `~/.xpquest/timetracker.mv.db`.
