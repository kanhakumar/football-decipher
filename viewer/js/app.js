// Decipher Live: the broadcast-style viewer. It plays the core's viewer-delayed feed, schedules
// overlays by video_ts_ms (D5, D8) and asks the agent service to narrate each moment in the
// viewer's own persona and language (D3). It computes no match facts itself: every number on
// screen comes from the core or from a verified narration.

import { GROUPS, InsightBook, displayClock, groupOf, scoreAt, shapeDots, tierOf, toScreen } from "./match.js";
import { OverlayQueue, ViewerClock, suggestDelay } from "./timeline.js";
import { createAuth } from "./auth.js";
import { followStream } from "./sse.js";
import { viewSettings } from "./settings.js";

const params = new URLSearchParams(location.search);
const deployed = window.SMI_CONFIG ?? {}; // config.js: written by azd at deploy, local defaults here
const CORE = params.get("core") ?? deployed.core ?? "http://127.0.0.1:8080";
const AGENTS = params.get("agents") ?? deployed.agents ?? "http://127.0.0.1:8090";
const auth = createAuth(deployed.auth); // Entra ID sign-in when config.js names an app (ADR-018)
const SVG = "http://www.w3.org/2000/svg";
const $ = (id) => document.getElementById(id);

const KIND = {
  en: { goal: "Goal", red_card: "Red card", key_chance: "Big chance", momentum_swing: "Momentum",
    pressure_shift: "Pressing", control_vs_chaos: "Control", rhythm_change: "Tempo", milestone: "Milestone",
    player_spotlight: "Key pass", tactical_shift: "Tactics" },
  es: { goal: "Gol", red_card: "Tarjeta roja", key_chance: "Ocasión", momentum_swing: "Impulso",
    pressure_shift: "Presión", control_vs_chaos: "Control", rhythm_change: "Ritmo", milestone: "Hito",
    player_spotlight: "Pase clave", tactical_shift: "Táctica" },
  hi: { goal: "गोल", red_card: "लाल कार्ड", key_chance: "बड़ा मौका", momentum_swing: "रुख",
    pressure_shift: "दबाव", control_vs_chaos: "नियंत्रण", rhythm_change: "रफ़्तार", milestone: "उपलब्धि",
    player_spotlight: "अहम पास", tactical_shift: "रणनीति" },
};
const BADGES = {
  en: { verified: "Verified", template: "Template line", quick: "Quick line", pending: "Analysing this moment…", basis: { event_data: "Event data", tracking: "Tracking", event_estimate: "Estimate" } },
  es: { verified: "Verificado", template: "Plantilla", quick: "Línea rápida", pending: "Analizando la jugada…", basis: { event_data: "Datos de eventos", tracking: "Seguimiento", event_estimate: "Estimación" } },
  hi: { verified: "सत्यापित", template: "टेम्पलेट", quick: "त्वरित पंक्ति", pending: "इस पल का विश्लेषण…", basis: { event_data: "इवेंट डेटा", tracking: "ट्रैकिंग", event_estimate: "अनुमान" } },
};
const GROUP_LABELS = { chances: "Goals and chances", flow: "Pressing and momentum", tactics: "Tactics", players: "Players" };
const STAT_LABELS = {
  passes: "Passes", passes_completed: "Passes completed", shots: "Shots", xg: "xG",
  progressive_passes: "Progressive passes", pressures: "Pressures", tackles_won: "Tackles won",
  interceptions: "Interceptions", distance_covered: "Distance", high_speed_distance: "High-speed distance",
  top_speed: "Top speed", sprints: "Sprints", fatigue_index: "Fatigue index",
};
const BASIS_SHORT = { event_data: "events", tracking: "tracking", event_estimate: "estimate" };

const clock = new ViewerClock();
const queue = new OverlayQueue({ minShowMs: 4500 });
let view = viewSettings(null); // the core's settings (ADR-019), refreshed every 60 s
const book = new InsightBook();

