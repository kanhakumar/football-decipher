import assert from "node:assert/strict";
import { test } from "node:test";
import { SseParser } from "../js/sse.js";

test("events are parsed across chunk boundaries, with types, ids and multi-line data", () => {
  const p = new SseParser();
  assert.deepEqual(p.push("event: insight\nid: 3\ndata: {\"a\":"), []);
  assert.deepEqual(p.push("1}\n\n: keep-alive\n\ndata: one\ndata: two\n\n"), [
    { type: "insight", id: "3", data: "{\"a\":1}" },
    { type: "message", id: null, data: "one\ntwo" },
  ]);
});

test("CRLF line endings and fields without a space after the colon", () => {
  const p = new SseParser();
  assert.deepEqual(p.push("event:overlay\r\ndata:{}\r\n\r\n"), [{ type: "overlay", id: null, data: "{}" }]);
});
