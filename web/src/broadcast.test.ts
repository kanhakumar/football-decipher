import { afterEach, assert, beforeEach, test, vi } from "vitest";
import { Broadcast } from "./broadcast";
import { NO_AUTH } from "./lib/auth";
import type { Insight, Playback } from "./lib/types";

const CORE = "http://core.test";
const AGENTS = "http://agents.test";

const goal = (over: Partial<Insight> = {}): Insight => ({
  insight_id: "I_0002",
  match_id: "SYN-154",
  kind: "goal",
  period: 1,
  video_ts_ms: 600_000,
  teams: ["T_AWY", "T_HOM"],
  players: ["P_AWY_09"],
  facts: { minute: 11, team_goals: 1, opponent_goals: 0 },
  importance: 0.93,
  ...over,
});

/** A fake core and agent service; `narrate` decides each narration answer. */
function fakeServices(opts: { playback?: Partial<Playback>; insights?: Insight[]; failStart?: boolean } = {}) {
  const calls: { url: string; body?: any }[] = [];
  const playback: Playback = {
    status: "running", seed: 154, speed: 10, delay_s: 120, tracking: false, run_id: "run-1",
    envelopes_viewer: 0, live: { match_clock_s: 60 }, ...opts.playback,
  };
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  const fetchFn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, body });
    if (url === `${CORE}/api/config`) return json({ settings: { "moments:model_slots": 1 } });
    if (url === `${AGENTS}/health`) return json({ status: "ok", model: "stub" });
    if (url === `${CORE}/api/match/state`) return json(playback);
    if (url === `${CORE}/api/match/start`) return opts.failStart ? json({ error: "delay_s too big" }, 400) : json(playback);
    if (url === `${CORE}/api/tools/match_info`)
      return json({
        teams: [
          { team_id: "T_HOM", name: "Northgate FC", short: "NOR", formation: "4-3-3", starters: [{ player_id: "P_HOM_09", name: "Kofi Mensah", shirt: 9 }], bench: [] },
          { team_id: "T_AWY", name: "Eastmere Rovers", short: "EAS", formation: "4-2-3-1", starters: [{ player_id: "P_AWY_09", name: "Liam Carver", shirt: 9 }], bench: [] },
        ],
      });
    if (url.startsWith(`${CORE}/api/stream/insights`)) {
      const sse = (opts.insights ?? []).map((i, n) => `event: insight\nid: ${n}\ndata: ${JSON.stringify(i)}\n\n`).join("");
      return new Response(sse, { headers: { "Content-Type": "text/event-stream" } });
    }
    if (url.startsWith(`${CORE}/api/stream/`)) return new Response("", { headers: { "Content-Type": "text/event-stream" } });
    if (url === `${AGENTS}/api/narrate`) {
      const recap = body.insight_id === "I_0035";
      const headline = recap
        ? "Full time: ten-man Northgate FC take a point"
        : body.mode === "template" ? "Quick: Carver scores" : `Model: Carver scores (${body.lang})`;
      return json({
        overlay: {
          overlay_id: recap ? "O_00000035" : "O_00000001", match_id: "SYN-154", insight_id: body.insight_id, op: "show",
          type: recap ? "recap_card" : "insight_card", t_start_ms: recap ? 5_900_000 : 600_000,
          duration_ms: recap ? 30_000 : 8000, style: recap ? "full_width_banner" : "lower_third", lang: body.lang, persona: body.persona,
          content: { headline, body: "It is 0-1.", team_id: "T_AWY" }, verified: true, template_fallback: false, basis: "event_data",
        },
        narration: { source: body.mode, verified: true, template_fallback: false, model: "stub", latency_ms: 5 },
      });
    }
    return json({ error: "not found" }, 404);
  });
  return { fetchFn, calls, playback };
}

let wall = 0;
beforeEach(() => {
  vi.useFakeTimers();
  wall = 0;
});
afterEach(() => {
  vi.useRealTimers();
});

async function started(services: ReturnType<typeof fakeServices>, persona = "casual") {
  const saved = JSON.stringify({ persona });
  const storage = { getItem: () => saved, setItem: () => {} };
  const b = new Broadcast({ core: CORE, agents: AGENTS }, { auth: NO_AUTH, fetch: services.fetchFn, now: () => wall, storage });
  await b.start();
  await vi.advanceTimersByTimeAsync(0);
  return b;
}