const state = {
  matchId: null,
  homeId: null,
  awayId: null,
  teams: {}, // team id -> lineup
  players: {}, // player id -> { name, shirt, team }
  settings: loadSettings(),
  settingsVersion: 0,
  moments: new Map(), // insight id -> { insight, overlay, narration, source, leadMs, roundTripMs }
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
  coreOk: true,
  quickMs: null, // round trip of the last quick (template) line
};

// ---------------------------------------------------------------- settings

function loadSettings() {
  // A first-time viewer's persona and language come from the settings once they load.
  const fallback = { persona: "", lang: "", team: "", player: "", metric: "", groups: Object.keys(GROUPS) };
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
  savePreferences();
}

// ---------------------------------------------------------------- sign-in and preferences

const PREFERENCE_KEYS = ["persona", "lang", "team", "player", "metric", "groups"];
let preferenceTimer = null;

/** A signed-in viewer's settings follow them to any device (the agent service keeps them). */
async function loadPreferences() {
  if (!auth.account) return;
  try {
    const p = await agents("/api/me/preferences");
    if (!p.saved) return savePreferences(); // first visit: keep what this browser had
    for (const k of PREFERENCE_KEYS) state.settings[k] = p[k];
    try {
      localStorage.setItem("decipher.settings", JSON.stringify(state.settings));
    } catch {
      /* private mode */
    }
    syncSettingControls();
  } catch (e) {
    toast(`Could not load your preferences: ${e.message}`, "warn");
  }
}

function savePreferences() {
  if (!auth.account) return;
  clearTimeout(preferenceTimer);
  const body = Object.fromEntries(PREFERENCE_KEYS.map((k) => [k, state.settings[k]]));
  preferenceTimer = setTimeout(() => {
    agents("/api/me/preferences", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    }).catch((e) => toast(`Could not save your preferences: ${e.message}`, "warn"));
  }, 500);
}

function renderAccount() {
  const box = $("account");
  box.hidden = !auth.enabled;
  if (!auth.enabled) return;
  const button = document.createElement("button");
  button.type = "button";
  button.className = "link";
  if (auth.account) {
    const who = document.createElement("span");
    who.textContent = `Signed in as ${auth.account.name ?? auth.account.username}`;
    button.textContent = "Sign out";
    button.addEventListener("click", () => auth.signOut());
    box.replaceChildren(who, button);
  } else {
    button.textContent = "Sign in";
    button.addEventListener("click", () => auth.signIn());
    box.replaceChildren(button);
  }
}

function bindFilters() {
  $("moment-filters").replaceChildren(
    ...Object.keys(GROUPS).map((g) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "filter";
      b.textContent = GROUP_LABELS[g];
      const sync = () => b.setAttribute("aria-pressed", String(state.settings.groups.includes(g)));
      b.addEventListener("click", () => {
        const on = new Set(state.settings.groups);
        if (on.has(g)) on.delete(g);
        else on.add(g);
        state.settings.groups = Object.keys(GROUPS).filter((k) => on.has(k));
        saveSettings();
        sync();
        renderMoments();
      });
      sync();
      return b;
    }),
  );
}

const SETTING_FIELDS = { persona: "persona", lang: "lang", team: "fav-team", player: "fav-player", metric: "metric" };

/** Puts the settings into the controls (after loading a signed-in user's preferences). */
function syncSettingControls() {
  for (const [key, id] of Object.entries(SETTING_FIELDS)) $(id).value = state.settings[key];
  document.documentElement.lang = state.settings.lang;
  bindFilters();
  state.settingsVersion += 1;
  renarrateAll();
}

