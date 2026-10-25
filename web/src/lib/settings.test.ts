// @ts-nocheck -- ported from viewer/tests with partial fixtures; the behaviour pinned is unchanged
import { assert, test } from "vitest";
import { tierOf } from "./match";
import { DEFAULTS, viewSettings } from "./settings";
import { suggestDelay } from "./timeline";

test("the viewer takes the core's settings, keeping defaults for missing or mistyped values", () => {
  assert.deepEqual(viewSettings(null), DEFAULTS);
  const v = viewSettings({
    settings: {
      "moments:model_min_importance": 0.6,
      "moments:model_slots": 3,
      "access:sign_in_required": false,
      "match:speed": "fast",
    },
  });
  assert.equal(v.modelMin, 0.6);
  assert.equal(v.modelSlots, 3);
  assert.equal(v.signInRequired, false);
  assert.equal(v.speed, DEFAULTS.speed);
});

test("tiers and the suggested delay follow the settings", () => {
  const v = viewSettings({ settings: { "moments:model_min_importance": 0.6, "match:narration_budget_s": 20 } });
  assert.equal(tierOf(0.55), "model");
  assert.equal(tierOf(0.55, v), "template");
  assert.equal(suggestDelay(10, v), 200);
  assert.equal(suggestDelay(10), 120);
});
