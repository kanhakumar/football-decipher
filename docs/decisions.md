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

## ADR-009: Provider-style raw feed, adapter and feed emulator (accepted, 2026-10-20)
The pipeline must show a path to real provider data without using any. "synthfeed" is our own provider-style format; nothing in it comes from vendor documentation or ID tables.
- Format (`smi.raw`, registry in `core/src/main/resources/raw/registry.yaml`): messages of kind `match`, `lineup` or `event` with action `upsert` or `delete` and a `last_modified` time. Events carry numeric type IDs, provider team and player IDs, period + minute + second (the second half starts at minute 45), a wall-clock `timestamp`, 0..100 coordinates (team-relative), an outcome bit and numbered qualifiers with string values. All IDs are our own sequential numbering.
- Precision: raw coordinates have 2 decimals and the adapter keeps 4 decimals in metres, so raw -> canonical -> raw is exact. Canonical -> raw -> canonical moves coordinates by at most about 5 mm and drops tenths of a second (the raw clock is whole seconds). `video_ts_ms` comes from `timestamp - kickoff + video_offset_ms` and is exact.
- IDs: canonical IDs are derived (`E_<raw id>`, team from the registry, `P_<team suffix>_<shirt>` from the lineup), and every canonical event carries `source_ids` back to the raw record.
- Outcomes: the bit says complete, goal or won; a `result` qualifier carries anything the bit cannot (for example `intercepted` or `saved`).
- Adapter (`Adapter`, immutable state): match and lineup messages publish at once; event upserts wait in a reorder buffer (10 s of event time) and publish in time order; an upsert for a published event is an `update`, a delete is a `delete`. Repairs and rejections are reported as issues, never silent: a missing `result` qualifier gets the type's default outcome, coordinates up to 1 unit outside 0..100 are clamped, anything unknown or missing a required field is rejected.
- Emulator (`FeedEmulator`, seeded): latency and jitter, about 2% of events delayed by 2-7 s (inside the reorder window, so recoverable), 3 corrections a minute or more later, 2 phantom events that are later deleted, and one 20-40 s dropout released as a burst. `FeedEmulator.stream` replays a schedule at 1x to 20x.
- Recovery check: the adapter's final state from the messy deliveries equals its state from the clean feed with corrections applied (`FeedEmulator.truth`).

## ADR-010: Stats engine (accepted, 2026-10-20)
`smi.engine` turns canonical envelopes into facts. It is pure, reads only canonical data, and never imports generator code (a test enforces this).
- State: an event store by ID; per-minute buckets keyed by (period, minute of period) and by team or player, holding every additive metric. Creates add an event's contributions, updates subtract the old version and add the new one (possibly in another minute), deletes subtract. Order-dependent state (score, players on the pitch, cards, possession) is rebuilt from the store when first asked for. A property test checks that any mix of corrections, deletes and restores equals a full recompute.
- Enrichment (raw events are never changed): engine-owned zones (thirds at 35 and 70 m, penalty box, zone 14, an 18-zone grid); xG from distance and goal-mouth angle (logistic, hand-set: about 0.27 from 6 m central, 0.11 from the penalty spot, 0.03 from 20 m; headers x0.6, direct free kicks x0.8, penalties 0.76), set so mean xG tracks mean goals in the synthetic matches (3.22 vs 2.82 over 50 unscripted seeds) and not fitted to any data; expected pass completion from length, forwardness, end zone, height and pressure; progressive pass = completed and at least max(10 m, 25% of the distance to goal) closer; final-third and box entries.
- Metric registry: each metric declares unit, calculation (count, sum, or ratio of two at query time), the capability level it requires, a fallback and its basis. Distance covered and sprints need tracking (level 1); until tracking exists they answer with involvements, tagged `event_estimate` and naming the fallback. Every result is a `MetricValue` with value, unit, basis and capability level; undefined ratios are empty, never zero.
- Definitions: possession share = share of passes; field tilt = share of final-third passes; PPDA = opponent passes in their own 60% divided by our tackles, interceptions and fouls in that area.
- Evidence: the same predicate that counts a metric lists the events behind it, for `evidence_event_ids` in step 8.

