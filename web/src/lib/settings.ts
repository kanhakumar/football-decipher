// Settings the viewer follows (ADR-019), from the core's GET /api/config: config/app.yaml with any
// Azure App Configuration values over it. Pure: the fetch happens in the controller. The defaults
// match config/app.yaml, for a core that does not answer.

import type { Lang, Persona } from "./types";

export interface ViewSettings {
  seed: number;
  speed: number;
  tracking: boolean;
  narrationBudgetS: number;
  minDelayS: number;
  maxDelayS: number;
  modelMin: number;
  templateMin: number;
  cardMinShowMs: number;
  modelSlots: number;
  defaultPersona: Persona;
  defaultLang: Lang;
  signInRequired: boolean;
}

export const DEFAULTS: ViewSettings = {
  seed: 154,
  speed: 10,
  tracking: false,
  narrationBudgetS: 12,
  minDelayS: 30,
  maxDelayS: 600,
  modelMin: 0.5,
  templateMin: 0.35,
  cardMinShowMs: 4500,
  modelSlots: 2,
  defaultPersona: "casual",
  defaultLang: "en",
  signInRequired: true,
};

const KEYS: Record<keyof ViewSettings, string> = {
  seed: "match:seed",
  speed: "match:speed",
  tracking: "match:tracking",
  narrationBudgetS: "match:narration_budget_s",
  minDelayS: "match:min_delay_s",
  maxDelayS: "match:max_delay_s",
  modelMin: "moments:model_min_importance",
  templateMin: "moments:template_min_importance",
  cardMinShowMs: "moments:card_min_show_ms",
  modelSlots: "moments:model_slots",
  defaultPersona: "narration:default_persona",
  defaultLang: "narration:default_lang",
  signInRequired: "access:sign_in_required",
};

/** The viewer's settings from a /api/config answer; a missing or mistyped value keeps its default. */
export function viewSettings(answer: { settings?: Record<string, unknown> } | null): ViewSettings {
  const s = answer?.settings ?? {};
  const out = { ...DEFAULTS } as Record<string, unknown>;
  for (const [name, key] of Object.entries(KEYS)) {
    const value = s[key];
    if (typeof value === typeof DEFAULTS[name as keyof ViewSettings]) out[name] = value;
  }
  return out as unknown as ViewSettings;
}
