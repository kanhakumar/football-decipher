import assert from "node:assert/strict";
import { test } from "node:test";
import { InsightBook, displayClock, groupOf, scoreAt, shapeDots, tierOf, toScreen } from "../js/match.js";

test("away coordinates are turned into the home team's frame", () => {
  assert.deepEqual(toScreen("T_HOM", "T_HOM", 94.9, 38.3), { x: 94.9, y: 38.3 });
  const away = toScreen("T_AWY", "T_HOM", 94.9, 38.3);
  assert.deepEqual([away.x.toFixed(1), away.y.toFixed(1)], ["10.1", "29.7"]);
});

test("the score comes from the latest goal insight the viewer has reached", () => {
  const goal = (id, ts, teams, tg, og, status) => ({
    insight_id: id, kind: "goal", video_ts_ms: ts, teams, facts: { team_goals: tg, opponent_goals: og }, status,
  });
  const goals = [
    goal("I_0001", 700_000, ["T_AWY", "T_HOM"], 1, 0),
    goal("I_0006", 1_800_000, ["T_HOM", "T_AWY"], 1, 1),
    goal("I_0008", 1_947_500, ["T_AWY", "T_HOM"], 2, 1),
  ];
  const teams = ["T_HOM", "T_AWY"];
  assert.deepEqual(scoreAt(goals, 0, teams), { T_HOM: 0, T_AWY: 0 });
  assert.deepEqual(scoreAt(goals, 1_900_000, teams), { T_HOM: 1, T_AWY: 1 });
  assert.deepEqual(scoreAt(goals, 2_000_000, teams), { T_HOM: 1, T_AWY: 2 });
  goals[2].status = "retracted"; // the goal is ruled out
  assert.deepEqual(scoreAt(goals, 2_000_000, teams), { T_HOM: 1, T_AWY: 1 });
});

test("display clock", () => {
  assert.equal(displayClock(1, 1937.5), "32:17");
  assert.equal(displayClock(1, 2760), "45+2'");
  assert.equal(displayClock(2, 1800), "75:00");
});

test("insight book: retractions and replacements withdraw the old version", () => {
  const book = new InsightBook();
  assert.deepEqual(book.add({ insight_id: "I_0001", kind: "goal" }), []);
  assert.deepEqual(book.add({ insight_id: "I_0002", kind: "goal", supersedes: "I_0001" }), ["I_0001"]);
  assert.deepEqual(book.add({ insight_id: "I_0002", kind: "goal", status: "retracted" }), ["I_0002"]);
  assert.deepEqual(book.active(), []);
});

test("moment tiers: model from 0.5, template line from 0.35, ticker below", () => {
  assert.equal(tierOf(0.93), "model");
  assert.equal(tierOf(0.5), "model");
  assert.equal(tierOf(0.45), "template");
  assert.equal(tierOf(0.3), "ticker");
  assert.equal(groupOf("tactical_shift"), "tactics");
  assert.equal(groupOf("player_spotlight"), "players");
  assert.equal(groupOf("goal"), "chances");
});

test("a shape map is drawn in the home team's frame", () => {
  const overlay = { content: { team_id: "T_AWY", positions: [{ player_id: "P_AWY_09", x: 80, y: 10 }] } };
  const [dot] = shapeDots(overlay, "T_HOM", { P_AWY_09: { shirt: 9 } });
  assert.deepEqual(dot, { x: 25, y: 58, shirt: 9, home: false });
});
