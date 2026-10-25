// The shapes the viewer reads from the core and the agent service (see /schema for the contracts).
// Only the fields the viewer uses are typed.

export type Lang = "en" | "hi" | "es";
export type Persona = "casual" | "analyst" | "club_fan" | "player_focus" | "metric_focus";
export type Basis = "event_data" | "tracking" | "event_estimate";

export interface Metric {
  label: string;
  value: number | string;
  unit?: string | null;
}

export interface Position {
  player_id: string;
  x: number;
  y: number;
}

export interface Overlay {
  overlay_id: string;
  match_id: string;
  insight_id?: string;
  op: "show" | "retract";
  type?: string;
  t_start_ms?: number;
  duration_ms?: number;
  style?: string;
  lang?: Lang;
  persona?: Persona;
  content?: {
    headline: string;
    body?: string;
    metrics?: Metric[];
    team_id?: string;
    player_id?: string;
    positions?: Position[];
    formation?: string;
  };
  basis?: Basis;
  importance?: number;
  verified?: boolean;
  template_fallback?: boolean;
}

/** An overlay that is shown (op "show"), with the fields a show always has. */
export type ShownOverlay = Overlay & Required<Pick<Overlay, "t_start_ms" | "content">>;

export interface Insight {
  insight_id: string;
  match_id: string;
  kind: string;
  period: number;
  video_ts_ms: number;
  teams: string[];
  players?: string[];
  facts: Record<string, number | string | boolean | unknown[]>;
  importance: number;
  status?: "active" | "retracted";
  supersedes?: string;
}

export interface Player {
  player_id: string;
  name: string;
  shirt: number;
  position?: string;
}

export interface Lineup {
  team_id: string;
  name: string;
  short: string;
  colors?: string[];
  formation?: string;
  starters: Player[];
  bench: Player[];
}

export interface MatchEvent {
  event_id: string;
  type: string;
  period: number;
  period_clock_s: number;
  video_ts_ms: number;
  team_id: string;
  player_id?: string;
  x?: number;
  y?: number;
  outcome?: string;
}

export interface TrackingFrame {
  video_ts_ms: number;
  ball: { x: number; y: number };
  players: { player_id: string; team_id: string; x: number; y: number }[];
}

export interface Envelope {
  match_id: string;
  payload_type: "match_meta" | "lineup" | "event" | "tracking_frame" | "delete";
  payload: Record<string, unknown>;
}

export interface Playback {
  status: "idle" | "running" | "paused" | "finished";
  seed: number;
  speed: number;
  delay_s: number;
  tracking: boolean;
  run_id?: string;
  envelopes_viewer: number;
  live?: { match_clock_s: number };
}

export interface Narration {
  source: "template" | "model";
  verified: boolean;
  template_fallback: boolean;
  model: string;
  latency_ms: number;
}
