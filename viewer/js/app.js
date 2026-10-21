// Decipher Live: the broadcast-style viewer. It plays the core's viewer-delayed feed, schedules
// overlays by video_ts_ms (D5, D8) and asks the agent service to narrate each moment in the
// viewer's own persona and language (D3). It computes no match facts itself: every number on
// screen comes from the core or from a verified narration.

import { InsightBook, displayClock, scoreAt, toScreen } from "./match.js";
import { OverlayQueue, ViewerClock } from "./timeline.js";

const params = new URLSearchParams(location.search);
const deployed = window.SMI_CONFIG ?? {}; // config.js: written by azd at deploy, local defaults here
const CORE = params.get("core") ?? deployed.core ?? "http://127.0.0.1:8080";
const AGENTS = params.get("agents") ?? deployed.agents ?? "http://127.0.0.1:8090";
const MIN_IMPORTANCE = Number(params.get("min") ?? 0.6);
const SVG = "http://www.w3.org/2000/svg";
const $ = (id) => document.getElementById(id);

const KIND = {
  en: { goal: "Goal", red_card: "Red card", key_chance: "Big chance", momentum_swing: "Momentum",
    pressure_shift: "Pressing", control_vs_chaos: "Control", rhythm_change: "Tempo", milestone: "Milestone" },
  es: { goal: "Gol", red_card: "Tarjeta roja", key_chance: "Ocasión", momentum_swing: "Impulso",
    pressure_shift: "Presión", control_vs_chaos: "Control", rhythm_change: "Ritmo", milestone: "Hito" },
  hi: { goal: "गोल", red_card: "लाल कार्ड", key_chance: "बड़ा मौका", momentum_swing: "रुख",
    pressure_shift: "दबाव", control_vs_chaos: "नियंत्रण", rhythm_change: "रफ़्तार", milestone: "उपलब्धि" },
};
const BADGES = {
  en: { verified: "Verified", template: "Template line", basis: { event_data: "Event data", tracking: "Tracking", event_estimate: "Estimate" } },
  es: { verified: "Verificado", template: "Plantilla", basis: { event_data: "Datos de eventos", tracking: "Seguimiento", event_estimate: "Estimación" } },
  hi: { verified: "सत्यापित", template: "टेम्पलेट", basis: { event_data: "इवेंट डेटा", tracking: "ट्रैकिंग", event_estimate: "अनुमान" } },
};
const STAT_LABELS = {
  passes: "Passes", passes_completed: "Passes completed", shots: "Shots", xg: "xG",
  progressive_passes: "Progressive passes", pressures: "Pressures", tackles_won: "Tackles won",
  interceptions: "Interceptions", distance_covered: "Distance", high_speed_distance: "High-speed distance",
  top_speed: "Top speed", sprints: "Sprints", fatigue_index: "Fatigue index",
};
const BASIS_SHORT = { event_data: "events", tracking: "tracking", event_estimate: "estimate" };

const clock = new ViewerClock();
const queue = new OverlayQueue({ minShowMs: 4500 });
const book = new InsightBook();

const state = {
  matchId: null,
  homeId: null,
  awayId: null,
  teams: {}, // team id -> lineup
  players: {}, // player id -> { name, shirt, team }
  settings: loadSettings(),
  settingsVersion: 0,
  moments: new Map(), // insight id -> { insight, overlay, narration, leadMs, roundTripMs }
  playback: null,
  fingerprint: null,
  streams: [],
  trail: [],
  dots: new Map(),
  tag: null, // { x, y, until }
  correction: null, // { overlay, until }
  replay: null, // { overlay, until }
  lastShownLateMs: null,
  lastScore: "",
  agentsOk: false,
};

// ---------------------------------------------------------------- settings

function loadSettings() {
  const fallback = { persona: "casual", lang: "en", team: "", player: "", metric: "" };
  try {
    return { ...fallback, ...JSON.parse(localStorage.getItem("decipher.settings") ?? "{}") };
  } catch {
    return fallback;
  }
}

function saveSettings() {
  try {
    localStorage.setItem("decipher.settings", JSON.stringify(state.settings));
  } catch {
    /* private mode: settings last for this page only */
  }
}