test("a moment gets the quick line, then the model line in place, on time", async () => {
  const services = fakeServices({ insights: [goal()] });
  const b = await started(services);
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 2));
  const modes = services.calls.filter((c) => c.url.endsWith("/api/narrate")).map((c) => c.body.mode);
  assert.deepEqual(modes, ["template", "model"]);
  // The viewer reaches the moment: the card shows, with the model's line.
  b.onEnvelope({ match_id: "SYN-154", payload_type: "match_meta", payload: { home_team_id: "T_HOM", away_team_id: "T_AWY" } });
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "shot", period: 1, period_clock_s: 600, video_ts_ms: 600_000, team_id: "T_AWY", x: 99, y: 34 } });
  b.frame();
  const s = b.getSnapshot();
  assert.equal(s.card?.headline, "Model: Carver scores (en)");
  assert.equal(s.card?.kicker, "Goal · 11'");
  assert.isTrue(s.card?.away);
  assert.equal(s.moments[0].meta, "Goal · verified");
  assert.equal(s.latency.onTime, "on time (+0 ms)");
  b.stop();
});

test("a new language re-narrates every moment", async () => {
  const services = fakeServices({ insights: [goal()] });
  const b = await started(services);
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 2));
  b.setSetting("lang", "hi");
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 4));
  const last = services.calls.filter((c) => c.url.endsWith("/api/narrate")).at(-1)!;
  assert.equal(last.body.lang, "hi");
  b.stop();
});

test("a retracted moment is withdrawn and the card says so", async () => {
  const services = fakeServices({ insights: [goal()] });
  const b = await started(services);
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 2));
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "shot", period: 1, period_clock_s: 600, video_ts_ms: 600_000, team_id: "T_AWY" } });
  b.frame();
  b.onInsight(goal({ status: "retracted" }));
  b.frame();
  const s = b.getSnapshot();
  assert.isTrue(s.card?.retracted);
  assert.isTrue(s.moments[0].retracted);
  assert.match(s.moments[0].meta, /withdrawn/);
  b.stop();
});

test("before kickoff reaches the viewer, the pre-match screen counts down the delay", async () => {
  const services = fakeServices();
  const b = await started(services);
  const s = b.getSnapshot();
  // delay 120 s of video, live at 60 s, 10x: 6 s of real time to go.
  assert.equal(s.prematch?.countdownS, 6);
  assert.equal(s.home?.name, "Northgate FC");
  assert.equal(s.away?.formation, "4-2-3-1");
  wall = 2000;
  b.frame();
  assert.equal(b.getSnapshot().prematch?.countdownS, 4);
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "pass", period: 1, period_clock_s: 1, video_ts_ms: 1000, team_id: "T_HOM" } });
  b.frame();
  assert.isNull(b.getSnapshot().prematch);
  b.stop();
});

test("routine moments are template-only corner cards; the ticker-only ones are not listed", async () => {
  const services = fakeServices({
    insights: [goal({ insight_id: "I_0003", kind: "control_vs_chaos", importance: 0.45, facts: { after_to_minute: 20 } }), goal({ insight_id: "I_0004", kind: "milestone", importance: 0.3 })],
  });
  const b = await started(services, "analyst");
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 1));
  assert.deepEqual(services.calls.filter((c) => c.url.endsWith("/api/narrate")).map((c) => [c.body.insight_id, c.body.mode]), [["I_0003", "template"]]);
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "pass", period: 1, period_clock_s: 600, video_ts_ms: 600_000, team_id: "T_HOM" } });
  b.frame();
  const s = b.getSnapshot();
  assert.isNull(s.card);
  assert.equal(s.corner?.kicker, "Control · 20'");
  assert.deepEqual(s.moments.map((m) => m.id), ["I_0003"]);
  b.toggleGroup("flow");
  assert.deepEqual(b.getSnapshot().moments, [], "filtered out");
  b.stop();
});

test("a refused start shows the core's reason, and the button is busy meanwhile", async () => {
  const services = fakeServices({ failStart: true });
  const b = await started(services);
  const pending = b.startMatch({ seed: 154, speed: 10, delay_s: 9999, tracking: false });
  assert.isTrue(b.getSnapshot().busy.start);
  await pending;
  const s = b.getSnapshot();
  assert.isFalse(s.busy.start);
  assert.equal(s.toasts.at(-1)?.text, "Could not start: delay_s too big");
  b.stop();
});

