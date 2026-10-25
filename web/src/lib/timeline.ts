// The viewer's timeline: a clock on the broadcast feed's video_ts_ms (D5) and a queue that shows
// overlays when the clock reaches their t_start_ms (D8). Pure: no DOM, no network.

import type { Overlay, ShownOverlay } from "./types";

/** How far the clock may run ahead of the last envelope, in video ms (covers gaps between events). */
export const MAX_EXTRAPOLATION_MS = 8000;

export class ViewerClock {
  ts: number | null = null; // video_ts_ms of the newest envelope seen
  wall = 0; // wall time it arrived
  speed = 1;
  running = false;

  observe(videoTsMs: number, wallMs: number): void {
    if (this.ts === null || videoTsMs >= this.ts) {
      this.ts = videoTsMs;
      this.wall = wallMs;
    }
  }

  setPlayback(speed: number, running: boolean): void {
    this.speed = speed;
    this.running = running;
  }

  /** The video position now: the last envelope plus playback since, capped. */
  now(wallMs: number): number | null {
    if (this.ts === null) return null;
    if (!this.running) return this.ts;
    const ahead = Math.min((wallMs - this.wall) * this.speed, MAX_EXTRAPOLATION_MS);
    return this.ts + Math.max(0, ahead);
  }

  reset(): void {
    this.ts = null;
    this.wall = 0;
  }
}

/** Real seconds the model gets per moment, and the delay bounds (the core's limit is 600 s). */
export const NARRATION_BUDGET_S = 12;
export const MIN_DELAY_S = 30;
export const MAX_DELAY_S = 600;

export interface DelayBounds {
  narrationBudgetS?: number;
  minDelayS?: number;
  maxDelayS?: number;
}

/** The viewer delay (video seconds) that leaves the model its narration budget of real time. */
export function suggestDelay(
  speed: number,
  { narrationBudgetS = NARRATION_BUDGET_S, minDelayS = MIN_DELAY_S, maxDelayS = MAX_DELAY_S }: DelayBounds = {},
): number {
  return Math.min(maxDelayS, Math.max(minDelayS, Math.ceil(speed * narrationBudgetS)));
}

/** Screen slots: the lower third, the corner card and drawings on the pitch. */
export type Slot = "lower" | "corner" | "pitch";
export const SLOTS: Slot[] = ["lower", "corner", "pitch"];

export function slotOf(overlay: Overlay): Slot {
  if (overlay.style === "pitch_overlay") return "pitch";
  if (overlay.type === "scoreline_update" || overlay.style === "corner_card") return "corner";
  return "lower";
}

export type ItemState = "pending" | "showing" | "done" | "missed" | "retracted";

export interface QueueItem {
  overlay: ShownOverlay;
  slot: Slot;
  state: ItemState;
  shownAt: number | null;
  lateMs: number | null;
  wasShowing?: boolean;
}

export interface QueueChange {
  change: "shown" | "done" | "missed";
  item: QueueItem;
}

const DEFAULT_DURATION_MS = 8000;

/**
 * Overlays waiting for their time, on screen, done, or retracted. One overlay per slot at a time;
 * a card stays at least `minShowMs` of wall time so it can be read at fast playback.
 */
export class OverlayQueue {
  minShowMs: number;
  items = new Map<string, QueueItem>();

  constructor({ minShowMs = 4000 }: { minShowMs?: number } = {}) {
    this.minShowMs = minShowMs;
  }

  /** Adds or updates an overlay; a retract op withdraws the overlay with that id. */
  put(overlay: Overlay): QueueItem | null {
    if (overlay.op === "retract") return this.retract(overlay.overlay_id);
    const shown = overlay as ShownOverlay;
    const old = this.items.get(overlay.overlay_id);
    const item: QueueItem = {
      overlay: shown,
      slot: slotOf(shown),
      state: old ? old.state : "pending",
      shownAt: old ? old.shownAt : null,
      lateMs: old ? old.lateMs : null,
    };
    this.items.set(overlay.overlay_id, item);
    return item;
  }

  /**
   * Replaces the overlay for an insight with another rendering of the same moment (persona or
   * language changed, or the model line after the quick one), keeping its place on the timeline
   * and on screen.
   */
  replaceForInsight(overlay: ShownOverlay): QueueItem | null {
    for (const [id, item] of this.items) {
      if (item.overlay.insight_id === overlay.insight_id && item.slot === slotOf(overlay)) {
        if (id === overlay.overlay_id) return this.put(overlay);
        this.items.delete(id);
        const moved = { ...item, overlay };
        this.items.set(overlay.overlay_id, moved);
        return moved;
      }
    }
    return this.put(overlay);
  }

  retract(overlayId: string): QueueItem | null {
    const item = this.items.get(overlayId);
    if (!item || item.state === "retracted") return null;
    item.wasShowing = item.state === "showing";
    item.state = "retracted";
    return item;
  }

  /** Withdraws every overlay of an insight (the insight was retracted or replaced). */
  retractInsight(insightId: string): QueueItem[] {
    const out: QueueItem[] = [];
    for (const [id, item] of this.items) {
      if (item.overlay.insight_id === insightId) {
        const r = this.retract(id);
        if (r) out.push(r);
      }
    }
    return out;
  }

  /** True if the moment's card never made it on screen because its slot had passed. */
  missedInsight(insightId: string): boolean {
    for (const item of this.items.values()) {
      if (item.overlay.insight_id === insightId && item.state === "missed") return true;
    }
    return false;
  }

  showing(slot: Slot): QueueItem | null {
    for (const item of this.items.values()) {
      if (item.slot === slot && item.state === "showing") return item;
    }
    return null;
  }

  /** Advances to video time `ts`; returns what changed. */
  tick(ts: number, wallMs: number): QueueChange[] {
    const changes: QueueChange[] = [];
    for (const slot of SLOTS) {
      const current = this.showing(slot);
      if (current) {
        const o = current.overlay;
        const over = ts >= o.t_start_ms + (o.duration_ms ?? DEFAULT_DURATION_MS);
        if (over && wallMs - (current.shownAt ?? 0) >= this.minShowMs) {
          current.state = "done";
          changes.push({ change: "done", item: current });
        } else continue;
      }
      const due = [...this.items.values()]
        .filter((i) => i.slot === slot && i.state === "pending" && i.overlay.t_start_ms <= ts)
        .sort((a, b) => a.overlay.t_start_ms - b.overlay.t_start_ms);
      for (const item of due) {
        const o = item.overlay;
        if (ts > o.t_start_ms + (o.duration_ms ?? DEFAULT_DURATION_MS)) {
          item.state = "missed";
          changes.push({ change: "missed", item });
          continue;
        }
        item.state = "showing";
        item.shownAt = wallMs;
        item.lateMs = ts - o.t_start_ms;
        changes.push({ change: "shown", item });
        break;
      }
    }
    return changes;
  }

  clear(): void {
    this.items.clear();
  }
}
