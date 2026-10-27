// @ts-nocheck -- ported from viewer/tests with partial fixtures; the behaviour pinned is unchanged
import { assert, test } from "vitest";
import { InsightBook, displayClock, groupOf, matchMinute, scoreAt, shapeDots, tierOf, toScreen, viewerWindows } from "./match";

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

test("stats windows end at the viewer's minute, and first-half stoppage stays in the first half", () => {
  assert.equal(matchMinute(2, 30), 45.5);
  assert.deepEqual(viewerWindows(1, 12.4), {
    lastFive: "period=1&from_minute=8&to_minute=13",
    soFar: "period=1&from_minute=0&to_minute=13",
  });
  // 45+2 in the first half: the second half's minutes 45-47 are not mixed in.
  assert.equal(viewerWindows(1, 46.2).soFar, "period=1&from_minute=0&to_minute=47");
  // Just after the restart: the last five minutes start at the second half's first minute.
  assert.deepEqual(viewerWindows(2, 45.5), {
    lastFive: "period=2&from_minute=45&to_minute=46",
    soFar: "from_minute=0&to_minute=46",
  });
});
