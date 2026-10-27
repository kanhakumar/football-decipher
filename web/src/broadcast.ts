// The broadcast controller: everything the viewer does that is not drawing. It plays the core's
// viewer-delayed feed, schedules overlays by video_ts_ms (D5, D8), asks the agent service for each
// moment in the viewer's persona and language (D3), and publishes a snapshot that React renders
// (useSyncExternalStore). It computes no match facts: every number on screen comes from the core
// or from a verified narration.

import type { Auth } from "./lib/auth";
import { BADGES, BASIS_SHORT, KIND, STAT_LABELS, prettyType, statText } from "./lib/labels";
import {
  GROUP_NAMES,
  InsightBook,
  displayClock,
  groupOf,
  momentMinute,
  scoreAt,
  shapeDots,
  tierOf,
  toScreen,
  type Group,
  type Tier,
} from "./lib/match";
import { showsShapeMaps, visibleTo } from "./lib/personas";
import { DEFAULTS, viewSettings, type ViewSettings } from "./lib/settings";
import { followStream } from "./lib/sse";
import { OverlayQueue, ViewerClock } from "./lib/timeline";
import type {
  Envelope,
  Insight,
  Lang,
  Lineup,
  MatchEvent,
  Metric,
  Narration,
  Overlay,
  Persona,
  Playback,
  ShownOverlay,
  TrackingFrame,
} from "./lib/types";

// ---------------------------------------------------------------- what React renders

export interface UserSettings {
  persona: Persona;
  lang: Lang;
  team: string;
  player: string;
  metric: string;
  groups: Group[];
}

/** The broadcast screen's fields, held still while a late joiner catches up (ADR-026). */
const FROZEN_WHILE_CATCHING_UP = [
  "clock", "score", "ball", "trail", "dots", "tag", "ticker", "card", "recap", "corner", "shape", "momentum", "prematch", "moments",
] as const;

function pick<T extends object, K extends keyof T>(o: T, keys: readonly K[]): Pick<T, K> {
  return Object.fromEntries(keys.map((k) => [k, o[k]])) as Pick<T, K>;
}

export interface CardView {
  key: string;
  overlayId: string;
  kicker: string;
  headline: string;
  body: string;
  metrics: Metric[];
  badges: { cls: string; text: string }[];
  away: boolean;
  lang: Lang;
  retracted: boolean;
  pending: boolean;
}

export interface CornerView {
  key: string;
  kicker: string;
  headline: string;
  body: string;
  lang: Lang;
}

export interface ShapeView {
  key: string;
  dots: { x: number; y: number; shirt: number | string; home: boolean }[];
  caption: string;
}

export interface MomentRow {
  id: string;
  minute: string;
  headline: string;
  meta: string;
  lang: Lang;
  pending: boolean;
  retracted: boolean;
}

export interface TeamView {
  id: string;
  name: string;
  short: string;
  color?: string;
  formation?: string;
  starters: { shirt: number; name: string; position?: string }[];
}

export interface Toast {
  id: number;
  text: string;
  kind: "" | "ok" | "warn" | "bad";
}

export interface Snapshot {
  ready: boolean;
  gate: boolean;
  account: { enabled: boolean; name: string | null };
  playback: Playback | null;
  coreOk: boolean;
  agentsOk: boolean;
  agentModel: string;
  clock: string;
  score: [number, number];
  home: TeamView | null;
  away: TeamView | null;
  ball: { x: number; y: number } | null;
  trail: { x: number; y: number; home: boolean }[];
  dots: { id: string; x: number; y: number; home: boolean; fav: boolean }[];
  tag: { x: number; y: number; label: string } | null;
  ticker: string;
  card: CardView | null;
  /** The full-time recap (ADR-023), in the centre of the pitch until the next match. */
  recap: CardView | null;
  corner: CornerView | null;
  shape: ShapeView | null;
  momentum: { home: number; label: string; figures: string } | null;
  prematch: { countdownS: number; delayS: number } | null;
  /** Seconds to kickoff while a Start from this viewer waits for the match to play. */
  startingS: number | null;
  /** Joining a match already under way: the history is being taken in, not played back (ADR-026). */
  catchingUp: boolean;
  moments: MomentRow[];
  latency: { delay: string; quick: string; model: string; lead: string; leadLate: boolean; onTime: string; onTimeOk: boolean | null };
  player: { title: string; rows: { label: string; value: string; basis: string }[]; note: string } | null;
  toasts: Toast[];
  settings: UserSettings;
  view: ViewSettings;
  teams: { id: string; name: string }[];
  squads: { team: string; players: { id: string; label: string }[] }[];
  busy: Record<"start" | "pause" | "retry", boolean>;
}

interface Moment {
  insight: Insight;
  tier: Tier;
  overlay?: ShownOverlay;
  narration?: Narration;
  source?: "template" | "model" | null;
  version?: number;
  retracted?: boolean;
  roundTripMs?: number;
  leadMs?: number | null;
}

export interface Endpoints {
  core: string;
  agents: string;
}

export interface Deps {
  auth: Auth;
  fetch?: typeof fetch;
  now?: () => number;
  storage?: Pick<Storage, "getItem" | "setItem"> | null;
}

