// Match facts the viewer shows, taken from what the core published. Pure: no DOM, no network.

export const PITCH = { length: 105, width: 68 };

/** Event coordinates are team-relative (each team attacks toward x = 105); the screen uses the
 * home team's frame, like the tracking frames (ADR-012). */
export function toScreen(teamId, homeId, x, y) {
  return teamId === homeId ? { x, y } : { x: PITCH.length - x, y: PITCH.width - y };
}

/** Moment tiers (ADR-017): the model narrates the big and the interpretive moments, the template
 * line alone covers the routine ones as a corner card, and the rest only reach the ticker. */
export const TIERS = { model: 0.5, template: 0.35 };

export function tierOf(importance) {
  if (importance >= TIERS.model) return "model";
  if (importance >= TIERS.template) return "template";
  return "ticker";
}

/** Filter groups for the Moments list. */
export const GROUPS = {
  chances: ["goal", "red_card", "key_chance"],
  flow: ["pressure_shift", "momentum_swing", "control_vs_chaos", "rhythm_change"],
  tactics: ["tactical_shift"],
  players: ["player_spotlight", "milestone"],
};

export function groupOf(kind) {
  return Object.keys(GROUPS).find((g) => GROUPS[g].includes(kind)) ?? "flow";
}

/** A shape map's average positions on screen (the home team's frame), with shirt numbers. */
export function shapeDots(overlay, homeId, players) {
  const team = overlay.content.team_id;
  return (overlay.content.positions ?? []).map((p) => ({
    ...toScreen(team, homeId, p.x, p.y),
    shirt: players[p.player_id]?.shirt ?? "",
    home: team === homeId,
  }));
}

/** The score at video time `ts`: from the latest active goal insight at or before it. */
export function scoreAt(goals, ts, teamIds) {
  const score = Object.fromEntries(teamIds.map((t) => [t, 0]));
  let latest = null;
  for (const g of goals) {
    if (g.status === "retracted" || g.video_ts_ms > ts) continue;
    if (!latest || g.video_ts_ms > latest.video_ts_ms) latest = g;
  }
  if (latest) {
    const [team, other] = latest.teams;
    score[team] = latest.facts.team_goals;
    if (other) score[other] = latest.facts.opponent_goals;
  }
  return score;
}

/** "45+2'" style display minute from an event's period and period clock. */
export function displayClock(period, periodClockS) {
  const base = { 1: 0, 2: 45, 3: 90, 4: 105, 5: 120 }[period] ?? 0;
  const length = { 1: 45, 2: 45, 3: 15, 4: 15, 5: 0 }[period] ?? 45;
  const whole = Math.floor(periodClockS);
  const minute = Math.floor(whole / 60);
  const second = String(whole % 60).padStart(2, "0");
  if (length && minute >= length) return `${base + length}+${minute - length + 1}'`;
  return `${base + minute}:${second}`;
}

/** Keeps the newest version of every insight; supersedes and retractions withdraw old ones. */
export class InsightBook {
  constructor() {
    this.byId = new Map();
  }

  /** Records an insight version; returns the ids whose overlays should be withdrawn. */
  add(insight) {
    const withdrawn = [];
    if (insight.supersedes) {
      const old = this.byId.get(insight.supersedes);
      if (old) {
        this.byId.set(old.insight_id, { ...old, status: "retracted" });
        withdrawn.push(old.insight_id);
      }
    }
    this.byId.set(insight.insight_id, insight);
    if (insight.status === "retracted") withdrawn.push(insight.insight_id);
    return withdrawn;
  }

  active() {
    return [...this.byId.values()].filter((i) => i.status !== "retracted");
  }

  goals() {
    return [...this.byId.values()].filter((i) => i.kind === "goal");
  }

  clear() {
    this.byId.clear();
  }
}
