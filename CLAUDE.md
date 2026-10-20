# CLAUDE.md: Synthetic Match Intelligence Engine

Hackathon entry for "Inside the Game" (Microsoft and the Premier League). Solo project. Submit by Oct 27, 2026 IST (official deadline Oct 27, 11:59 pm PT).

## What we are building
An AI pipeline that turns a stream of synthetic football events into explainable match intelligence: live overlays, narratives and a full-time recap, personalised by persona, favourite club or player, metric and language (English, Hindi, Spanish).
Five stages: Ingest, Interpret, Explain, Render, Personalize.

## Hard rules (never break these)
1. Synthetic data only. Never use real match data, real vendor payloads, vendor docs or vendor ID tables (for example Opta).
2. Fictional clubs and players only. No Premier League, vendor or other third-party names or logos in code, UI, README, tests or video.
3. Code computes facts. The LLM only explains facts that tools return. Every number in generated text must be checkable against the engine.
4. Agents reach data only through MCP tools. Never put bulk raw events or tracking frames in a prompt.
5. No secrets in the repo. Use managed identity and Key Vault.
6. The repo is public and all docs are in English.
7. Do not overclaim. Only name Microsoft technologies we actually use.

## Architecture decisions (accepted)
- D1 Code computes facts and detects moments. The LLM only explains and narrates.
- D2 The Insight object is the central contract between engine, agents and delivery.
- D3 One language-neutral insight, narrated per (persona, language) on demand and cached by (insight_id, persona, lang).
- D4 Hot path (routine events): templates, under about 1 s. Moment path (important insights): agent workflow, about 2-4 s.
- D5 The viewer feed runs about 10 s behind the generator. Overlays are scheduled by video_ts_ms.
- D6 The demo is a replayed match that behaves live.
- D7 The Verifier checks every number and claim. On reject, retry. After N failures, fall back to a template line.
- D8 Overlays are timed JSON (times in milliseconds: t_start_ms, duration_ms).
- D9 Agents never bulk-read events or frames. They call tools.

## Languages and layout
Hybrid. Scala for the deterministic core, Python for agents.
```
/schema    JSON Schema (source of truth) and examples
/core      Scala: generator, raw-feed emulator, adapter, engine, detectors, HTTP and SSE API
/agents    Python: Microsoft Agent Framework workflow, thin MCP server wrapping the Scala API
/viewer    Web UI styled like a broadcast screen
/infra     Bicep and azd
/eval      Recorded matches, eval configs, number-match checker
/docs      Architecture, decisions, README assets
```
Core is a modular monolith (adapter, engine, detectors, API) with clean module boundaries. The MCP server stays a thin Python layer over the Scala API.

## Contracts (the files in /schema)
- canonical.schema.json: feed envelope (match_meta, lineup, event, tracking_frame, delete). Pitch 105 x 68 m, team-relative coordinates (each team attacks toward x = 105). Time fields: period, period_clock_s, match_clock_s (total elapsed playing time, monotonic; ADR-005), wallclock_utc, video_ts_ms.
- insight.schema.json: facts, evidence_event_ids, why_it_matters {code, params}, importance, basis, capability_level. No prose.
- overlay.schema.json: timed graphic. op show or retract.
- Layers: raw events, then enriched (xG, pass difficulty, progressive), then insights. Never put derived metrics in the raw layer.
- Capability levels: 0 events only; 1 coarse tracking at 2-5 Hz; 2 detailed windows. Every metric declares what it requires and a fallback. Outputs carry a basis tag.
- Generator attributes (pace, passing and so on) live in a generator-only file. The pipeline never reads them.
- Changing a schema means updating the Scala and Python models, the examples and the golden-file tests in the same change. Ask first.
- Generate Pydantic models with datamodel-code-generator. Scala: generate case classes or hand-write them, but pin them with golden-file tests. Decide in the spike.

## Conventions
- Scala 3.9 LTS with sbt 1.x on JDK 25, scalafmt, munit + ScalaCheck. Effect stack (ADR-002): cats-effect + fs2 + http4s (Ember) + circe. Never mix in ZIO. No Akka (Pekko if needed). Pure core, effects at the edges.
- Scala models are hand-written (ADR-003), pinned by golden round-trip tests and JVM schema validation against /schema. Python models are generated with datamodel-code-generator.
- Python 3.11 or newer, Pydantic v2, ruff, pytest.
- Determinism: every random draw comes from the seeded RNG. The same seed must produce byte-identical NDJSON.
- Small modules, short functions, comments only where intent is not obvious.