const STORAGE_KEY = "decipher.settings";
const PREFERENCE_KEYS = ["persona", "lang", "team", "player", "metric", "groups"] as const;

export class Broadcast {
  private readonly fetchFn: typeof fetch;
  private readonly now: () => number;
  private readonly storage: Pick<Storage, "getItem" | "setItem"> | null;
  private readonly auth: Auth;

  readonly clock = new ViewerClock();
  readonly queue = new OverlayQueue({ minShowMs: DEFAULTS.cardMinShowMs });
  readonly book = new InsightBook();
  view: ViewSettings = DEFAULTS;
  settings: UserSettings;
  private settingsVersion = 0;

  private matchId: string | null = null;
  private homeId: string | null = null;
  private awayId: string | null = null;
  private teams: Record<string, Lineup> = {};
  private players: Record<string, { name: string; shirt: number; team: string }> = {};
  private moments = new Map<string, Moment>();
  private playback: Playback | null = null;
  private polledAt = 0;
  private fingerprint: string | null = null;
  private lastViewer = 0;
  private streams: AbortController[] = [];
  private trail: Snapshot["trail"] = [];
  private dots = new Map<string, Snapshot["dots"][number]>();
  private ball: Snapshot["ball"] = null;
  private tag: { x: number; y: number; until: number } | null = null;
  private ticker = "";
  private clockText = "00:00";
  private backlog = 0;
  private seen = 0;
  private correction: { overlay: ShownOverlay; until: number } | null = null;
  private replayed: { overlay: ShownOverlay; until: number } | null = null;
  /** Shape maps by insight, so a replayed tactics moment redraws its map. */
  private shapes = new Map<string, ShownOverlay>();
  private replayedShape: { overlay: ShownOverlay; until: number } | null = null;
  private lastShownLateMs: number | null = null;
  private lastNarration: Moment | null = null;
  private quickMs: number | null = null;
  private momentum: Snapshot["momentum"] = null;
  private player: Snapshot["player"] = null;
  private coreOk = true;
  private agentsOk = false;
  private agentModel = "";
  private toasts: Toast[] = [];
  private toastId = 0;
  private busy: Snapshot["busy"] = { start: false, pause: false, retry: false };
  private waiting = new Set<string>();
  private inFlight = 0;
  private preferenceTimer: ReturnType<typeof setTimeout> | null = null;
  private timers: ReturnType<typeof setInterval>[] = [];
  private ready = false;
  /** A Start from this viewer that has not reached kickoff yet, and when to give up on it. */
  private starting: { deadline: number } | null = null;
  private gate = false;

  private snapshot: Snapshot;
  private listeners = new Set<() => void>();

  constructor(
    readonly endpoints: Endpoints,
    deps: Deps,
  ) {
    this.auth = deps.auth;
    this.fetchFn = deps.fetch ?? ((...args) => fetch(...args));
    this.now = deps.now ?? (() => performance.now());
    this.storage = deps.storage === undefined ? safeStorage() : deps.storage;
    this.settings = this.loadSettings();
    this.snapshot = this.build();
  }