## ADR-011: Moment detectors and re-derivable insights (accepted, 2026-10-20)
`smi.insight` turns engine state into Insight objects. It reads the engine and canonical models only, never generator code.
- Contract: a detector `find`s a moment (evidence event IDs plus a header: kind, period, teams, players, window bounds), then `derive`s every fact from the evidence events alone, and computes `why_it_matters` and `importance` from the facts alone. Window bounds are stored in the facts, so `Rederive.check` can rebuild the header from a published insight and must reproduce facts, reason and importance exactly. Every number in `params` is also a fact, so the Verifier only has to check narration against facts.
- Runner (`Moments`): replays the feed through the engine; event detectors run on each new event, window detectors when a minute completes; 10-minute cooldown per detector and team. Times come from the triggering event, `created_at` from its wall clock (deterministic).
- Corrections: an update or delete to an evidence or trigger event re-checks the insight. Same facts: nothing. Different facts: the old insight is re-emitted `retracted` and a replacement with `supersedes` is published. No longer true: retracted only. An update can also create a new insight (a saved shot corrected to a goal).
- Detectors and thresholds: goal (scoreline codes, or `counter_attack_goal` when the possession began with a tackle or interception in the team's own half and the shot came within 15 s and 5 completed passes, computed from events, not the shot's situation tag); red card; key chance (unscored, xG at least 0.25); momentum swing (attacking-action share from 40% or less to 60% or more, 10-minute windows); pressure shift (pressures per opponent pass over 6 minutes at least 1.5x the previous 8 to 15 minutes, at least 10 pressures); control vs chaos (turnovers up 35% to 26 or more in 10 minutes, or one team's pass share from 55% or less to 65% or more); rhythm change (passes per minute changes 35% or more); milestones (50, 75, 100 completed passes; 10 and 20 team shots; brace, hat-trick).
- Measured on 50 seeds: about 27 insights per match; every generator counter goal is detected as one and nothing else is (two independent implementations agree); every red card detected; the home high press found in 41/50 seeds. Without beats: counter story 0/50, red card story 2/50.
- Demo seed 154 (default scenario) shows all three scripted moments with a close scoreline.

## ADR-012: Synthetic 5 Hz tracking and workload metrics (accepted, 2026-10-21)
- Source: `smi.tracking.TrackingSynth` builds frames from a canonical feed (lineups and events) and a seed. It is data-source code like the generator; the engine and detectors never import it (tested). `generate --tracking` merges frames into the feed by wall-clock time and renumbers `seq` (event IDs unchanged); about 29,500 frames and 75 MB per match, so it is off by default.
- Frame convention: coordinates in the home team's frame (home attacks toward x = 105 all match), within the schema's tracking bounds.
- Movement: each player heads for their lineup slot, shifted with the ball and by line height (5 m up in possession, 5 m back out of it), with slow seeded wander; event actors are pulled to the event spot from 6 s before, pass receivers to the pass end. Recovery runs at top speed when more than 10 m from the shape. Limits: top speed by role (about 7 m/s keeper, 8.5 defence, 9 midfield, 9.5 attack, with up to 4% seeded variation), acceleration 3.5 m/s2, and an approach speed the player can still stop from (no overshoot). Fatigue lowers speed and acceleration with time and high-speed running; half-time recovers some; substitutes come on fresh. Only players on the pitch appear. The ball sits at each event spot at event time and travels to the pass end or next event; officials follow simple speed-limited rules.
- Measured on seed 154: acting players within 3 m of the event spot for 98.7% of events; full-match outfield players about 11.5 to 12 km, keepers about 5.6 km, top speeds 28 to 34 km/h, about 20 sprints per full-match outfield player, and lower distance per minute late than early.
- Engine workload: frames are folded into per-minute buckets per player and team, never stored. Distance between consecutive frames (gaps over 1 s ignored), high-speed distance above 5.5 m/s, top speed, sprints (7 m/s or more held for 1 s), and a fatigue index (distance per minute in a window as a percentage of the holder's own first 15 minutes). Capability becomes 1 once frames arrive.
- Degrading: a window answers from tracking (basis `tracking`, capability 1) only if at least 90% of the expected frames arrived; otherwise distance and sprints fall back to involvements (basis `event_estimate`) and the other tracking metrics report not available. `PositionProvider` returns a position or `NotAvailable` with a reason (no tracking data, not on the pitch, stale for more than 1 s).
- `Rng` moved to the shared `smi` package so the generator, feed emulator and tracking synthesizer share it without depending on each other.