function bindSettings() {
  const fields = { persona: "persona", lang: "lang", team: "fav-team", player: "fav-player", metric: "metric" };
  for (const [key, id] of Object.entries(fields)) {
    const el = $(id);
    el.value = state.settings[key];
    el.addEventListener("change", () => {
      state.settings[key] = el.value;
      saveSettings();
      state.settingsVersion += 1;
      document.documentElement.lang = state.settings.lang;
      renarrateAll();
      refreshPlayerPanel();
    });
  }
  document.documentElement.lang = state.settings.lang;
}

// ---------------------------------------------------------------- core and agents

async function core(path, init) {
  const res = await fetch(CORE + path, init);
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return res.json();
}

function openStream(path, types, handler) {
  const es = new EventSource(CORE + path);
  for (const t of types) es.addEventListener(t, (e) => handler(JSON.parse(e.data), t));
  es.onerror = () => {
    // The core ends every stream when the match is over; do not keep reconnecting to it.
    if (!state.playback || ["finished", "idle"].includes(state.playback.status)) es.close();
  };
  return es;
}

function connect() {
  for (const es of state.streams) es.close();
  state.streams = [
    openStream("/api/stream/events", ["match_meta", "lineup", "event", "tracking_frame"], onEnvelope),
    openStream("/api/stream/insights", ["insight"], onInsight),
    openStream("/api/stream/overlays", ["overlay"], onCoreOverlay),
  ];
}

function resetMatch() {
  clock.reset();
  queue.clear();
  book.clear();
  state.moments.clear();
  state.matchId = null;
  state.trail = [];
  state.tag = state.correction = state.replay = null;
  for (const dot of state.dots.values()) dot.remove();
  state.dots.clear();
  $("ball").setAttribute("visibility", "hidden");
  $("ticker").textContent = "";
  renderMoments();
}

async function pollState() {
  try {
    const s = await core("/api/match/state");
    state.playback = s;
    clock.setPlayback(s.speed, s.status === "running");
    const fingerprint = s.run_id ?? null; // a new run: started here, by someone else, or by the generator job
    const restarted = fingerprint !== state.fingerprint || s.envelopes_viewer < (state.lastViewer ?? 0);
    state.lastViewer = s.envelopes_viewer;
    if (restarted && fingerprint) {
      state.fingerprint = fingerprint;
      resetMatch();
      connect();
    }
    renderStatus(s);
  } catch {
    $("status").textContent = `Core not reachable at ${CORE}. Start it with: cd core && sbt run`;
    $("live-chip").textContent = "OFFLINE";
    $("live-chip").className = "live-chip";
  }
}

async function checkAgents() {
  try {
    const res = await fetch(`${AGENTS}/health`);
    const h = await res.json();
    const cameBack = !state.agentsOk;
    state.agentsOk = true;
    state.agentModel = h.model;
    if (cameBack) renarrateAll(); // moments that arrived while the service was down
  } catch {
    state.agentsOk = false;
  }
}

// ---------------------------------------------------------------- feed (viewer side)

function onEnvelope(env) {
  const p = env.payload;
  if (!state.matchId) state.matchId = env.match_id;
  switch (env.payload_type) {
    case "match_meta":
      state.homeId = p.home_team_id;
      state.awayId = p.away_team_id;
      break;
    case "lineup":
      addLineup(p);
      break;
    case "event":
      clock.observe(p.video_ts_ms, performance.now());
      $("clock").textContent = displayClock(p.period, p.period_clock_s);
      showEvent(p);
      break;
    case "tracking_frame":
      clock.observe(p.video_ts_ms, performance.now());
      drawFrame(p);
      break;
  }
}

function addLineup(l) {
  state.teams[l.team_id] = l;
  for (const p of [...l.starters, ...l.bench]) {
    state.players[p.player_id] = { name: p.name, shirt: p.shirt, team: l.team_id };
  }
  const side = l.team_id === state.homeId ? "home" : "away";
  $(`${side}-short`).textContent = l.short;
  if (l.colors) document.documentElement.style.setProperty(`--${side}`, l.colors[0]);
  fillChoices();
}

function fillChoices() {
  const teamSel = $("fav-team");
  const playerSel = $("fav-player");
  teamSel.replaceChildren(new Option("None", ""));
  playerSel.replaceChildren(new Option("None", ""));
  for (const l of Object.values(state.teams)) {
    teamSel.add(new Option(l.name, l.team_id));
    const group = document.createElement("optgroup");
    group.label = l.name;
    for (const p of [...l.starters, ...l.bench]) group.append(new Option(`${p.shirt} ${p.name}`, p.player_id));
    playerSel.add(group);
  }
  teamSel.value = state.settings.team;
  playerSel.value = state.settings.player;
}

