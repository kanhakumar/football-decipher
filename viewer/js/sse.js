// Server-sent events over fetch, so a stream can carry an Authorization header (ADR-018);
// EventSource cannot send headers. The parser is pure and tested; `followStream` does the I/O.

/**
 * Parses SSE text. Feed it chunks as they arrive; it returns the complete events so far
 * ({ type, data, id }) and keeps any partial event for the next chunk.
 */
export class SseParser {
  constructor() {
    this.buffer = "";
  }

  push(chunk) {
    this.buffer += chunk.replace(/\r\n?/g, "\n");
    const events = [];
    let end;
    while ((end = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      const event = { type: "message", data: [], id: null };
      for (const line of block.split("\n")) {
        if (!line || line.startsWith(":")) continue; // comments are keep-alives
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") event.type = value;
        else if (field === "data") event.data.push(value);
        else if (field === "id") event.id = value;
      }
      if (event.data.length) events.push({ ...event, data: event.data.join("\n") });
    }
    return events;
  }
}

/**
 * Follows an SSE stream until it ends or `signal` aborts. `headers()` is called once per connection
 * (a fresh token). Calls `onEvent(type, data, id)` per event; resolves when the server closes the stream.
 */
export async function followStream(url, { headers = async () => ({}), onEvent, signal }) {
  const res = await fetch(url, { headers: { Accept: "text/event-stream", ...(await headers()) }, signal });
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    for (const e of parser.push(value)) onEvent(e.type, e.data, e.id);
  }
}
