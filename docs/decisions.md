# Decisions

Short ADR-style log. Newest at the bottom. Keep CLAUDE.md in sync when a decision changes.

## ADR-001: Toolchain (accepted, 2026-10-20)
JDK 25 (Temurin LTS), Scala 3.9 LTS, sbt 1.13, Python 3.13. Latest stable lines, not the newest releases: Scala Next (3.10) and sbt 2.x are skipped because they trade stability for features we do not need this week.

## ADR-002: Effect stack is cats-effect + fs2 + http4s + circe (accepted, 2026-10-20)
The core is a replay and streaming engine, so fs2 `Stream` is the natural model for the generator, the feed emulator (jitter, out-of-order delivery and dropouts via `metered`, `evalMap` and `delayBy`) and the roughly 10 s viewer-delayed fan-out (`Topic`, `broadcastThrough`), with backpressure included. http4s has first-class server-sent events, and circe's explicit codecs suit golden-file testing. Pure core with effects at the edges maps onto `IO`. ZIO would also work; we pick cats-effect and fs2 for streaming ergonomics, the SSE and HTTP stack, and munit-cats-effect. Never mix the two stacks. No Akka.

Versions: cats-effect 3.7.1, fs2 3.14.0, http4s 0.23.38 (the 1.0 line is still milestone-only), circe 0.14.17.

## ADR-003: Scala models are hand-written, pinned by golden tests (accepted, 2026-10-20)
No codegen on the Scala side. The canonical event schema selects the `details` shape, the allowed `outcome` values and the required fields with `if/then/else` keyed on `type`; no maintained Scala 3 generator expresses that, so output would be hand-edited anyway. Hand-written sealed ADTs also give exhaustive matching in detectors.

Sync mechanism, with `/schema` as the source of truth:
1. Every example is validated against the schemas on the JVM (`SchemaConformanceSuite`, the twin of `schema/validate_examples.py`), including known-bad variants.
2. Golden round trip: decode, re-encode, compare (`EnvelopeGoldenSuite`; extended to all models in step 2).
3. From step 3, everything the generator emits is validated against the schema in tests.

Python keeps datamodel-code-generator for its models. Test-only dependency: `com.networknt:json-schema-validator` 3.0.8 (draft 2020-12, format assertions switched on).

Step 2 outcome: all payloads are typed (`smi.model`). Decoders enforce the schema, not just the shape: unknown fields are rejected, IDs and timestamps are refined opaque types, ranges and per-type rules (required fields, allowed outcomes, the `details` shape chosen by event `type`) are checked, and absent optionals are omitted on encode. Timestamps keep their original string so re-encoding is byte-identical. Known-bad variants live in one data file, `schema/negatives.json`, which both `validate_examples.py` and the Scala golden tests apply, so the schema and the decoders must reject the same cases.

## ADR-004: Server-sent events as the stream transport (accepted, 2026-10-20)
`GET /events/stream` emits `text/event-stream`; `id` is the envelope `seq` (supports resume), `event` is the payload type, `data` is the envelope JSON. Chosen over WebSockets because the viewer only receives, browsers reconnect with `Last-Event-ID` for free, and it works through standard HTTP infrastructure. Spike status: serves the first `event` envelope of the examples file on `localhost`.

## ADR-005: `match_clock_s` is total elapsed playing time (accepted, 2026-10-20)
`match_clock_s` = playing time of all earlier periods (including their stoppage time) + `period_clock_s`. It is monotonic across halves, as the invariants require. The broadcast-style display ("45+2'", second half restarting at 45:00) is derived from `period` and `period_clock_s` at render time, never stored.

## ADR-006: Generator v0 design (accepted, 2026-10-20)
- One SplitMix64 RNG per match, seeded from the CLI, is the only randomness. All maths goes through `StrictMath` so the same seed is byte-identical on any JVM or CPU. Output values are rounded (coordinates 0.1 m, clocks 0.1 s) before they are written or reused.
- Pure from the outside: `MatchGenerator.generate(config)` returns the envelopes; mutable state lives inside one private simulation instance.
- A possession loop: the ball carrier shoots (chance by 12 x 8 grid cell) or passes to a teammate chosen by distance and forwardness from a 4-3-3 shape that shifts with the ball. Pass risk depends on length and destination zone. Failures become interceptions, loose balls, throw-ins or offside free kicks; shots end in goals, saves (keeper or corner), blocks or goal kicks.
- Fixed default kickoff time, so output never depends on the current date. `video_ts_ms` = wall time since kickoff + 10 s pre-roll.
- v0 has no per-player attributes; when step 4 adds them they go in a generator-only file the pipeline never reads.

## ADR-007: Scenario control with styles, phases and soft beats (accepted, 2026-10-20)
A scenario YAML (default: `core/src/main/resources/scenarios/default.yaml`, override with `generate --scenario <file>`) scripts a match without scripting events. Generator-only: the pipeline never reads it.
- Team styles: `press`, `directness`, `tempo`, `aggression` (roughly 0..1).
- Phases override one team's knobs between two display minutes (default: home high press 55-70).
- Beats (`counter_goal`, `red_card`) are soft. Around the target minute the relevant odds are multiplied by a boost that ramps up over the 5 minutes before, holds for 4 minutes after and fades by +6; once the beat happens it closes. A counter-goal boost applies only while the possession still qualifies as a counter (ball won in own half by tackle or interception, at most 15 s and 5 passes), so every boosted goal is a genuine counter. Nothing is forced: the control scenario without beats produces neither story at that rate.
- Minutes are display minutes (34 = first half at 34:00, 78 = second half at 33:00).
- A red-card beat also makes the team hunt the ball (more pressure and challenges), since a red needs fouls and fouls need defending.
- Measured on seeds 1-50 after calibration (step 5): counter goal within 5 minutes of target in 47/50, red card in 50/50, home pressures per away pass 1.52x higher during the press phase. Without beats: 0/50 and 2/50. Beats are soft, so any single seed may miss one (seed 42 has no counter goal); pick the demo seed by checking its summary.
- Dependency: `io.circe::circe-yaml-v12` 1.15.0, decoded into strict case classes with the shared codec setup.

## ADR-008: Calibration gates the unscripted baseline over 200 seeds (accepted, 2026-10-20)
- `sbt calibrate` measures seeds 1-200 and writes `docs/calibration.md` with `--report`. `CalibrationSuite` runs the same gate in `sbt test`.
- The gate is the **baseline**: the default scenario's styles and phase with its beats removed. The full story scenario is reported next to it. Its scripted counter goal and red card add about one goal and one red card per match by design; the test checks the story stays in range on every other metric and adds 0.5 to 1.5 goals.
- 200 seeds, not 50: goals per match vary by about 1.6, so a 50-seed average moves by about 0.2, almost half the 2.5-3.0 band. 200 seeds halve that.
- Target bands: CLAUDE.md for goals, shots, completion, events and possession (35-65% in every match, plus a 45-55% average). Plausibility bands chosen for this project, not taken from any data source: shots on target 7-10, passes per team 350-550, fouls 18-26.
- Possession is measured as the share of passes. Shots on target are goals plus saves.
- Main tuning changes: higher shot rates per grid cell, lower finishing (`0.46 e^(-d/7) + 0.02`), slower ball holding, more fouls, defenders on a yellow foul less, home slightly more direct (0.45).

