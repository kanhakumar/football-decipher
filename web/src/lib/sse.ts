// Server-sent events over fetch, so a stream can carry an Authorization header (ADR-018);
// EventSource cannot send headers. The parser is pure and tested; `followStream` does the I/O.

export interface SseEvent {
  type: string;
  data: string;
  id: string | null;
}

/**
 * Parses SSE text. Feed it chunks as they arrive; it returns the complete events so far and keeps
 * any partial event for the next chunk.
 */
export class SseParser {
  private buffer = "";

  push(chunk: string): SseEvent[] {
    this.buffer += chunk.replace(/\r\n?/g, "\n");
    const events: SseEvent[] = [];
    let end: number;
    while ((end = this.buffer.indexOf("\n\n")) >= 0) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      let type = "message";
      let id: string | null = null;
      const data: string[] = [];
      for (const line of block.split("\n")) {
        if (!line || line.startsWith(":")) continue; // comments are keep-alives
        const colon = line.indexOf(":");
        const field = colon < 0 ? line : line.slice(0, colon);
        const value = colon < 0 ? "" : line.slice(colon + 1).replace(/^ /, "");
        if (field === "event") type = value;
        else if (field === "data") data.push(value);
        else if (field === "id") id = value;
      }
      if (data.length) events.push({ type, id, data: data.join("\n") });
    }
    return events;
  }
}

/**
 * Follows an SSE stream until it ends or `signal` aborts. `headers()` is called once per connection
 * (a fresh token). Calls `onEvent` per event; resolves when the server closes the stream.
 */
export async function followStream(
  url: string,
  opts: {
    headers?: () => Promise<Record<string, string>>;
    onEvent: (e: SseEvent) => void;
    signal?: AbortSignal;
    fetch?: typeof fetch;
  },
): Promise<void> {
  const extra = opts.headers ? await opts.headers() : {};
  const res = await (opts.fetch ?? fetch)(url, { headers: { Accept: "text/event-stream", ...extra }, signal: opts.signal });
  if (!res.ok || !res.body) throw new Error(`${url}: ${res.status}`);
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    for (const e of parser.push(value)) opts.onEvent(e);
  }
}