function prettyType(t) {
  return t.replaceAll("_", " ").replace(/^./, (c) => c.toUpperCase());
}

function showEvent(e) {
  if (e.x === undefined || e.y === undefined) return;
  const { x, y } = toScreen(e.team_id, state.homeId, e.x, e.y);
  if (!state.dots.size) {
    const ball = $("ball");
    ball.setAttribute("visibility", "visible");
    ball.setAttribute("cx", x);
    ball.setAttribute("cy", y);
  }
  // Without tracking there are no player positions: the trail shows where the last actions
  // happened, in the colour of the team that made them.
  state.trail = [...state.trail.slice(-5), { x, y, home: e.team_id === state.homeId }];
  $("trail").replaceChildren(
    ...state.trail.map((t) => {
      const c = document.createElementNS(SVG, "circle");
      c.setAttribute("cx", t.x);
      c.setAttribute("cy", t.y);
      c.setAttribute("r", 0.9);
      c.style.fill = t.home ? "var(--home)" : "var(--away)";
      return c;
    }),
  );
  $("trail").setAttribute("class", "trail");
  const who = e.player_id ? state.players[e.player_id]?.name : null;
  $("ticker").textContent = `${prettyType(e.type)}${who ? ` · ${who}` : ""}${e.outcome ? ` · ${e.outcome.replaceAll("_", " ")}` : ""}`;
  if (e.player_id && e.player_id === state.settings.player && !state.dots.size) {
    state.tag = { x, y, until: performance.now() + 3500 };
  }
}

function drawFrame(f) {
  $("trail").replaceChildren();
  const ball = $("ball");
  ball.setAttribute("visibility", "visible");
  ball.setAttribute("cx", f.ball.x);
  ball.setAttribute("cy", f.ball.y);
  const seen = new Set();
  for (const p of f.players) {
    seen.add(p.player_id);
    let dot = state.dots.get(p.player_id);
    if (!dot) {
      dot = document.createElementNS(SVG, "circle");
      dot.setAttribute("r", 1.1);
      $("players").append(dot);
      state.dots.set(p.player_id, dot);
    }
    dot.setAttribute("cx", p.x);
    dot.setAttribute("cy", p.y);
    dot.setAttribute("fill", p.team_id === state.homeId ? "var(--home)" : "var(--away)");
    const fav = p.player_id === state.settings.player;
    dot.setAttribute("class", fav ? "dot fav" : "dot");
    if (fav) state.tag = { x: p.x, y: p.y, until: performance.now() + 1000 };
  }
  for (const [id, dot] of state.dots) {
    if (!seen.has(id)) {
      dot.remove();
      state.dots.delete(id);
    }
  }
}

// ---------------------------------------------------------------- insights (live side)

function onInsight(insight) {
  for (const id of book.add(insight)) withdraw(id);
  if (insight.status === "retracted" || insight.importance < MIN_IMPORTANCE) return;
  state.moments.set(insight.insight_id, { insight });
  enqueueNarration(insight.insight_id);
}

function withdraw(insightId) {
  const pulled = queue.retractInsight(insightId);
  const m = state.moments.get(insightId);
  if (m) m.retracted = true;
  const onScreen = pulled.find((i) => i.wasShowing);
  if (onScreen) state.correction = { overlay: onScreen.overlay, until: performance.now() + 4000 };
  renderMoments();
}

let chain = Promise.resolve();

function enqueueNarration(insightId) {
  chain = chain.then(() => narrate(insightId)).catch((e) => console.warn(e));
}

