# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

XP Quest Time Tracker — an always-on-top JavaFX desktop widget for tracking
project time, backed by an embedded H2 database and compiled to native
executables (Linux + Windows) via GraalVM / GluonFX. There is deliberately **no
server framework** (Quarkus/Spring): it is a single desktop application process.

## Commands

```bash
mvn javafx:run            # run on a normal JVM (primary dev loop; any Maven/JDK 21)
mvn compile               # compile only
./mvnw gluonfx:build      # build the native executable for the host OS
./mvnw gluonfx:runagent   # run under the GraalVM tracing agent to (re)generate native config
```

No test suite yet. Native builds are version-locked — use `./mvnw` (pinned to
Maven 3.8.8; the GluonFX 1.0.23 plugin breaks on 3.9.x), with `GRAALVM_HOME`
pointing at a **GraalVM for JDK 21** (not 25). `gluonfx:build` produces the
runnable binary under `target/gluonfx/<arch>/` — there is no package/installer
step. Cross-compiling is unsupported (Windows binary on Windows, Linux on Linux;
`gluonfx.target` is `host`). See README for the full version table and the
Windows toolchain (x64 Native Tools prompt + the `windows-native` profile that
links `management_ext.lib`/`psapi.lib`).

## Architecture

Single-process desktop app. Flow: `Launcher` → `App` (JavaFX `Application`) →
`Database` + DAOs.

- **`Launcher`** is the entry point for both `javafx:run` and the native image.
  It exists only to bootstrap `App.main` without the "JavaFX runtime components
  missing" error — do not make it the `Application` subclass.
- **`App`** owns the whole UI (built programmatically, no FXML — FXML adds
  reflection that complicates native image) and the timer state machine
  (`runningEntryId`/`runningSince` + a 1s `Timeline`). It creates and `start()`s
  the `Database` in `Application.start` and `stop()`s it in `Application.stop`,
  so the DB engine lifecycle is tied to the window.
- **`Database`** (in `db/`) owns the embedded H2 lifecycle. On `start()` it boots
  an **H2 TCP server on localhost:9092** so that both the app and an external
  client (DBeaver) can share one engine, then applies `schema.sql`. Data lives in
  a single file at `~/.xpquest/timetracker.mv.db`. Credentials are `sa` / blank.
- **DAOs** (`dao/`) use plain JDBC (no JPA/Hibernate — kept lightweight and
  native-image-friendly). `ProjectDao` feeds the dropdown; `TimeEntryDao` writes
  one row per start/stop session and aggregates per-project daily totals
  (`dailySummary`) for the export.
- **`model/Project`** is a record. Individual `time_entry` rows are never read
  back into objects (only ever written, then summed in SQL); the one read-back is
  the aggregate `model/DailySummaryRow` produced by `TimeEntryDao.dailySummary`.
- **Daily Summary export.** The "Daily Summary" button writes today's per-project
  totals to `daily-summary-<DATE>.json` for the `xpquest-daily-log` skill. Each
  project is tagged with a workstream inferred from its code (`xpq-eng*` →
  engineering, `xpq-sred*` → sred, else client). Output dir is
  `$XPQUEST_SUMMARY_DIR` if set, else `user.home/.xpquest` (OS-native: the same
  dir as the H2 file; the skill resolves the Windows-vs-WSL path on its side).
  JSON is hand-written — there is deliberately no JSON dependency.

## Conventions & gotchas

- **Schema changes** go in `src/main/resources/schema.sql`. It is split on `;`
  and every statement is run on each startup, so keep statements idempotent
  (`CREATE TABLE IF NOT EXISTS`, etc.) — it serves as the migration mechanism.
- **New classpath resources** consumed at runtime must be added to the native
  image resource list in
  `src/main/resources/META-INF/native-image/com.xpquest/timetracker/resource-config.json`,
  or they will be missing from the native binary (works fine under `javafx:run`).
- **New reflection** (e.g. another JDBC driver) typically needs native config;
  regenerate it with `mvn gluonfx:runagent` rather than hand-editing.
- The H2 TCP server binds localhost only (no `-tcpAllowOthers`); keep it that way
  unless remote DB access is explicitly wanted.
