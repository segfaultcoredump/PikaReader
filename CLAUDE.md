# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

PikaReader is a Java 21 / Maven app (v0.7, GPL-3.0) that runs on a small SBC (e.g. Raspberry Pi) next to an RFID reader (currently Impinj R420 via Octane SDK 3.0.0). It gates and stores tag reads on disk, streams them over a websocket, and supports "rewind" so a timing app can re-download reads later.

## Commands

- Build: `mvn package` → `target/PikaReader-0.7.jar` plus a shaded `target/PikaReader-0.7-uber.jar` (shade plugin attaches classifier `uber`)
- Run: `java -jar target/PikaReader-0.7-uber.jar` (or `mvn exec:java -Dexec.mainClass=com.pikatimer.pikareader.PikaReader`). Stop with Ctrl-C / SIGTERM / SIGHUP (a JVM shutdown hook in `PikaReader.start()` stops readers and HTTP).
- There are no tests (`src/test/java` is empty) and no lint configuration.
- Config file: `~/.PikaReader.cfg` (JSON), override with `-DCONFIG=<path>`. Tag DB directory: `~/.PikaReader`, override with `-DDB=<path>`. A default config is generated on first run (default reader IP `192.0.2.10`); edit it and restart. Web UI/API listens on port 8080.
- Log file: `<java.io.tmpdir>/pikareader.log`, override with `-DPIKAREADER_LOG=<path>`. A new file starts each run (`StartupTriggeringPolicy` + logback fixed-window rolling); the last 5 runs are kept as `.log` and `.log.1`–`.4`. Configured in `src/main/resources/logback.xml`.

## Architecture

- **Singletons**: `PikaConfig`, `TagDB`, `ReaderHandler`, `HTTPHandler`, `StatusHandler`, `TagReadProcessor`, `TagReadRouter` all use the SingletonHolder idiom. Startup order is in `PikaReader.start()`.
- **Reader plugins**: `RFIDReader` is the interface. Implementations are annotated `@AutoService(RFIDReader.class)` and discovered via `ServiceLoader` in `ReaderHandler`, keyed by `getType()`, which must match the `"Type"` in the config's reader entries. `Impinj` (with listener classes in `readers/impinj/`) is the only real implementation; `LLRP` and `Zebra` are empty stubs.
- **Tag pipeline**: `ImpinjTagReportListener` → `TagReadProcessor` (100k-entry queue; gates by `Gating` seconds and `Gating Style` ANTENNA/BOX/READER, keeping the strongest read per key) → `TagReadRouter` (applies `ChipFormat` hex/decimal) → fan-out to `TagDB.addReads` (on-disk JSON lines at `/yyyy/MM/dd/HH/HH-mm-ss.SSS.dat`, only today's files are loaded into memory at startup; `/rewind/{from}[/{to}]` reads older days from disk via `TagDB.getReadsBetween`) and `HTTPHandler.sendTags` (websocket `/events`).
- **HTTP**: `HTTPHandler` uses Javalin 6.7. REST: `/start /stop /trigger /rewind[/{from}[/{to}]] /debug /status`; websockets `/events` and `/status`; static UI in `src/main/resources/public`. `StatusHandler` aggregates per-antenna stats, `DiscoveryListener` answers UDP broadcast discovery, `DebugLogHolder` backs `/debug` through logback.

## Gotchas

- A "trigger" is a special read with chip/reader/antenna all 0.
- JSON uses `org.json` throughout; a Jackson migration is listed in `TODO.md`.
- `com.impinj:octane-sdk` must be resolvable by Maven to build.