test("a casual fan is not shown tactics; switching to analyst brings them in", async () => {
  const tactics = goal({ insight_id: "I_0005", kind: "tactical_shift", importance: 0.58, facts: { after_to_minute: 20 } });
  const services = fakeServices({ insights: [goal(), tactics] });
  const b = await started(services);
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 2));
  const narrated = () => services.calls.filter((c) => c.url.endsWith("/api/narrate")).map((c) => c.body.insight_id);
  assert.notInclude(narrated(), "I_0005", "never narrated for a casual fan");
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "pass", period: 1, period_clock_s: 1300, video_ts_ms: 1_300_000, team_id: "T_HOM" } });
  b.frame();
  assert.deepEqual(b.getSnapshot().moments.map((m) => m.id), ["I_0002"]);
  b.setSetting("persona", "analyst");
  await vi.waitFor(() => assert.include(narrated(), "I_0005"));
  b.frame();
  assert.sameMembers(b.getSnapshot().moments.map((m) => m.id), ["I_0002", "I_0005"]);
  b.stop();
});

test("Start stays busy, counting down, until kickoff reaches the viewer", async () => {
  const services = fakeServices({ playback: { status: "idle", run_id: undefined } });
  const b = await started(services);
  Object.assign(services.playback, { status: "running", run_id: "run-2", live: { match_clock_s: 0 } });
  await b.startMatch({ seed: 154, speed: 10, delay_s: 120, tracking: false });
  let s = b.getSnapshot();
  assert.isTrue(s.busy.start, "still waiting for kickoff");
  assert.equal(s.startingS, 12);
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_1", type: "pass", period: 1, period_clock_s: 1, video_ts_ms: 1000, team_id: "T_HOM" } });
  b.frame();
  s = b.getSnapshot();
  assert.isFalse(s.busy.start);
  assert.isNull(s.startingS);
  b.stop();
});

test("the full-time recap shows in its own centre slot at the final whistle, for a casual fan too", async () => {
  const whistle = goal({
    insight_id: "I_0035", kind: "full_time", period: 2, video_ts_ms: 5_900_000, importance: 0.9,
    teams: ["T_HOM", "T_AWY"], players: ["P_HOM_09"], facts: { minute: 96, team_goals: 2, opponent_goals: 2 },
  });
  const services = fakeServices({ insights: [whistle] });
  const b = await started(services);
  await vi.waitFor(() => assert.equal(services.calls.filter((c) => c.url.endsWith("/api/narrate")).length, 2));
  b.onEnvelope({ match_id: "SYN-154", payload_type: "match_meta", payload: { home_team_id: "T_HOM", away_team_id: "T_AWY" } });
  b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: "E_9", type: "period_end", period: 2, period_clock_s: 2940, video_ts_ms: 5_900_000, team_id: "T_HOM" } });
  b.frame();
  const s = b.getSnapshot();
  assert.equal(s.recap?.headline, "Full time: ten-man Northgate FC take a point");
  assert.equal(s.recap?.kicker, "Full time · 96'");
  assert.isNull(s.card, "the lower third stays free");
  b.stop();
});

test("replaying a tactics moment redraws its shape map, even if it passed while you were a casual fan", async () => {
  const shift = goal({ insight_id: "I_0005", kind: "tactical_shift", importance: 0.58, video_ts_ms: 1_200_000, facts: { after_to_minute: 20 } });
  const services = fakeServices({ insights: [shift] });
  const b = await started(services); // casual: no shape maps
  b.onEnvelope({ match_id: "SYN-154", payload_type: "match_meta", payload: { home_team_id: "T_HOM", away_team_id: "T_AWY" } });
  b.onCoreOverlay({
    overlay_id: "O_90000005", match_id: "SYN-154", insight_id: "I_0005", op: "show", type: "shape_map",
    t_start_ms: 1_200_000, duration_ms: 8000, style: "pitch_overlay",
    content: { headline: "Northgate FC drop deeper", team_id: "T_HOM", positions: [{ player_id: "P_HOM_09", x: 40, y: 30 }] },
  } as never);
  const at = (ms: number) => {
    b.onEnvelope({ match_id: "SYN-154", payload_type: "event", payload: { event_id: `E_${ms}`, type: "pass", period: 1, period_clock_s: ms / 1000, video_ts_ms: ms, team_id: "T_HOM" } });
    b.frame();
  };
  at(1_201_000);
  assert.isNull(b.getSnapshot().shape, "a casual fan gets no shape map");
  wall += 5000; // the map has had its minimum time on screen
  at(2_400_000); // 40': the map's time is long past
  b.setSetting("persona", "analyst");
  await vi.waitFor(() => assert.isTrue(services.calls.some((c) => c.url.endsWith("/api/narrate") && c.body.insight_id === "I_0005")));
  b.frame();
  assert.isNull(b.getSnapshot().shape);
  b.replay("I_0005");
  b.frame();
  assert.equal(b.getSnapshot().shape?.caption.split(" · ")[0], "Northgate FC drop deeper");
  wall += 7000;
  b.frame();
  assert.isNull(b.getSnapshot().shape, "the replay ends with the card");
  b.stop();
});
