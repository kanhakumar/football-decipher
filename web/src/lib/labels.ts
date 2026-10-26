// Words on screen, per language where the card is in the viewer's language.

import type { Group } from "./match";
import type { Lang } from "./types";

export const KIND: Record<Lang, Record<string, string>> = {
  en: {
    goal: "Goal", red_card: "Red card", key_chance: "Big chance", momentum_swing: "Momentum",
    pressure_shift: "Pressing", control_vs_chaos: "Control", rhythm_change: "Tempo",
    milestone: "Milestone", player_spotlight: "Key pass", tactical_shift: "Tactics", full_time: "Full time",
  },
  es: {
    goal: "Gol", red_card: "Tarjeta roja", key_chance: "Ocasión", momentum_swing: "Impulso",
    pressure_shift: "Presión", control_vs_chaos: "Control", rhythm_change: "Ritmo",
    milestone: "Hito", player_spotlight: "Pase clave", tactical_shift: "Táctica", full_time: "Final",
  },
  hi: {
    goal: "गोल", red_card: "लाल कार्ड", key_chance: "बड़ा मौका", momentum_swing: "रुख",
    pressure_shift: "दबाव", control_vs_chaos: "नियंत्रण", rhythm_change: "रफ़्तार",
    milestone: "उपलब्धि", player_spotlight: "अहम पास", tactical_shift: "रणनीति", full_time: "फुल टाइम",
  },
};

export const BADGES: Record<Lang, { verified: string; template: string; quick: string; pending: string; basis: Record<string, string> }> = {
  en: { verified: "Verified", template: "Template line", quick: "Quick line", pending: "Analysing this moment…",
    basis: { event_data: "Event data", tracking: "Tracking", event_estimate: "Estimate" } },
  es: { verified: "Verificado", template: "Plantilla", quick: "Línea rápida", pending: "Analizando la jugada…",
    basis: { event_data: "Datos de eventos", tracking: "Seguimiento", event_estimate: "Estimación" } },
  hi: { verified: "सत्यापित", template: "टेम्पलेट", quick: "त्वरित पंक्ति", pending: "इस पल का विश्लेषण…",
    basis: { event_data: "इवेंट डेटा", tracking: "ट्रैकिंग", event_estimate: "अनुमान" } },
};

export const GROUP_LABELS: Record<Group, string> = {
  chances: "Goals and chances", flow: "Pressing and momentum", tactics: "Tactics", players: "Players",
};

export const STAT_LABELS: Record<string, string> = {
  passes: "Passes", passes_completed: "Passes completed", shots: "Shots", xg: "xG",
  progressive_passes: "Progressive passes", pressures: "Pressures", tackles_won: "Tackles won",
  interceptions: "Interceptions", distance_covered: "Distance", high_speed_distance: "High-speed distance",
  top_speed: "Top speed", sprints: "Sprints", fatigue_index: "Fatigue index",
};

export const BASIS_SHORT: Record<string, string> = { event_data: "events", tracking: "tracking", event_estimate: "estimate" };

export const PERSONAS = [
  ["casual", "Casual fan"], ["analyst", "Analyst"], ["club_fan", "Club fan"],
  ["player_focus", "Player focus"], ["metric_focus", "Metric focus"],
] as const;

export const LANGS = [["en", "English"], ["hi", "हिन्दी (Hindi)"], ["es", "Español"]] as const;

export const METRICS = [
  ["", "None"], ["xg", "xG"], ["shots", "Shots"], ["possession_share", "Possession"],
  ["pass_completion", "Pass completion"], ["ppda", "PPDA (pressing)"],
] as const;

export function prettyType(t: string): string {
  return t.replaceAll("_", " ").replace(/^./, (c) => c.toUpperCase());
}

/** A stat's value and unit for the panel, as the core reported it (converted, never derived). */
export function statText(m: { metric: string; unit: string; value: number }): string {
  if (m.metric === "top_speed" && m.unit === "m/s") return `${(m.value * 3.6).toFixed(1)} km/h`;
  if (m.unit === "m") return `${Math.round(m.value).toLocaleString()} m`;
  if (m.unit === "%") return `${m.value.toFixed(1)}%`;
  if (m.unit === "events") return `${m.value.toLocaleString()} events`;
  return Number.isInteger(m.value) ? String(m.value) : m.value.toFixed(2);
}
