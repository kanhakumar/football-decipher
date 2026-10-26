// What each persona sees (ADR-021). A casual fan follows the story; an analyst gets every
// moment; the others follow their club, player or metric, plus the moments everyone needs (goals
// and red cards). The importance tiers still apply inside what a persona sees. Pure.

import type { Insight, Persona } from "./types";

const STORY = new Set(["goal", "red_card", "key_chance", "player_spotlight", "momentum_swing", "full_time"]);
// Goals, red cards and the full-time recap (ADR-023) reach every persona.
const EVERYONE = new Set(["goal", "red_card", "full_time"]);

/** Which facts make a moment about a metric (metric focus). */
const METRIC_FACTS: Record<string, (key: string) => boolean> = {
  xg: (k) => k === "xg" || k.endsWith("_xg"),
  shots: (k) => k.endsWith("shots"),
  possession_share: (k) => k.endsWith("share"),
  pass_completion: (k) => k.includes("pass"),
  ppda: (k) => k.endsWith("_rate") || k.includes("pressures"),
};

export interface Focus {
  team: string;
  player: string;
  metric: string;
}

export function visibleTo(persona: Persona, focus: Focus, insight: Insight): boolean {
  switch (persona) {
    case "analyst":
      return true;
    case "club_fan":
      if (!focus.team) return STORY.has(insight.kind);
      return insight.teams[0] === focus.team || EVERYONE.has(insight.kind);
    case "player_focus":
      if (!focus.player) return STORY.has(insight.kind);
      return (insight.players ?? []).includes(focus.player) || EVERYONE.has(insight.kind);
    case "metric_focus": {
      const about = METRIC_FACTS[focus.metric];
      if (!about) return STORY.has(insight.kind);
      return EVERYONE.has(insight.kind) || Object.keys(insight.facts).some(about);
    }
    default:
      return STORY.has(insight.kind);
  }
}

/** Shape maps are tactical detail: for the analyst and the metric focus. */
export function showsShapeMaps(persona: Persona): boolean {
  return persona === "analyst" || persona === "metric_focus";
}