function bindSettings() {
  for (const [key, id] of Object.entries(SETTING_FIELDS)) {
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

/** Fetch with the signed-in user's token (none when sign-in is off). */
async function authFetch(url, init = {}) {
  return fetch(url, { ...init, headers: { ...(init.headers ?? {}), ...(await auth.headers()) } });
}

async function agents(path, init) {
  const res = await authFetch(AGENTS + path, init);
  if (!res.ok) {
    const detail = await res.json().then((b) => b.error).catch(() => null);
    throw new Error(detail ?? `${path}: ${res.status}`);
  }
  return res.json();
}

async function core(path, init) {
  const res = await authFetch(CORE + path, init);
  if (!res.ok) {
    const detail = await res.json().then((b) => b.error).catch(() => null);
    throw new Error(detail ?? `${path}: ${res.status}`);
  }
  return res.json();
}

/** Follows one core stream with the user's token, resuming after the last event on a dropped
 * connection, until the match is over or the stream is aborted (a new run). */
function openStream(path, types, handler) {
  const control = new AbortController();
  let lastId = null;
  (async () => {
    while (!control.signal.aborted) {
      try {
        await followStream(CORE + path, {
          signal: control.signal,
          headers: async () => ({ ...(await auth.headers()), ...(lastId === null ? {} : { "Last-Event-ID": lastId }) }),
          onEvent: (type, data, id) => {
            if (id !== null) lastId = id;
            if (types.includes(type)) handler(JSON.parse(data), type);
          },
        });
      } catch {
        if (control.signal.aborted) return;
      }
      // The core ends every stream when the match is over; do not keep reconnecting to it.
      if (!state.playback || ["finished", "idle"].includes(state.playback.status)) return;
      await new Promise((r) => setTimeout(r, 1000));
    }
  })();
  return control;
}

function connect() {
  for (const stream of state.streams) stream.abort();
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
    state.coreOk = true;
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
    state.coreOk = false;
    $("status").textContent = `Core not reachable at ${CORE}.`;
    $("live-chip").textContent = "OFFLINE";
    $("live-chip").className = "live-chip";
  }
  renderHealth();
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
  renderHealth();
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
  const tier = tierOf(insight.importance, view);
  if (insight.status === "retracted" || tier === "ticker") return;
  state.moments.set(insight.insight_id, { insight, tier });
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

// Every moment gets the template line at once (D4: a card is on the timeline before its moment),
// then the model line, which replaces it in place. Model requests run view.modelSlots at a time,
// the moment the viewer reaches first going first.
const waiting = new Set();
let inFlight = 0;

function enqueueNarration(insightId) {
  requestLine(insightId, "template").catch(reportNarrationError);
  if (state.moments.get(insightId)?.tier !== "model") return; // routine moments: the template line only
  waiting.add(insightId);
  pumpModel();
}

function pumpModel() {
  while (inFlight < view.modelSlots && waiting.size) {
    const next = [...waiting].sort(
      (a, b) => (state.moments.get(a)?.insight.video_ts_ms ?? 0) - (state.moments.get(b)?.insight.video_ts_ms ?? 0),
    )[0];
    waiting.delete(next);
    inFlight += 1;
    requestLine(next, "model")
      .catch(reportNarrationError)
      .finally(() => {
        inFlight -= 1;
        pumpModel();
      });
  }
}

function reportNarrationError(e) {
  if (!String(e.message).includes(": 409")) toast(`Narration failed: ${e.message}`, "warn"); // 409: an old match
}

async function requestLine(insightId, mode) {
  const m = state.moments.get(insightId);
  if (!m || m.retracted || !state.agentsOk) return;
  const s = state.settings;
  const version = state.settingsVersion;
  const started = performance.now();
  const res = await authFetch(`${AGENTS}/api/narrate`, {
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
      mode,
    }),
  });
  if (!res.ok) throw new Error(`narrate ${insightId}: ${res.status} ${await res.text()}`);
  const data = await res.json();
  if (version !== state.settingsVersion || m.retracted) return; // settings changed meanwhile
  if (mode === "template" && m.source === "model" && m.version === version) return; // model was faster
  const roundTripMs = performance.now() - started;
  if (mode === "template") {
    state.quickMs = roundTripMs;
  } else {
    const now = clock.now(performance.now());
    Object.assign(m, { roundTripMs, leadMs: now === null ? null : data.overlay.t_start_ms - now });
    state.lastNarration = m;
  }
  // Routine moments go to the corner card, so the lower third stays for the big ones.
  const shown = m.tier === "template" ? { ...data.overlay, style: "corner_card" } : data.overlay;
  Object.assign(m, { overlay: shown, narration: data.narration, source: mode, version });
  queue.replaceForInsight(shown);
  renderMoments();
}

