"""Narrations to overlays (overlay.schema.json), timed by the insight's video_ts_ms (D5, D8)."""

from datetime import UTC, datetime
from typing import Any

from smi_agents.models.overlay import Overlay
from smi_agents.workflow import Narration

# Agent overlays use O_00000001 upward; O_90000000 and up belong to the core (ADR-013).
AGENT_ID_MAX = 89_999_999


def overlay_id(n: int) -> str:
    if not 1 <= n <= AGENT_ID_MAX:
        raise ValueError(f"agent overlay number {n} out of range")
    return f"O_{n:08d}"


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def show(n: Narration, oid: str) -> dict[str, Any]:
    insight, req, line = n.sheet.insight, n.request, n.line
    players = [p.root for p in insight.players or []]
    focus = {"team_id": req.favourite_team or insight.teams[0].root}
    if req.favourite_player:
        focus["player_id"] = req.favourite_player
    if req.metric_focus:
        focus["metric"] = req.metric_focus
    content: dict[str, Any] = {
        "headline": line.headline,
        "team_id": insight.teams[0].root,
        "metrics": [m.model_dump(exclude_none=True) for m in line.metrics],
    }
    if line.body:
        content["body"] = line.body
    if players:
        content["player_id"] = players[0]
    overlay = {
        "overlay_id": oid,
        "match_id": insight.match_id,
        "insight_id": insight.insight_id,
        "op": "show",
        "type": "milestone_badge" if insight.kind.value == "milestone" else "insight_card",
        "t_start_ms": insight.video_ts_ms,
        "duration_ms": int(6000 + 4000 * insight.importance),
        "style": "lower_third",
        "lang": req.lang,
        "persona": req.persona,
        "focus": focus,
        "content": content,
        "evidence_event_ids": [e.root for e in insight.evidence_event_ids],
        "basis": insight.basis.value,
        "importance": insight.importance,
        "verified": n.verified,
        "template_fallback": n.template_fallback,
        "generated_at": _now(),
    }
    return Overlay.model_validate(overlay).model_dump(mode="json", exclude_none=True)


def retract(shown: dict[str, Any]) -> dict[str, Any]:
    """Withdraws an overlay this service published (its insight was retracted or replaced)."""
    overlay = {
        "overlay_id": shown["overlay_id"],
        "match_id": shown["match_id"],
        "insight_id": shown.get("insight_id"),
        "op": "retract",
        "generated_at": _now(),
    }
    return Overlay.model_validate(overlay).model_dump(mode="json", exclude_none=True)