async function narrate(insightId) {
  const m = state.moments.get(insightId);
  if (!m || m.retracted || !state.agentsOk) return;
  const s = state.settings;
  const version = state.settingsVersion;
  const started = performance.now();
  const res = await fetch(`${AGENTS}/api/narrate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      insight_id: insightId,
      match_id: m.insight.match_id,
      persona: s.persona,
      lang: s.lang,
      favourite_team: s.team || null,
      favourite_player: s.player || null,
      metric_focus: s.metric || null,
    }),
  });
  if (!res.ok) throw new Error(`narrate ${insightId}: ${res.status} ${await res.text()}`);
  const data = await res.json();
  if (version !== state.settingsVersion || m.retracted) return; // settings changed meanwhile
  const now = clock.now(performance.now());
  Object.assign(m, {
    overlay: data.overlay,
    narration: data.narration,
    roundTripMs: performance.now() - started,
    leadMs: now === null ? null : data.overlay.t_start_ms - now,
  });
  state.lastNarration = m;
  queue.replaceForInsight(data.overlay);
  renderMoments();
}

function renarrateAll() {
  const ordered = [...state.moments.values()].sort(
    (a, b) => (b.insight.video_ts_ms ?? 0) - (a.insight.video_ts_ms ?? 0),
  );
  for (const m of ordered) enqueueNarration(m.insight.insight_id);
}

function onCoreOverlay(o) {
  // The core's own overlays (scoreline updates) and retractions. Narrated cards come from the
  // agent service in this viewer's settings, so agent overlays published for the default
  // persona are not shown twice.
  if (o.op === "retract") {
    const item = queue.retract(o.overlay_id);
    if (item?.wasShowing) state.correction = { overlay: item.overlay, until: performance.now() + 4000 };
    return;
  }
  if (o.type === "scoreline_update") queue.put(o);
}

// ---------------------------------------------------------------- rendering

function renderCard(o, { retracted = false } = {}) {
  const card = $("lower-third");
  const m = state.moments.get(o.insight_id);
  const lang = o.lang ?? "en";
  const kind = m ? KIND[lang]?.[m.insight.kind] ?? m.insight.kind : "";
  const minute = m ? momentMinute(m.insight) : null;
  if (card.dataset.key !== `${o.overlay_id}|${retracted}`) {
    card.dataset.key = `${o.overlay_id}|${retracted}`;
    card.hidden = false;
    card.classList.toggle("retracted", retracted);
    card.style.animation = "none";
    void card.offsetWidth; // restart the slide-in
    card.style.animation = "";
    $("card-kicker").textContent = [kind, minute ? `${minute}'` : ""].filter(Boolean).join(" · ");
    $("card-headline").textContent = o.content.headline;
    $("card-body").textContent = o.content.body ?? "";
    $("card-metrics").replaceChildren(
      ...(o.content.metrics ?? []).map((mt) => {
        const li = document.createElement("li");
        li.append(mt.label);
        const b = document.createElement("b");
        b.textContent = `${mt.value}${mt.unit ?? ""}`;
        li.append(b);
        return li;
      }),
    );
    const words = BADGES[lang] ?? BADGES.en;
    const badges = [];
    if (o.verified) badges.push(["ok", `✓ ${words.verified}`]);
    if (o.template_fallback) badges.push(["warn", words.template]);
    if (o.basis) badges.push(["", words.basis[o.basis] ?? o.basis]);
    $("card-badges").replaceChildren(
      ...badges.map(([cls, text]) => {
        const s = document.createElement("span");
        s.className = `badge ${cls}`;
        s.textContent = text;
        return s;
      }),
    );
    const team = o.content.team_id;
    card.querySelector(".stripe").style.background = team === state.awayId ? "var(--away)" : "var(--home)";
    card.lang = lang;
  }
  $("card-correction").hidden = !retracted;
}

function renderCorner(item) {
  const corner = $("corner-card");
  if (!item) {
    corner.hidden = true;
    corner.dataset.key = "";
    return;
  }
  if (corner.dataset.key === item.overlay.overlay_id) return;
  corner.dataset.key = item.overlay.overlay_id;
  corner.hidden = false;
  const kicker = document.createElement("small");
  kicker.textContent = "SCORE UPDATE";
  corner.replaceChildren(kicker, item.overlay.content.headline);
}

function renderScore(ts) {
  if (!state.homeId) return;
  const score = scoreAt(book.goals(), ts ?? 0, [state.homeId, state.awayId]);
  const text = `${score[state.homeId]}-${score[state.awayId]}`;
  if (text === state.lastScore) return;
  state.lastScore = text;
  $("home-score").textContent = score[state.homeId];
  $("away-score").textContent = score[state.awayId];
}

function renderTag(now) {
  const tag = $("player-tag");
  const p = state.players[state.settings.player];
  if (!p || !state.tag || now > state.tag.until) {
    tag.setAttribute("visibility", "hidden");
    return;
  }
  const label = `${p.shirt} ${p.name}`;
  tag.querySelector("text").textContent = label;
  tag.querySelector("rect").setAttribute("width", label.length * 1.45 + 2);
  tag.setAttribute("transform", `translate(${Math.min(state.tag.x, 80)} ${Math.max(state.tag.y, 7)})`);
  tag.setAttribute("visibility", "visible");
}

