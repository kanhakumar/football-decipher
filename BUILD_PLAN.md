# BUILD_PLAN.md: step-by-step build with Claude Code

Use with CLAUDE.md in the repo root. One step per Claude Code session. Start each session with `/clear`, then paste that step's prompt. Commit when the "Done when" check passes. Check `/usage` between steps.

Modes: **plan** means start in plan mode (Shift+Tab to cycle modes, or `claude --permission-mode plan`) and approve the plan before it edits. **build** means normal mode with edit approval as you prefer.
Rough effort: about 50 hours in total. Steps 0-6 need no Azure.

## Schedule (Oct 20 to Oct 27, IST)
- Oct 20 (evening): steps 0, 1, 2
- Oct 21: steps 3, 4, 5
- Oct 22: steps 6, 7, 8
- Oct 23: steps 10, 11 (use a stub LLM until Azure is ready)
- Oct 24: step 12 (and step 9 if time remains)
- Oct 25: step 13
- Oct 26: step 14
- Oct 27: buffer and submit early
If behind: skip step 9 first, then voice and the third language, then extra personas. Never skip steps 3 to 8, 11 or 12.

## Step 0: Repo bootstrap (build, 30 min) [x] DONE
Prompt: "Read CLAUDE.md. Set up the repo: git init, folders /schema /core /agents /viewer /infra /eval /docs, unzip the contract pack into place, add .gitignore, a README stub, an sbt skeleton in /core (Scala 3, scalafmt, ScalaCheck) and a Python project in /agents (ruff, pytest). Add a Python script in /schema that validates examples against the schemas. Do not add Azure code yet."
Done when: `sbt test` runs (even with no tests), `pytest` runs, and the schema script passes on the examples.

## Step 1: Local tech spike (plan, 1 to 2 hours) [x] DONE (Azure part deferred to step 13)
Prompt: "Plan and implement a minimal Scala spike in /core: pick ONE effect stack (ZIO or cats-effect with fs2) and justify it in one paragraph. Decide how Scala models stay in sync with the JSON Schema (codegen or hand-written plus golden tests). Stream one example event over a local HTTP SSE endpoint. Record decisions in docs/decisions.md and update CLAUDE.md conventions."
Done when: one decision per question is recorded and the SSE endpoint streams the example. If tooling fights you for over 2 hours, stop and ask me about switching the core to Python.
Azure part of the spike (Event Hubs and Cosmos from Scala) happens later, in step 13, once your account is ready.

## Step 2: Core models and golden tests (build, 1.5 hours) [x] DONE
Prompt: "Implement the canonical envelope, event, lineup, match_meta and tracking_frame types in Scala, plus Insight and Overlay. Add JSON codecs. Add golden tests that read every file in /schema/examples, decode, re-encode and compare, and that reject the negative cases we validated in Python."
Done when: round-trip tests pass for all examples.

## Step 3: Generator v0 (build, 2 hours) [x] DONE
Prompt: "Build a seeded match generator in /core: two fictional teams with 11 players each, a 12 x 8 pitch grid, a possession loop emitting only passes and shots with realistic distributions, team-relative coordinates, clocks and video_ts_ms, written as NDJSON envelopes. All randomness comes from one seeded RNG. Add a test that the same seed gives byte-identical output and that every line validates against the schema."
Done when: `generate --seed 42` produces a plausible 90-minute file and tests pass.

## Step 4: Generator v1 and scenario control (plan, 4 hours) [x] DONE
Prompt: "Extend the generator with possession changes, tackles, interceptions, pressure events, fouls and cards, set pieces, substitutions, and period start and end. Add a scenario YAML with team styles, tactical phases (for example a high-press spell from 55 to 70 minutes) and soft story beats (a counter-attack goal around minute 34, a red card around 78). Beats nudge probabilities so the story happens naturally. Add invariant tests: monotonic seq and clocks, spatial continuity, score equals goal events, 11 per side minus red cards."
Done when: invariants pass on 50 seeds and the scripted beats appear within a few minutes of target.

## Step 5: Calibration (build, 3 hours) [x] DONE
Prompt: "Write a calibration script that generates 50 seeds and prints averages for goals, shots, shots on target, passes per team, pass completion, possession split, fouls and events per match. Compare against the targets in CLAUDE.md and tune parameters until all are inside range. Save a short calibration report in /docs."
Done when: the report shows every metric in range.

## Step 6: Raw feed, adapter and feed emulator (build, 3 hours) [x] DONE
Prompt: "Add a provider-style raw feed emitter: our own type and qualifier ID registry in config, 0 to 100 coordinates, minute and second time, 0 or 1 outcomes, last_modified. Build the adapter that converts raw to canonical (rescale, ID mapping, update and delete). Build the feed emulator: replay at 1x to 20x, jitter, occasional out-of-order delivery, a few corrections and a short dropout. Tests: round trip, delete, out-of-order, missing qualifier, boundary coordinates."
Done when: raw to canonical to raw matches, and the emulator produces a messy but recoverable stream.

