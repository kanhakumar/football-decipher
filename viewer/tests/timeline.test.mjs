import assert from "node:assert/strict";
import { test } from "node:test";
import { OverlayQueue, ViewerClock, MAX_EXTRAPOLATION_MS } from "../js/timeline.js";

const card = (id, start, extra = {}) => ({
  overlay_id: id, match_id: "SYN-154", insight_id: `I_${id.slice(-4)}`, op: "show",
  type: "insight_card", style: "lower_third", t_start_ms: start, duration_ms: 8000, ...extra,
});

test("the clock follows envelopes and extrapolates at playback speed, capped", () => {
  const c = new ViewerClock();
  assert.equal(c.now(0), null);
  c.observe(10_000, 1000);
  c.setPlayback(20, true);
  assert.equal(c.now(1100), 12_000); // 100 ms of wall time at 20x
  assert.equal(c.now(60_000), 10_000 + MAX_EXTRAPOLATION_MS);
  c.observe(9_000, 1200); // an older envelope never moves the clock back
  assert.equal(c.now(1100), 12_000);
  c.setPlayback(20, false);
  assert.equal(c.now(5000), 10_000, "paused: no extrapolation");
});

test("an overlay shows when the clock reaches t_start_ms, and one card per slot", () => {
  const q = new OverlayQueue({ minShowMs: 0 });
  q.put(card("O_00000001", 5000));
  q.put(card("O_00000002", 6000));
  q.put({ ...card("O_90000001", 5500), type: "scoreline_update", style: "corner_card" });
  assert.deepEqual(q.tick(4999, 0), []);
  const shown = q.tick(5600, 0).map((c) => [c.change, c.item.overlay.overlay_id]);
  assert.deepEqual(shown, [["shown", "O_00000001"], ["shown", "O_90000001"]]);
  assert.equal(q.showing("lower").lateMs, 600);
  assert.deepEqual(q.tick(7000, 0), [], "the second card waits for the slot");
  const next = q.tick(13_600, 0).map((c) => [c.change, c.item.overlay.overlay_id]);
  assert.deepEqual(next, [["done", "O_00000001"], ["shown", "O_00000002"], ["done", "O_90000001"]]);
});

test("cards stay readable at fast playback, and stale cards are skipped", () => {
  const q = new OverlayQueue({ minShowMs: 4000 });
  q.put(card("O_00000001", 0));
  q.put(card("O_00000002", 1000));
  q.tick(0, 0);
  assert.deepEqual(q.tick(20_000, 1000), [], "over in video time, but only 1 s on screen");
  const changes = q.tick(20_000, 4000).map((c) => c.change);
  assert.deepEqual(changes, ["done", "missed"], "the second one's moment has passed");
});

test("retraction takes a card off screen or out of the queue", () => {
  const q = new OverlayQueue({ minShowMs: 0 });
  q.put(card("O_00000001", 0));
  q.put(card("O_00000002", 50_000));
  q.tick(100, 0);
  const pulled = q.put({ overlay_id: "O_00000001", match_id: "SYN-154", op: "retract" });
  assert.equal(pulled.state, "retracted");
  assert.equal(pulled.wasShowing, true);
  assert.equal(q.showing("lower"), null);
  assert.equal(q.retractInsight("I_0002").length, 1);
  assert.deepEqual(q.tick(60_000, 0), [], "a retracted card never shows");
});

test("another persona or language replaces the same moment in place", () => {
  const q = new OverlayQueue({ minShowMs: 0 });
  q.put(card("O_00000001", 0, { insight_id: "I_0020", lang: "en" }));
  q.tick(100, 0);
  q.replaceForInsight(card("O_00000007", 0, { insight_id: "I_0020", lang: "hi" }));
  const now = q.showing("lower");
  assert.equal(now.overlay.overlay_id, "O_00000007");
  assert.equal(now.overlay.lang, "hi");
  assert.equal(q.items.size, 1);
});