/** The minute a moment is filed under: its own minute, or the end of the spell it describes. */
function momentMinute(insight) {
  const f = insight.facts;
  const minute = f.minute ?? f.after_to_minute;
  return minute === undefined ? null : Math.round(minute);
}

function fmtS(ms) {
  return `${(ms / 1000).toFixed(1)} s`;
}

function renderLatency() {
  const s = state.playback;
  if (s && s.status !== "idle") {
    const real = s.delay_s / s.speed;
    $("lat-delay").textContent = `${s.delay_s} s (${real < 10 ? real.toFixed(1) : Math.round(real)} s real)`;
  }
  const n = state.lastNarration;
  if (n?.narration) {
    $("lat-narration").textContent = `${Math.round(n.roundTripMs)} ms`;
    $("lat-lead").textContent = n.leadMs === null ? "–" : `${fmtS(n.leadMs)} video`;
    $("lat-lead").style.color = n.leadMs !== null && n.leadMs < 0 ? "var(--warn)" : "";
  }
  if (state.lastShownLateMs !== null && s) {
    const realMs = state.lastShownLateMs / s.speed; // video ms late, in wall time
    $("lat-ontime").textContent =
      realMs <= 250 ? `on time (+${Math.round(realMs)} ms)` : `${(realMs / 1000).toFixed(1)} s late`;
    $("lat-ontime").style.color = realMs <= 250 ? "var(--ok)" : "var(--warn)";
  }
}

function renderStatus(s) {
  const chip = $("live-chip");
  const labels = { running: "LIVE", paused: "PAUSED", finished: "FULL TIME", idle: "WAITING" };
  chip.textContent = labels[s.status] ?? s.status.toUpperCase();
  chip.className = `live-chip ${s.status}`;
  $("pause").textContent = s.status === "paused" ? "Resume" : "Pause";
  const agents = state.agentsOk ? `agents: ${state.agentModel} model` : `agent service not reachable at ${AGENTS}`;
  $("status").textContent =
    s.status === "idle"
      ? `No match yet. Press Start. (${agents})`
      : `Seed ${s.seed} · ${s.speed}x · delay ${s.delay_s} s · ${
          s.tracking ? "tracking on (player dots)" : "tracking off (dots: last actions by team; tick Tracking and Start for players)"
        } · ${agents}`;
}

function renderMoments() {
  const now = clock.now(performance.now()) ?? -1;
  const list = [...state.moments.values()]
    .filter((m) => m.overlay && m.overlay.t_start_ms <= now) // never ahead of the viewer
    .sort((a, b) => b.overlay.t_start_ms - a.overlay.t_start_ms);
  $("moment-count").textContent = list.length ? `(${list.length})` : "";
  $("moments").replaceChildren(
    ...list.map((m) => {
      const li = document.createElement("li");
      li.className = m.retracted ? "retracted" : "";
      const min = document.createElement("span");
      min.className = "min";
      const minute = momentMinute(m.insight);
      min.textContent = minute === null ? "" : `${minute}'`;
      const head = document.createElement("span");
      head.className = "head";
      head.textContent = m.overlay.content.headline;
      head.lang = m.overlay.lang;
      const meta = document.createElement("span");
      meta.className = "meta";
      const how = m.retracted ? "withdrawn" : m.overlay.template_fallback ? "template" : "verified";
      meta.textContent = `${KIND.en[m.insight.kind] ?? m.insight.kind} · ${how}`;
      li.append(min, head, meta);
      li.addEventListener("click", () => {
        if (!m.retracted) state.replay = { overlay: m.overlay, until: performance.now() + 6000 };
      });
      return li;
    }),
  );
}

function frame() {
  const wall = performance.now();
  const ts = clock.now(wall);
  if (ts !== null) {
    for (const c of queue.tick(ts, wall)) {
      if (c.change === "shown" && c.item.slot === "lower") {
        state.lastShownLateMs = c.item.lateMs;
        renderMoments();
      }
    }
  }
  const correction = state.correction && wall < state.correction.until ? state.correction : null;
  const replay = state.replay && wall < state.replay.until ? state.replay : null;
  const current = queue.showing("lower");
  if (correction) renderCard(correction.overlay, { retracted: true });
  else if (current) renderCard(current.overlay);
  else if (replay) renderCard(state.moments.get(replay.overlay.insight_id)?.overlay ?? replay.overlay);
  else {
    $("lower-third").hidden = true;
    $("lower-third").dataset.key = "";
  }
  renderCorner(queue.showing("corner"));
  renderScore(ts);
  renderTag(wall);
  renderLatency();
}