  // ---------------------------------------------------------------- React glue

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): Snapshot => this.snapshot;

  private emit(): void {
    const next = this.build();
    // While a late joiner takes in the match so far, the screen keeps what it showed: no fast-forward
    // through the history (ADR-026). The panels still update.
    this.snapshot = this.catchingUp
      ? { ...next, ...pick(this.snapshot, FROZEN_WHILE_CATCHING_UP), catchingUp: true }
      : next;
    for (const l of this.listeners) l();
  }

  /** True until the events stream has delivered what the viewer side held when this viewer joined. */
  private get catchingUp(): boolean {
    return this.seen < this.backlog;
  }

  // ---------------------------------------------------------------- lifecycle

  /** Signs in (if configured), loads the settings and starts following the core. */
  async start(): Promise<void> {
    try {
      await this.auth.init();
    } catch (e) {
      this.toast(`Sign-in could not load: ${message(e)}`, "bad");
    }
    await this.loadView();
    this.settings.persona ||= this.view.defaultPersona;
    this.settings.lang ||= this.view.defaultLang;
    this.timers.push(setInterval(() => void this.loadView(), 60_000));
    this.gate = this.auth.enabled && !this.auth.account && this.view.signInRequired;
    this.ready = true;
    if (!this.gate) {
      await this.loadPreferences();
      await this.checkAgents();
      await this.poll();
      this.timers.push(
        setInterval(() => void this.poll(), 1000),
        setInterval(() => void this.checkAgents(), 10_000),
        setInterval(() => void this.refreshMomentum(), 3000),
        setInterval(() => void this.refreshPlayer(), 5000),
      );
    }
    // Browsers pause requestAnimationFrame in hidden windows; a timer keeps the timeline moving.
    this.timers.push(setInterval(() => this.frame(), 100));
    this.emit();
  }

  stop(): void {
    for (const t of this.timers) clearInterval(t);
    this.timers = [];
    for (const s of this.streams) s.abort();
  }

  // ---------------------------------------------------------------- user actions

  /** Starts a match. The button stays busy until kickoff reaches the viewer (ADR-021): the delay
   * runs first, and the pre-match screen shows the countdown. */
  async startMatch(body: { seed: number; speed: number; delay_s: number; tracking: boolean }): Promise<void> {
    if (this.busy.start) return;
    this.busy = { ...this.busy, start: true };
    this.emit();
    try {
      await this.core("/api/match/start", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      this.fingerprint = null; // reconnect on the next poll
      await this.poll();
      const waitS = body.delay_s / body.speed + 30;
      this.starting = { deadline: this.now() + waitS * 1000 };
      this.toast(`Match started: seed ${body.seed} at ${body.speed}x`, "ok");
    } catch (e) {
      this.busy = { ...this.busy, start: false };
      this.toast(`Could not start: ${message(e)}`, "bad");
    }
    this.emit();
  }

  /** Ends the Start wait once kickoff plays, or gives up on it. */
  private settleStart(): void {
    if (!this.starting) return;
    const played = this.clock.ts !== null;
    const late = this.now() > this.starting.deadline;
    if (!played && !late) return;
    if (late && !played) this.toast("The match has not reached this screen yet; it may still be loading.", "warn");
    this.starting = null;
    this.busy = { ...this.busy, start: false };
  }

  async togglePause(): Promise<void> {
    const action = this.playback?.status === "paused" ? "resume" : "pause";
    await this.withBusy("pause", async () => {
      try {
        await this.core(`/api/match/${action}`, { method: "POST" });
        await this.poll();
      } catch (e) {
        this.toast(`Could not ${action}: ${message(e)}`, "bad");
      }
    });
  }

  retry = (): Promise<void> => this.withBusy("retry", () => this.poll());

  setSetting<K extends keyof UserSettings>(key: K, value: UserSettings[K]): void {
    this.settings = { ...this.settings, [key]: value };
    this.saveSettings();
    if (key !== "groups") {
      this.settingsVersion += 1;
      this.renarrateAll(); // what the persona sees and how it is said may both change
      void this.refreshPlayer();
    }
    this.emit();
  }

  toggleGroup(g: Group): void {
    const on = new Set(this.settings.groups);
    if (on.has(g)) on.delete(g);
    else on.add(g);
    this.setSetting("groups", GROUP_NAMES.filter((k) => on.has(k)));
  }

  /** Shows a moment's card again (from the Moments list), with its shape map if it has one. */
  replay(insightId: string): void {
    const m = this.moments.get(insightId);
    if (!m || m.retracted) return;
    const until = this.now() + 6000;
    if (m.overlay) this.replayed = { overlay: m.overlay, until };
    const shape = this.shapes.get(insightId);
    this.replayedShape = shape ? { overlay: shape, until } : null;
    this.emit();
  }

  signIn = (): Promise<void> => this.auth.signIn();
  signOut = (): Promise<void> => this.auth.signOut();

  dismissToast(id: number): void {
    this.toasts = this.toasts.filter((t) => t.id !== id);
    this.emit();
  }

  // ---------------------------------------------------------------- settings and preferences

  private loadSettings(): UserSettings {
    // A first-time viewer's persona and language come from the core's settings once they load.
    const fallback: UserSettings = { persona: "" as Persona, lang: "" as Lang, team: "", player: "", metric: "", groups: [...GROUP_NAMES] };
    try {
      return { ...fallback, ...JSON.parse(this.storage?.getItem(STORAGE_KEY) ?? "{}") };
    } catch {
      return fallback;
    }
  }

  private saveSettings(): void {
    try {
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(this.settings));
    } catch {
      /* private mode: settings last for this page only */
    }
    this.savePreferences();
  }

  /** A signed-in viewer's settings follow them to any device (the agent service keeps them). */
  private async loadPreferences(): Promise<void> {
    if (!this.auth.account) return;
    try {
      const p = await this.agents("/api/me/preferences");
      if (!p.saved) return this.savePreferences(); // first visit: keep what this browser had
      const next = { ...this.settings };
      for (const k of PREFERENCE_KEYS) (next as Record<string, unknown>)[k] = p[k];
      this.settings = next;
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(this.settings));
      this.settingsVersion += 1;
    } catch (e) {
      this.toast(`Could not load your preferences: ${message(e)}`, "warn");
    }
  }

  private savePreferences(): void {
    if (!this.auth.account) return;
    if (this.preferenceTimer) clearTimeout(this.preferenceTimer);
    const body = Object.fromEntries(PREFERENCE_KEYS.map((k) => [k, this.settings[k]]));
    this.preferenceTimer = setTimeout(() => {
      this.agents("/api/me/preferences", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).catch((e) => this.toast(`Could not save your preferences: ${message(e)}`, "warn"));
    }, 500);
  }

  /** The core's settings (public, no sign-in needed); the defaults stay if it does not answer. */
  async loadView(): Promise<void> {
    try {
      const res = await this.fetchFn(`${this.endpoints.core}/api/config`);
      if (res.ok) this.view = viewSettings(await res.json());
    } catch {
      /* keep what we have */
    }
    this.queue.minShowMs = this.view.cardMinShowMs;
  }

  // ---------------------------------------------------------------- core and agents

  private async authFetch(url: string, init: RequestInit = {}): Promise<Response> {
    const headers = { ...(init.headers as Record<string, string> | undefined), ...(await this.auth.headers()) };
    return this.fetchFn(url, { ...init, headers });
  }

  private async json(url: string, init?: RequestInit): Promise<any> {
    const res = await this.authFetch(url, init);
    if (!res.ok) {
      const detail = await res.json().then((b: { error?: string }) => b.error).catch(() => null);
      throw new Error(detail ?? `${url}: ${res.status}`);
    }
    return res.json();
  }

  private core = (path: string, init?: RequestInit) => this.json(this.endpoints.core + path, init);
  private agents = (path: string, init?: RequestInit) => this.json(this.endpoints.agents + path, init);

  /** Follows one core stream with the user's token, resuming after the last event on a dropped
   * connection, until the match is over or the stream is aborted (a new run). */
  private openStream(path: string, types: string[], handler: (data: any) => void, onAny?: () => void): AbortController {
    const control = new AbortController();
    let lastId: string | null = null;
    void (async () => {
      while (!control.signal.aborted) {
        try {
          await followStream(this.endpoints.core + path, {
            signal: control.signal,
            fetch: this.fetchFn,
            headers: async () => ({ ...(await this.auth.headers()), ...(lastId === null ? {} : { "Last-Event-ID": lastId }) }),
            onEvent: (e) => {
              if (e.id !== null) lastId = e.id;
              if (types.includes(e.type)) handler(JSON.parse(e.data));
              onAny?.();
            },
          });
        } catch {
          if (control.signal.aborted) return;
        }
        // The core ends every stream when the match is over; do not keep reconnecting to it.
        if (!this.playback || ["finished", "idle"].includes(this.playback.status)) return;
        await new Promise((r) => setTimeout(r, 1000));
      }
    })();
    return control;
  }

  private connect(): void {
    for (const s of this.streams) s.abort();
    // What the viewer side already holds: joining late, it arrives as one burst to take in quietly.
    this.backlog = this.playback?.envelopes_viewer ?? 0;
    this.seen = 0;
    this.streams = [
      this.openStream(
        "/api/stream/events",
        ["match_meta", "lineup", "event", "tracking_frame"],
        (e) => this.onEnvelope(e),
        () => {
          this.seen += 1;
          if (this.seen === this.backlog) this.frame(); // caught up: show where the match is, at once
        },
      ),
      this.openStream("/api/stream/insights", ["insight"], (i) => this.onInsight(i)),
      this.openStream("/api/stream/overlays", ["overlay"], (o) => this.onCoreOverlay(o)),
    ];
  }

  private resetMatch(): void {
    this.clock.reset();
    this.queue.clear();
    this.book.clear();
    this.moments.clear();
    this.waiting.clear();
    this.matchId = null;
    this.trail = [];
    this.dots.clear();
    this.ball = null;
    this.ticker = "";
    this.clockText = "00:00";
    this.tag = this.correction = this.replayed = this.replayedShape = null;
    this.shapes.clear();
  }

  async poll(): Promise<void> {
    try {
      const s: Playback = await this.core("/api/match/state");
      this.playback = s;
      this.polledAt = this.now();
      this.coreOk = true;
      this.clock.setPlayback(s.speed, s.status === "running");
      const fingerprint = s.run_id ?? null; // a new run: started here, by someone else, or by the generator job
      const restarted = fingerprint !== this.fingerprint || s.envelopes_viewer < this.lastViewer;
      this.lastViewer = s.envelopes_viewer;
      if (restarted && fingerprint) {
        this.fingerprint = fingerprint;
        this.resetMatch();
        this.connect();
        void this.loadTeams();
      }
    } catch {
      this.coreOk = false;
    }
    this.emit();
  }

  /** Both teams and squads before kickoff, for the pre-match screen and the pickers. */
  private async loadTeams(): Promise<void> {
    try {
      const info: { teams: Lineup[] } = await this.core("/api/tools/match_info");
      for (const t of info.teams) this.addLineup({ ...t, bench: t.bench ?? [] });
      if (!this.homeId && info.teams.length === 2) [this.homeId, this.awayId] = info.teams.map((t) => t.team_id);
      this.emit();
    } catch {
      /* the lineups also arrive on the feed */
    }
  }

  private async checkAgents(): Promise<void> {
    try {
      const res = await this.fetchFn(`${this.endpoints.agents}/health`);
      const h = await res.json();
      const cameBack = !this.agentsOk;
      this.agentsOk = true;
      this.agentModel = h.model;
      if (cameBack) this.renarrateAll(); // moments that arrived while the service was down
    } catch {
      this.agentsOk = false;
    }
    this.emit();
  }

  // ---------------------------------------------------------------- feed (viewer side)

  onEnvelope(env: Envelope): void {
    const p = env.payload;
    if (!this.matchId) this.matchId = env.match_id;
    switch (env.payload_type) {
      case "match_meta":
        this.homeId = p.home_team_id as string;
        this.awayId = p.away_team_id as string;
        break;
      case "lineup":
        this.addLineup(p as unknown as Lineup);
        break;
      case "event": {
        const e = p as unknown as MatchEvent;
        this.clock.observe(e.video_ts_ms, this.now());
        this.clockText = displayClock(e.period, e.period_clock_s);
        this.showEvent(e);
        break;
      }
      case "tracking_frame": {
        const f = p as unknown as TrackingFrame;
        this.clock.observe(f.video_ts_ms, this.now());
        this.drawFrame(f);
        break;
      }
    }
  }

  private addLineup(l: Lineup): void {
    this.teams[l.team_id] = l;
    for (const p of [...l.starters, ...l.bench]) {
      this.players[p.player_id] = { name: p.name, shirt: p.shirt, team: l.team_id };
    }
  }

  private showEvent(e: MatchEvent): void {
    if (e.x === undefined || e.y === undefined) return;
    const { x, y } = toScreen(e.team_id, this.homeId, e.x, e.y);
    if (!this.dots.size) this.ball = { x, y };
    // Without tracking there are no player positions: the trail shows where the last actions
    // happened, in the colour of the team that made them.
    this.trail = [...this.trail.slice(-5), { x, y, home: e.team_id === this.homeId }];
    const who = e.player_id ? this.players[e.player_id]?.name : null;
    this.ticker = `${prettyType(e.type)}${who ? ` · ${who}` : ""}${e.outcome ? ` · ${e.outcome.replaceAll("_", " ")}` : ""}`;
    if (e.player_id && e.player_id === this.settings.player && !this.dots.size) {
      this.tag = { x, y, until: this.now() + 3500 };
    }
  }

  private drawFrame(f: TrackingFrame): void {
    this.trail = [];
    this.ball = { x: f.ball.x, y: f.ball.y };
    const seen = new Set<string>();
    for (const p of f.players) {
      seen.add(p.player_id);
      const fav = p.player_id === this.settings.player;
      this.dots.set(p.player_id, { id: p.player_id, x: p.x, y: p.y, home: p.team_id === this.homeId, fav });
      if (fav) this.tag = { x: p.x, y: p.y, until: this.now() + 1000 };
    }
    for (const id of [...this.dots.keys()]) if (!seen.has(id)) this.dots.delete(id);
  }

  // ---------------------------------------------------------------- insights (live side)

  onInsight(insight: Insight): void {
    for (const id of this.book.add(insight)) this.withdraw(id);
    const tier = tierOf(insight.importance, this.view);
    if (insight.status === "retracted" || tier === "ticker") return;
    this.moments.set(insight.insight_id, { insight, tier });
    if (this.visible(this.moments.get(insight.insight_id)!)) this.enqueueNarration(insight.insight_id);
  }

  /** Whether this viewer's persona follows the moment (ADR-021). */
  private visible(m: Moment): boolean {
    return visibleTo(this.settings.persona || this.view.defaultPersona, this.settings, m.insight);
  }

  private withdraw(insightId: string): void {
    const pulled = this.queue.retractInsight(insightId);
    const m = this.moments.get(insightId);
    if (m) m.retracted = true;
    const onScreen = pulled.find((i) => i.wasShowing);
    if (onScreen) this.correction = { overlay: onScreen.overlay, until: this.now() + 4000 };
  }

  // Every moment gets the template line at once (D4: a card is on the timeline before its
  // moment), then the model line, which replaces it in place. Model requests run
  // view.modelSlots at a time, the moment the viewer reaches first going first.
  private enqueueNarration(insightId: string): void {
    this.requestLine(insightId, "template").catch((e) => this.reportNarrationError(e));
    if (this.moments.get(insightId)?.tier !== "model") return; // routine moments: template only
    this.waiting.add(insightId);
    this.pumpModel();
  }

  private pumpModel(): void {
    while (this.inFlight < this.view.modelSlots && this.waiting.size) {
      const ts = (id: string) => this.moments.get(id)?.insight.video_ts_ms ?? 0;
      const next = [...this.waiting].sort((a, b) => ts(a) - ts(b))[0];
      this.waiting.delete(next);
      this.inFlight += 1;
      this.requestLine(next, "model")
        .catch((e) => this.reportNarrationError(e))
        .finally(() => {
          this.inFlight -= 1;
          this.pumpModel();
        });
    }
  }

  private reportNarrationError(e: unknown): void {
    if (!message(e).includes(": 409")) this.toast(`Narration failed: ${message(e)}`, "warn"); // 409: an old match
  }

  private async requestLine(insightId: string, mode: "template" | "model"): Promise<void> {
    const m = this.moments.get(insightId);
    if (!m || m.retracted || !this.agentsOk || !this.visible(m)) return;
    const s = this.settings;
    const version = this.settingsVersion;
    const started = this.now();
    const res = await this.authFetch(`${this.endpoints.agents}/api/narrate`, {
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
    const data: { overlay: ShownOverlay; narration: Narration } = await res.json();
    if (version !== this.settingsVersion || m.retracted) return; // settings changed meanwhile
    if (mode === "template" && m.source === "model" && m.version === version) return; // model was faster
    const roundTripMs = this.now() - started;
    if (mode === "template") {
      this.quickMs = roundTripMs;
    } else {
      const now = this.clock.now(this.now());
      Object.assign(m, { roundTripMs, leadMs: now === null ? null : data.overlay.t_start_ms - now });
      this.lastNarration = m;
    }
    // Routine moments go to the corner card, so the lower third stays for the big ones.
    const shown = m.tier === "template" ? { ...data.overlay, style: "corner_card" } : data.overlay;
    Object.assign(m, { overlay: shown, narration: data.narration, source: mode, version });
    this.queue.replaceForInsight(shown);
    this.emit();
  }

  private renarrateAll(): void {
    for (const m of this.moments.values()) {
      m.source = null;
      if (this.visible(m)) {
        this.enqueueNarration(m.insight.insight_id);
      } else if (m.overlay) {
        // No longer this persona's moment: off the timeline, quietly (not a correction).
        for (const item of this.queue.retractInsight(m.insight.insight_id)) item.wasShowing = false;
        m.overlay = undefined;
      }
    }
  }

  onCoreOverlay(o: Overlay): void {
    // The core's own overlays (scoreline updates, shape maps) and retractions. Narrated cards come
    // from the agent service in this viewer's settings.
    if (o.op === "retract") {
      const item = this.queue.retract(o.overlay_id);
      if (item?.wasShowing) this.correction = { overlay: item.overlay, until: this.now() + 4000 };
      for (const [id, shape] of this.shapes) if (shape.overlay_id === o.overlay_id) this.shapes.delete(id);
      return;
    }
    if (o.type === "shape_map" && o.insight_id && o.content) this.shapes.set(o.insight_id, o as ShownOverlay);
    if (o.type === "scoreline_update" || o.type === "shape_map") this.queue.put(o);
  }

  // ---------------------------------------------------------------- momentum and player panel

  private async refreshMomentum(): Promise<void> {
    if (!this.homeId || !this.playback || this.playback.status === "idle") return;
    try {
      // From the live engine, which runs the viewer delay ahead; the bar is a smoothed 5-minute view.
      const s = await this.core(`/api/tools/team_stats?team=${this.homeId}&metrics=possession_share&last_minutes=5`);
      const m = s.metrics[0];
      if (m.value === undefined) return;
      const home = Math.round(m.value);
      const short = (id: string | null) => (id ? this.teams[id]?.short ?? "" : "");
      this.momentum = {
        home,
        label: `Possession · last 5 min · ${BASIS_SHORT[m.basis] ?? m.basis}`,
        figures: `${short(this.homeId)} ${home}% | ${100 - home}% ${short(this.awayId)}`,
      };
    } catch {
      /* next poll */
    }
  }

  private async refreshPlayer(): Promise<void> {
    const id = this.settings.player;
    const p = this.players[id];
    if (!id || !p || !this.playback || this.playback.status === "idle") {
      this.player = null;
      return;
    }
    const title = `Player focus · ${p.shirt} ${p.name}`;
    try {
      const [stats, workload] = await Promise.all([
        this.core(`/api/tools/player_stats?player=${id}`),
        this.core(`/api/tools/player_workload?player=${id}`),
      ]);
      const rows = [...stats.metrics, ...workload.metrics]
        .filter((m: { value?: number }) => m.value !== undefined)
        .map((m: { metric: string; unit: string; value: number; basis: string }) => ({
          label: STAT_LABELS[m.metric] ?? m.metric,
          value: statText(m),
          basis: BASIS_SHORT[m.basis] ?? m.basis,
        }));
      const distance = workload.metrics.find((m: { metric: string }) => m.metric === "distance_covered");
      const note =
        distance?.basis === "tracking"
          ? "Workload from 5 Hz tracking."
          : this.playback.tracking
            ? "Workload is an event count until tracking covers 90% of the window."
            : "No tracking in this match: workload is estimated from events or not available.";
      this.player = { title, rows, note };
    } catch {
      this.player = { title, rows: [], note: "Player stats unavailable right now." };
    }
  }

  // ---------------------------------------------------------------- the frame

  /** Advances the timeline to now and publishes what is on screen. Idempotent. */
  frame(): void {
    if (this.catchingUp) return; // cards whose time passed meanwhile are skipped on the next frame
    const ts = this.clock.now(this.now());
    if (ts !== null) {
      for (const c of this.queue.tick(ts, this.now())) {
        if (c.change === "shown" && c.item.slot === "lower") this.lastShownLateMs = c.item.lateMs;
      }
    }
    this.settleStart();
    this.emit();
  }

  private toast(text: string, kind: Toast["kind"] = ""): void {
    const id = ++this.toastId;
    this.toasts = [...this.toasts, { id, text, kind }];
    setTimeout(() => this.dismissToast(id), 6000);
    this.emit();
  }

  private async withBusy(which: keyof Snapshot["busy"], action: () => Promise<void>): Promise<void> {
    if (this.busy[which]) return;
    this.busy = { ...this.busy, [which]: true };
    this.emit();
    try {
      await action();
    } finally {
      this.busy = { ...this.busy, [which]: false };
      this.emit();
    }
  }

  // ---------------------------------------------------------------- the snapshot

  private build(): Snapshot {
    const wall = this.now();
    const ts = this.clock.now(wall);
    const score = this.homeId && this.awayId ? scoreAt(this.book.goals(), ts ?? 0, [this.homeId, this.awayId]) : {};
    return {
      ready: this.ready,
      gate: this.gate,
      account: { enabled: this.auth.enabled, name: this.auth.account ? (this.auth.account.name ?? this.auth.account.username) : null },
      playback: this.playback,
      coreOk: this.coreOk,
      agentsOk: this.agentsOk,
      agentModel: this.agentModel,
      clock: this.clockText,
      score: [score[this.homeId ?? ""] ?? 0, score[this.awayId ?? ""] ?? 0],
      home: this.teamView(this.homeId),
      away: this.teamView(this.awayId),
      ball: this.ball,
      trail: this.trail,
      dots: [...this.dots.values()],
      tag: this.tagView(wall),
      ticker: this.ticker,
      card: this.cardView(ts, wall),
      recap: this.recapView(),
      corner: this.cornerView(),
      shape: this.shapeView(),
      momentum: this.momentum,
      prematch: this.prematchView(wall),
      startingS: this.starting ? (this.prematchView(wall)?.countdownS ?? 0) : null,
      catchingUp: false,
      moments: this.momentRows(ts),
      latency: this.latencyView(),
      player: this.player,
      toasts: this.toasts,
      settings: this.settings,
      view: this.view,
      teams: Object.values(this.teams).map((l) => ({ id: l.team_id, name: l.name })),
      squads: Object.values(this.teams).map((l) => ({
        team: l.name,
        players: [...l.starters, ...l.bench].map((p) => ({ id: p.player_id, label: `${p.shirt} ${p.name}` })),
      })),
      busy: this.busy,
    };
  }

  private teamView(id: string | null): TeamView | null {
    const l = id ? this.teams[id] : undefined;
    if (!id || !l) return null;
    return {
      id,
      name: l.name,
      short: l.short,
      color: l.colors?.[0],
      formation: l.formation,
      starters: l.starters.map((p) => ({ shirt: p.shirt, name: p.name, position: p.position })),
    };
  }

  private tagView(wall: number): Snapshot["tag"] {
    const p = this.players[this.settings.player];
    if (!p || !this.tag || wall > this.tag.until) return null;
    return { x: this.tag.x, y: this.tag.y, label: `${p.shirt} ${p.name}` };
  }

  /** Before kickoff reaches the viewer: how long until it does (the viewer delay, in real time). */
  private prematchView(wall: number): Snapshot["prematch"] {
    const s = this.playback;
    if (!s || !["running", "paused"].includes(s.status) || this.clock.ts !== null) return null;
    const live = s.live?.match_clock_s ?? 0;
    const sincePoll = s.status === "running" ? ((wall - this.polledAt) / 1000) * s.speed : 0;
    return { countdownS: Math.max(0, (s.delay_s - live - sincePoll) / s.speed), delayS: s.delay_s };
  }

  private cardView(ts: number | null, wall: number): CardView | null {
    const correction = this.correction && wall < this.correction.until ? this.correction : null;
    const replay = this.replayed && wall < this.replayed.until ? this.replayed : null;
    const current = this.queue.showing("lower");
    if (correction) return this.card(correction.overlay, true);
    if (current) return this.card(current.overlay, false);
    if (replay) return this.card(this.moments.get(replay.overlay.insight_id ?? "")?.overlay ?? replay.overlay, false);
    const pending = this.pendingMoment(ts);
    return pending ? this.pendingCard(pending) : null;
  }

  private recapView(): CardView | null {
    const item = this.queue.showing("recap");
    return item ? this.card(item.overlay, false) : null;
  }

  private card(o: ShownOverlay, retracted: boolean): CardView {
    const m = this.moments.get(o.insight_id ?? "");
    const lang = o.lang ?? "en";
    const kind = m ? KIND[lang]?.[m.insight.kind] ?? m.insight.kind : "";
    const minute = m ? momentMinute(m.insight) : null;
    const words = BADGES[lang] ?? BADGES.en;
    const badges: CardView["badges"] = [];
    if (o.verified) badges.push({ cls: "ok", text: `✓ ${words.verified}` });
    if (o.template_fallback) badges.push({ cls: "warn", text: words.template });
    else if (m?.source === "template") badges.push({ cls: "quick", text: words.quick });
    if (o.basis) badges.push({ cls: "", text: words.basis[o.basis] ?? o.basis });
    return {
      // The model line reuses the quick line's overlay id: the key includes the text so it swaps in.
      key: `${o.overlay_id}|${o.content.headline}|${o.content.body ?? ""}|${retracted}`,
      overlayId: o.overlay_id,
      kicker: [kind, minute ? `${minute}'` : ""].filter(Boolean).join(" · "),
      headline: o.content.headline,
      body: o.content.body ?? "",
      metrics: o.content.metrics ?? [],
      badges,
      away: o.content.team_id === this.awayId,
      lang,
      retracted,
      pending: false,
    };
  }

  /** A moment the viewer has reached in the last 8 s of video whose line has not arrived. */
  private pendingMoment(ts: number | null): Moment | null {
    if (ts === null) return null;
    for (const m of this.moments.values()) {
      const t = m.insight.video_ts_ms;
      const lower = m.insight.kind !== "full_time"; // the recap waits in its own slot
      if (lower && m.tier === "model" && !m.overlay && !m.retracted && this.visible(m) && t <= ts && ts < t + 8000) return m;
    }
    return null;
  }

  private pendingCard(m: Moment): CardView {
    const lang = this.settings.lang || "en";
    const minute = momentMinute(m.insight);
    return {
      key: `pending|${m.insight.insight_id}|${lang}`,
      overlayId: "",
      kicker: [KIND[lang]?.[m.insight.kind] ?? m.insight.kind, minute ? `${minute}'` : ""].filter(Boolean).join(" · "),
      headline: (BADGES[lang] ?? BADGES.en).pending,
      body: "",
      metrics: [],
      badges: [],
      away: false,
      lang,
      retracted: false,
      pending: true,
    };
  }

  private cornerView(): CornerView | null {
    const item = this.queue.showing("corner");
    if (!item) return null;
    const o = item.overlay;
    const m = this.moments.get(o.insight_id ?? "");
    const key = `${o.overlay_id}|${o.content.headline}`;
    if (o.type === "scoreline_update" || !m) {
      return { key, kicker: "SCORE UPDATE", headline: o.content.headline, body: "", lang: "en" };
    }
    const minute = momentMinute(m.insight);
    const lang = o.lang ?? "en";
    return {
      key,
      kicker: [KIND[lang]?.[m.insight.kind] ?? m.insight.kind, minute ? `${minute}'` : ""].filter(Boolean).join(" · "),
      headline: o.content.headline,
      body: o.content.body ?? "",
      lang,
    };
  }

  private shapeView(): ShapeView | null {
    const replay = this.replayedShape && this.now() < this.replayedShape.until ? this.replayedShape.overlay : null;
    const o = this.queue.showing("pitch")?.overlay ?? replay;
    if (!o || !showsShapeMaps(this.settings.persona || this.view.defaultPersona)) return null;
    const formation = o.content.formation ? ` · lineup ${o.content.formation}` : "";
    return {
      key: o.overlay_id,
      dots: shapeDots(o, this.homeId, this.players),
      caption: `${o.content.headline}${formation} · players with actions in the spell, estimated from events`,
    };
  }

  private momentRows(ts: number | null): MomentRow[] {
    const now = ts ?? -1;
    const groups = new Set(this.settings.groups);
    return [...this.moments.values()]
      .filter((m) => m.insight.video_ts_ms <= now) // never ahead of the viewer
      .filter((m) => this.visible(m) && groups.has(groupOf(m.insight.kind)))
      .sort((a, b) => b.insight.video_ts_ms - a.insight.video_ts_ms)
      .map((m) => {
        const minute = momentMinute(m.insight);
        const kind = KIND.en[m.insight.kind] ?? m.insight.kind;
        const base = { id: m.insight.insight_id, minute: minute === null ? "" : `${minute}'`, retracted: !!m.retracted };
        if (!m.overlay) return { ...base, headline: BADGES.en.pending, meta: kind, lang: "en" as Lang, pending: true };
        const how = m.retracted
          ? "withdrawn"
          : m.overlay.template_fallback
            ? "template"
            : m.source === "template"
              ? "quick line, model line coming"
              : "verified";
        const late = !m.retracted && this.queue.missedInsight(m.insight.insight_id) ? " · not shown live, click to replay" : "";
        return { ...base, headline: m.overlay.content.headline, meta: `${kind} · ${how}${late}`, lang: m.overlay.lang ?? "en", pending: false };
      });
  }

  private latencyView(): Snapshot["latency"] {
    const s = this.playback;
    const real = s ? s.delay_s / s.speed : 0;
    const n = this.lastNarration;
    const late = this.lastShownLateMs !== null && s ? this.lastShownLateMs / s.speed : null; // video ms late, in wall time
    return {
      delay: s && s.status !== "idle" ? `${s.delay_s} s (${real < 10 ? real.toFixed(1) : Math.round(real)} s real)` : "–",
      quick: this.quickMs === null ? "–" : `${Math.round(this.quickMs)} ms`,
      model: n?.roundTripMs === undefined ? "–" : `${Math.round(n.roundTripMs)} ms`,
      lead: n?.leadMs == null ? "–" : `${(n.leadMs / 1000).toFixed(1)} s video`,
      leadLate: n?.leadMs != null && n.leadMs < 0,
      onTime: late === null ? "–" : late <= 250 ? `on time (+${Math.round(late)} ms)` : `${(late / 1000).toFixed(1)} s late`,
      onTimeOk: late === null ? null : late <= 250,
    };
  }
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function safeStorage(): Pick<Storage, "getItem" | "setItem"> | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}