## Step 7: Stats engine (plan, 4 hours) [x] DONE
Prompt: "Plan then build the stats engine: match state, enriched fields (xG from distance and angle, pass difficulty, progressive, zones), per-minute player and team aggregates, and a metric registry where each metric declares what data it requires and a fallback. Handle updates and deletes by recomputing affected state. Outputs carry a basis tag."
Done when: tests confirm known sequences give known stats, and a correction updates the aggregates.

## Step 8: Moment detectors and insights (plan, 4 hours) [x] DONE
Prompt: "Plan then build detectors for goal, red card, key chance, momentum swing, pressure shift, control vs chaos, rhythm change and milestones. Each emits an Insight with facts, evidence event IDs, a why_it_matters code with params, importance and basis, validated against insight.schema.json. Test against the scripted storylines: the high-press spell, counter goal and red card must each be detected."
Done when: the scripted match yields a sensible ranked list of insights and each insight's numbers can be re-derived from its evidence events.

## Step 9: Level 1 tracking and workload (plan, 3 hours, optional) [x] DONE
Prompt: "Add a formation-anchored tracking synthesizer at 5 Hz: players move toward slots that shift with ball position and line height, with speed and acceleration limits and fatigue decline; the ball follows events; referees follow simple rules. Derive distance, top speed, sprint counts and a fatigue indicator per player per minute. Add a position provider interface that returns data or 'not available' so metrics degrade."
Done when: workload numbers are plausible and consistent with events.

## Step 10: Core API and MCP-ready endpoints (build, 3 hours) [x] DONE
Prompt: "Expose the core as HTTP and SSE: match control (start, pause, speed, seed), the viewer-delayed event stream, insights, overlay stream, and tool endpoints such as player workload, team shape, recent events and insight detail. Add OpenAPI. Keep the viewer delay configurable (default about 10 s)."
Done when: curl and a test client can drive a full replay.

## Step 11: Agent workflow with a stub LLM, then the real model (plan, 6 hours) [x] DONE (offline part; Foundry swap pending Azure)
Prompt: "In /agents, build a thin MCP server that wraps the core API tools. Then build the Microsoft Agent Framework workflow: Explainer, Narrator, Verifier, then Personalize and Localize (en, hi, es), with a retry edge and a template fallback. Put the model behind an interface and start with a deterministic stub so tests run offline. The Verifier checks every number against the insight facts and the engine. Add tests: wrong number rejected, unsupported claim rejected, correct line accepted, three failures fall back to template."
Then (once Azure is ready): swap the stub for the Foundry model deployment, using managed identity.
Done when: the offline tests pass, then one real insight produces verified lines in three languages.

## Step 12: Viewer and overlays (build, 6 hours) [x] DONE
Prompt: "Build the viewer: a single web page styled like a broadcast screen with a pitch or video placeholder, overlays scheduled by video_ts_ms (lower thirds, cards, momentum bar, player tag), a settings panel for persona, language, favourite club, favourite player and metric focus, a player-focus panel, a latency readout and a retract path for corrections. Use fictional branding only. Check it at desktop and phone width."
Done when: a full replay shows overlays on time, and switching persona or language changes the same moment.
Demo seed: 154 (chosen in step 8, recorded in CLAUDE.md): away counter goal 33', home red card 76', home press spell detected, ten-man home equaliser 81', final 2-2.

## Step 13: Azure deploy and hardening (plan, 6 hours)
Prompt: "Create Bicep and azd for Event Hubs, Cosmos DB (serverless), Container Apps (generator job, core, agents), Static Web Apps, Key Vault, Application Insights and managed identity, plus GitHub Actions. Move the stream to Event Hubs and persistence to Cosmos. Add OpenTelemetry tracing to Foundry. Add an eval gate: replay a recorded match, run the number-match check and Foundry evaluations, and fail the build on regression. Do not create resources until I approve the plan."
Done when: one `azd up` brings up everything and the eval gate runs in CI.

## Step 14: Package (build, 4 hours)
Prompt: "Write the README (problem, architecture diagram, the five stages, Azure services used, how to run, the adapter as path to production), an architecture doc, and a 2-minute demo script. Check the repo for any real names, logos or vendor material and remove them."
Done when: a clean clone deploys from the README, and the demo video (under 2 minutes) is recorded and public.

## Demo script (2 minutes)
1. 0:00 to 0:15: the problem and the one-line pitch.
2. 0:15 to 0:50: live replay with overlays and a "why this matters" card.
3. 0:50 to 1:15: same moment as analyst and casual fan, then Hindi and Spanish.
4. 1:15 to 1:30: player-focus mode.
5. 1:30 to 1:50: failure recovery (verifier rejection, correction retracting an overlay), and the trace in Foundry.
6. 1:50 to 2:00: recap and the architecture slide.
