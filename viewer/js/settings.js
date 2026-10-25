// Settings the viewer follows (ADR-019), from the core's GET /api/config: config/app.yaml with any
// Azure App Configuration values over it. Pure: the fetch happens in app.js. The defaults match
// config/app.yaml, for a core that does not answer.

export const DEFAULTS = {
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

const KEYS = {
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
export function viewSettings(answer) {
  const s = answer?.settings ?? {};
  return Object.fromEntries(
    Object.entries(KEYS).map(([name, key]) => {
      const value = s[key];
      return [name, typeof value === typeof DEFAULTS[name] ? value : DEFAULTS[name]];
    }),
  );
}