// ---------------------------------------------------------------- momentum and player panel

async function refreshMomentum() {
  if (!state.homeId || !state.playback || state.playback.status === "idle") return;
  try {
    // From the live engine, which runs the viewer delay ahead; the bar is a smoothed 5-minute view.
    const s = await core(`/api/tools/team_stats?team=${state.homeId}&metrics=possession_share&last_minutes=5`);
    const m = s.metrics[0];
    if (m.value === undefined) return;
    const home = Math.round(m.value);
    $("momentum-home").style.flexGrow = home;
    $("momentum-away").style.flexGrow = 100 - home;
    const t = state.teams;
    $("momentum-figures").textContent =
      `${t[state.homeId]?.short ?? ""} ${home}% | ${100 - home}% ${t[state.awayId]?.short ?? ""}`;
    $("momentum-label").textContent = `Possession · last 5 min · ${BASIS_SHORT[m.basis] ?? m.basis}`;
  } catch {
    /* next poll */
  }
}

/** A stat's value and unit for the panel, as the core reported it (converted, never derived). */
function statText(m) {
  if (m.metric === "top_speed" && m.unit === "m/s") return [(m.value * 3.6).toFixed(1), " km/h"];
  if (m.unit === "m") return [Math.round(m.value).toLocaleString(), " m"];
  if (m.unit === "%") return [m.value.toFixed(1), "%"];
  if (m.unit === "events") return [m.value.toLocaleString(), " events"];
  return [Number.isInteger(m.value) ? m.value : m.value.toFixed(2), ""];
}

async function refreshPlayerPanel() {
  const id = state.settings.player;
  const box = $("player-box");
  if (!id || !state.players[id] || !state.playback || state.playback.status === "idle") {
    box.hidden = true;
    return;
  }
  box.hidden = false;
  const p = state.players[id];
  $("player-title").textContent = `Player focus · ${p.shirt} ${p.name}`;
  try {
    const [stats, workload] = await Promise.all([
      core(`/api/tools/player_stats?player=${id}`),
      core(`/api/tools/player_workload?player=${id}`),
    ]);
    const rows = [...stats.metrics, ...workload.metrics].filter((m) => m.value !== undefined);
    $("player-stats").replaceChildren(
      ...rows.flatMap((m) => {
        const dt = document.createElement("dt");
        dt.textContent = STAT_LABELS[m.metric] ?? m.metric;
        const dd = document.createElement("dd");
        const [value, unit] = statText(m);
        dd.textContent = `${value}${unit}`;
        const basis = document.createElement("small");
        basis.textContent = BASIS_SHORT[m.basis] ?? m.basis;
        dd.append(basis);
        return [dt, dd];
      }),
    );
    const distance = workload.metrics.find((m) => m.metric === "distance_covered");
    $("player-note").textContent =
      distance?.basis === "tracking"
        ? "Workload from 5 Hz tracking."
        : state.playback.tracking
          ? "Workload is an event count until tracking covers 90% of the window."
          : "No tracking in this match: workload is estimated from events or not available.";
  } catch {
    $("player-note").textContent = "Player stats unavailable right now.";
  }
}

// ---------------------------------------------------------------- controls

function bindControls() {
  $("start-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const body = {
      seed: Number($("seed").value),
      speed: Number($("speed").value),
      delay_s: Number($("delay").value),
      tracking: $("tracking").checked,
    };
    try {
      await core("/api/match/start", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
      state.fingerprint = null; // reconnect on the next poll
      await pollState();
    } catch (err) {
      $("status").textContent = `Could not start: ${err.message}`;
    }
  });
  $("pause").addEventListener("click", async () => {
    const action = state.playback?.status === "paused" ? "resume" : "pause";
    await core(`/api/match/${action}`, { method: "POST" }).catch(() => {});
    await pollState();
  });
}

// ---------------------------------------------------------------- start

bindSettings();
bindControls();
await checkAgents();
await pollState();
setInterval(pollState, 1000);
setInterval(checkAgents, 10_000);
setInterval(refreshMomentum, 3000);
setInterval(refreshPlayerPanel, 5000);
setInterval(renderMoments, 2000);
(function loop() {
  frame();
  requestAnimationFrame(loop);
})();