function renarrateAll() {
  for (const m of state.moments.values()) {
    m.source = null;
    enqueueNarration(m.insight.insight_id);
  }
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
  if (o.type === "scoreline_update" || o.type === "shape_map") queue.put(o);
}

// ---------------------------------------------------------------- rendering

function renderCard(o, { retracted = false } = {}) {
  const card = $("lower-third");
  const m = state.moments.get(o.insight_id);
  const lang = o.lang ?? "en";
  const kind = m ? KIND[lang]?.[m.insight.kind] ?? m.insight.kind : "";
  const minute = m ? momentMinute(m.insight) : null;
  // The model line reuses the quick line's overlay id: the key includes the text so it swaps in.
  const key = `${o.overlay_id}|${o.content.headline}|${o.content.body ?? ""}|${retracted}`;
  if (card.dataset.key !== key) {
    const sameCard = card.dataset.key?.startsWith(`${o.overlay_id}|`);
    card.dataset.key = key;
    card.hidden = false;
    card.classList.toggle("retracted", retracted);
    card.classList.remove("pending");
    if (!sameCard) {
      card.style.animation = "none";
      void card.offsetWidth; // restart the slide-in, but not when the model line replaces the quick one
      card.style.animation = "";
    }
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
    else if (m?.source === "template") badges.push(["quick", words.quick]);
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

/** The moment is on screen but no line has arrived yet: a placeholder instead of nothing. */
function renderPending(m) {
  const card = $("lower-third");
  const key = `pending|${m.insight.insight_id}|${state.settings.lang}`;
  if (card.dataset.key === key) return;
  card.dataset.key = key;
  card.hidden = false;
  card.classList.remove("retracted");
  card.classList.add("pending");
  const lang = state.settings.lang;
  const minute = momentMinute(m.insight);
  $("card-kicker").textContent = [KIND[lang]?.[m.insight.kind] ?? m.insight.kind, minute ? `${minute}'` : ""]
    .filter(Boolean)
    .join(" · ");
  $("card-headline").textContent = (BADGES[lang] ?? BADGES.en).pending;
  $("card-body").textContent = "";
  $("card-metrics").replaceChildren();
  $("card-badges").replaceChildren();
  $("card-correction").hidden = true;
}

/** A moment the viewer has reached in the last 8 s of video whose line has not arrived. */
function pendingMoment(ts) {
  if (ts === null) return null;
  for (const m of state.moments.values()) {
    const t = m.insight.video_ts_ms;
    if (m.tier === "model" && !m.overlay && !m.retracted && t <= ts && ts < t + 8000) return m;
  }
  return null;
}

function renderCorner(item) {
  const corner = $("corner-card");
  if (!item) {
    corner.hidden = true;
    corner.dataset.key = "";
    return;
  }
  const o = item.overlay;
  const key = `${o.overlay_id}|${o.content.headline}`;
  if (corner.dataset.key === key) return;
  corner.dataset.key = key;
  corner.hidden = false;
  const kicker = document.createElement("small");
  const m = state.moments.get(o.insight_id);
  if (o.type === "scoreline_update" || !m) {
    kicker.textContent = "SCORE UPDATE";
    corner.replaceChildren(kicker, o.content.headline);
    return;
  }
  const minute = momentMinute(m.insight);
  kicker.textContent = [KIND[o.lang ?? "en"]?.[m.insight.kind] ?? m.insight.kind, minute ? `${minute}'` : ""].filter(Boolean).join(" · ");
  const head = document.createElement("b");
  head.textContent = o.content.headline;
  const body = document.createElement("span");
  body.className = "corner-body";
  body.textContent = o.content.body ?? "";
  corner.lang = o.lang ?? "en";
  corner.replaceChildren(kicker, head, body);
}

/** The shape map: average positions of one team over a spell, drawn on the pitch. */
function renderShape(item) {
  const g = $("shape");
  const caption = $("shape-caption");
  if (!item) {
    if (g.dataset.key) g.replaceChildren();
    g.dataset.key = "";
    caption.hidden = true;
    return;
  }
  const o = item.overlay;
  if (g.dataset.key === o.overlay_id) return;
  g.dataset.key = o.overlay_id;
  g.replaceChildren(
    ...shapeDots(o, state.homeId, state.players).flatMap((d) => {
      const c = document.createElementNS(SVG, "circle");
      c.setAttribute("cx", d.x);
      c.setAttribute("cy", d.y);
      c.setAttribute("r", 2);
      c.style.fill = d.home ? "var(--home)" : "var(--away)";
      const t = document.createElementNS(SVG, "text");
      t.setAttribute("x", d.x);
      t.setAttribute("y", d.y + 0.9);
      t.textContent = d.shirt;
      return [c, t];
    }),
  );
  caption.hidden = false;
  const formation = o.content.formation ? ` · lineup ${o.content.formation}` : "";
  caption.textContent = `${o.content.headline}${formation} · players with actions in the spell, estimated from events`;
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
  if (state.quickMs !== null) $("lat-quick").textContent = `${Math.round(state.quickMs)} ms`;
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

function renderHealth() {
  const agents = $("agents-chip");
  agents.textContent = state.agentsOk ? `Agents: ${state.agentModel}` : "Agents: unreachable";
  agents.className = `chip ${state.agentsOk ? "ok" : "bad"}`;
  const core = $("core-chip");
  core.textContent = state.coreOk ? "Core: connected" : "Core: unreachable";
  core.className = `chip ${state.coreOk ? "ok" : "bad"}`;
  $("banner").hidden = state.coreOk;
}

function renderStatus(s) {
  const chip = $("live-chip");
  const labels = { running: "LIVE", paused: "PAUSED", finished: "FULL TIME", idle: "WAITING" };
  chip.textContent = labels[s.status] ?? s.status.toUpperCase();
  chip.className = `live-chip ${s.status}`;
  $("pause").textContent = s.status === "paused" ? "Resume" : "Pause";
  $("pause").disabled = !["running", "paused"].includes(s.status);
  $("status").textContent =
    s.status === "idle"
      ? "No match yet. Press Start."
      : `Seed ${s.seed} · ${s.speed}x · delay ${s.delay_s} s · ${
          s.tracking ? "tracking on (player dots)" : "tracking off (dots: last actions by team; tick Tracking and Start for players)"
        }`;
  if (!state.agentsOk) $("status").textContent += ` · agent service not reachable at ${AGENTS}`;
}

function renderMoments() {
  const now = clock.now(performance.now()) ?? -1;
  const groups = new Set(state.settings.groups);
  const list = [...state.moments.values()]
    .filter((m) => m.insight.video_ts_ms <= now) // never ahead of the viewer
    .filter((m) => groups.has(groupOf(m.insight.kind)))
    .sort((a, b) => b.insight.video_ts_ms - a.insight.video_ts_ms);
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
      const meta = document.createElement("span");
      meta.className = "meta";
      const kind = KIND.en[m.insight.kind] ?? m.insight.kind;
      if (!m.overlay) {
        li.classList.add("pending");
        head.textContent = BADGES.en.pending;
        meta.textContent = kind;
        li.append(min, head, meta);
        return li;
      }
      head.textContent = m.overlay.content.headline;
      head.lang = m.overlay.lang;
      const how = m.retracted
        ? "withdrawn"
        : m.overlay.template_fallback
          ? "template"
          : m.source === "template"
            ? "quick line, model line coming"
            : "verified";
      const late = !m.retracted && queue.missedInsight(m.insight.insight_id) ? " · not shown live, click to replay" : "";
      meta.textContent = `${kind} · ${how}${late}`;
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
  else if (pendingMoment(ts)) renderPending(pendingMoment(ts));
  else {
    $("lower-third").hidden = true;
    $("lower-third").dataset.key = "";
  }
  renderCorner(queue.showing("corner"));
  renderShape(queue.showing("pitch"));
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

/** The core's settings (public, no sign-in needed); the defaults stay if it does not answer. */
async function loadView() {
  try {
    const res = await fetch(`${CORE}/api/config`);
    if (res.ok) view = viewSettings(await res.json());
  } catch {
    /* keep what we have */
  }
  queue.minShowMs = view.cardMinShowMs;
}

function toast(message, kind = "") {
  const t = document.createElement("div");
  t.className = `toast ${kind}`;
  t.textContent = message;
  $("toasts").append(t);
  setTimeout(() => t.remove(), 6000);
}

/** Disables the button and shows a spinner on it until the action settles. */
async function busy(button, action) {
  if (button.classList.contains("busy")) return;
  button.classList.add("busy");
  button.disabled = true;
  try {
    await action();
  } finally {
    button.classList.remove("busy");
    button.disabled = false;
    if (state.playback) renderStatus(state.playback); // Pause is disabled again when idle
  }
}

function bindControls() {
  $("speed").addEventListener("input", () => {
    const speed = Number($("speed").value);
    if (speed > 0) $("delay").value = suggestDelay(speed, view);
  });
  $("start-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const body = {
      seed: Number($("seed").value),
      speed: Number($("speed").value),
      delay_s: Number($("delay").value),
      tracking: $("tracking").checked,
    };
    busy($("start"), async () => {
      try {
        await core("/api/match/start", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        state.fingerprint = null; // reconnect on the next poll
        await pollState();
        toast(`Match started: seed ${body.seed} at ${body.speed}x`, "ok");
      } catch (err) {
        toast(`Could not start: ${err.message}`, "bad");
      }
    });
  });
  $("pause").addEventListener("click", () => {
    const action = state.playback?.status === "paused" ? "resume" : "pause";
    busy($("pause"), async () => {
      try {
        await core(`/api/match/${action}`, { method: "POST" });
        await pollState();
      } catch (err) {
        toast(`Could not ${action}: ${err.message}`, "bad");
      }
    });
  });
  $("retry").addEventListener("click", () => busy($("retry"), pollState));
}

// ---------------------------------------------------------------- start

try {
  await auth.init();
} catch (e) {
  toast(`Sign-in could not load: ${e.message}`, "bad");
}
await loadView();
// The Start form offers the configured match; a first-time viewer gets the default view.
$("seed").value = view.seed;
$("speed").value = view.speed;
$("tracking").checked = view.tracking;
$("delay").value = suggestDelay(view.speed, view);
state.settings.persona ||= view.defaultPersona;
state.settings.lang ||= view.defaultLang;
renderAccount();
bindSettings();
bindFilters();
bindControls();
$("signin").addEventListener("click", () => auth.signIn());
setInterval(loadView, 60_000);
if (auth.enabled && !auth.account && view.signInRequired) {
  $("signin-gate").hidden = false; // everything behind the gate needs a signed-in user
  $("status").textContent = "Sign in to watch.";
} else {
  await loadPreferences();
  await checkAgents();
  await pollState();
  setInterval(pollState, 1000);
  setInterval(checkAgents, 10_000);
  setInterval(refreshMomentum, 3000);
  setInterval(refreshPlayerPanel, 5000);
  setInterval(renderMoments, 2000);
}
(function loop() {
  frame();
  requestAnimationFrame(loop);
})();
// Browsers pause requestAnimationFrame in hidden or occluded windows, sometimes without setting
// document.hidden; a timer keeps the timeline moving regardless (frame() is idempotent).
setInterval(frame, 100);