## Testing and validation
- Schema validity on every emitted message.
- Known-bad cases live in schema/negatives.json. Add new ones there; the Python script and the Scala golden tests both apply them.
- Invariants: monotonic seq and clocks, spatial continuity within a possession, score equals goal events, 11 players per side minus red cards.
- Calibration over 50 or more seeds (we use 200, ADR-008): about 2.5-3 goals, 20-28 shots, 78-90% pass completion, 1,500-2,500 events per match, possession in a 35-65% range. Project plausibility bands: 7-10 shots on target, 350-550 passes per team, 18-26 fouls. The gate is the baseline (scenario without beats); see docs/calibration.md.
- Adapter round trip and edge cases (delete, out-of-order, missing qualifier, boundary coordinates).
- Verifier tests: reject a wrong number, reject an unsupported claim, accept a correct line.
- Eval gate in CI: replay a recorded match, run the number-match check and Foundry evaluations, fail the build on regression.

## Azure (planned)
Foundry project (models, guardrails, tracing, evals), Microsoft Agent Framework, custom MCP server, Event Hubs, Cosmos DB (serverless), Container Apps (generator job, core service, agent service), Static Web Apps, Application Insights, Key Vault, managed identity, azd with Bicep, GitHub Actions.
Stretch: Speech (TTS), Functions, Logic Apps, Fabric Eventhouse, A2A split of the Recap Editor.
Ask before adding a dependency or an Azure resource. Region and model deployments are decided after the day-1 spikes.

## How to work with me
- Plan first, then build in small vertical slices. Each slice ends with a passing test and a runnable command.
- Do not read or print large NDJSON files. Use head, tail, jq or counts.
- Prefer editing existing files. Do not create docs I did not ask for.
- Keep this file and /docs/decisions.md current when a decision changes.
- If a command is unknown, read the build files instead of guessing, then add it below.
- Cut order if time runs short: cut first auto-eventing and detailed tracking, then extra languages and voice. Never cut the generator, moments with explanations, narration or the overlay viewer.

## Commands (fill in as they are established)
- Python env: `agents/.venv` (Python 3.13 from python.org; the system default is still 3.6). Setup: `cd agents && "%LOCALAPPDATA%/Programs/Python/Python313/python.exe" -m venv .venv && .venv/Scripts/python -m pip install -e ".[dev]" -r ../schema/requirements.txt`
- Schema check: `agents/.venv/Scripts/python schema/validate_examples.py`
- Generate a match: `cd core && sbt "generate --seed 42"` (writes out/match-42.ndjson at the repo root and prints a summary; `--out <path>` to override, `--scenario <yaml>` for another match script; default script is core/src/main/resources/scenarios/default.yaml)
- Calibrate: `cd core && sbt calibrate` (200 seeds; `--report ../docs/calibration.md` rewrites the report; exits non-zero if a baseline metric is out of range)
- Raw feed and emulator: `cd core && sbt "rawfeed --seed 42"` (writes the messy raw feed to out/raw-42.ndjson and checks the adapter recovers the match; `--feed-seed N` for another mess, `--live --speed 1-20` to replay through the adapter in real time)
- Match stats: `cd core && sbt "stats --seed 42"` (team totals from the stats engine with basis tags)
- Run core service: `cd core && sbt run` (SSE on http://127.0.0.1:8080, override with `SMI_PORT`; try `curl -N http://127.0.0.1:8080/events/stream`)
- Run agent service: TODO
- Toolchain: JDK 25 (Temurin, installed with machine-wide JAVA_HOME; open a new terminal after install), Scala 3.9.0 LTS, sbt 1.13.0
- Run tests: `cd core && sbt test`; `cd agents && .venv/Scripts/python -m pytest`
- Lint and format: `cd core && sbt scalafmtCheckAll`; `cd agents && .venv/Scripts/ruff check . ../schema`
- Deploy: TODO

## Status
- [x] Local Scala spike: effect stack, model sync, SSE endpoint (docs/decisions.md ADR-002 to 004)
- [ ] Spikes: Scala to Event Hubs and Cosmos, Foundry model call, Agent Framework plus MCP hello workflow, hosted-agent deploy (2 h limit each)
- [ ] Phase 1: generator, raw feed, adapter
- [ ] Phase 2: engine and moment detectors
- [ ] Phase 3: agent workflow, personas, languages, verifier, recap
- [ ] Phase 4: viewer and overlays
- [ ] Phase 5: hardening, eval gate, deploy
- [ ] Phase 6: video, README, pitch, submit

## Glossary
xG: expected goals. PPDA: passes per defensive action (lower means more pressing). Possession: a run of consecutive actions by one team. Field tilt: share of final-third passes. Capability level: how much data a metric needs. Basis: where a number came from.
