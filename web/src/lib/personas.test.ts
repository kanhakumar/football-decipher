import { assert, test } from "vitest";
import { showsShapeMaps, visibleTo, type Focus } from "./personas";
import type { Insight } from "./types";

const moment = (kind: string, over: Partial<Insight> = {}): Insight => ({
  insight_id: "I_1", match_id: "SYN-154", kind, period: 1, video_ts_ms: 0, teams: ["T_HOM", "T_AWY"],
  facts: {}, importance: 0.6, ...over,
});
const none: Focus = { team: "", player: "", metric: "" };

test("a casual fan follows the story, an analyst sees everything", () => {
  for (const kind of ["goal", "red_card", "key_chance", "player_spotlight", "momentum_swing"]) {
    assert.isTrue(visibleTo("casual", none, moment(kind)), kind);
  }
  for (const kind of ["tactical_shift", "pressure_shift", "rhythm_change", "control_vs_chaos", "milestone"]) {
    assert.isFalse(visibleTo("casual", none, moment(kind)), kind);
    assert.isTrue(visibleTo("analyst", none, moment(kind)), kind);
  }
  assert.isFalse(showsShapeMaps("casual"));
  assert.isTrue(showsShapeMaps("analyst"));
});

test("a club fan sees their club's moments and the opponent's goals and cards", () => {
  const fan: Focus = { ...none, team: "T_HOM" };
  assert.isTrue(visibleTo("club_fan", fan, moment("tactical_shift")), "their club's tactics");
  assert.isFalse(visibleTo("club_fan", fan, moment("tactical_shift", { teams: ["T_AWY", "T_HOM"] })), "the opponent's");
  assert.isTrue(visibleTo("club_fan", fan, moment("goal", { teams: ["T_AWY", "T_HOM"] })), "the opponent's goal");
  assert.isFalse(visibleTo("club_fan", none, moment("tactical_shift")), "no club chosen: as casual");
});

test("player and metric focus follow their choice, plus goals and cards", () => {
  const player: Focus = { ...none, player: "P_HOM_07" };
  assert.isTrue(visibleTo("player_focus", player, moment("milestone", { players: ["P_HOM_07"] })));
  assert.isFalse(visibleTo("player_focus", player, moment("milestone", { players: ["P_HOM_10"] })));
  assert.isTrue(visibleTo("player_focus", player, moment("red_card", { players: ["P_AWY_04"] })));
  const xg: Focus = { ...none, metric: "xg" };
  assert.isTrue(visibleTo("metric_focus", xg, moment("player_spotlight", { facts: { shot_xg: 0.3 } })));
  assert.isFalse(visibleTo("metric_focus", xg, moment("rhythm_change", { facts: { after_per_minute: 11 } })));
  const ppda: Focus = { ...none, metric: "ppda" };
  assert.isTrue(visibleTo("metric_focus", ppda, moment("pressure_shift", { facts: { after_rate: 0.4 } })));
});
