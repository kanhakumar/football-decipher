"""Builds the FactSheet for one insight through MCP tool calls only (rule 4, D9).

The tool plan is fixed per insight kind and chosen by code, not by the model: the insight with its
evidence summary, the squads for names, and for spell-type insights the subject team's metrics
over the spell. Every answer is small; nothing bulk reaches a prompt.
"""

from typing import Any, Protocol

from smi_agents.models.insight import Insight
from smi_agents.types import FactSheet, MetricFact

# Insights about a spell of play, with an `after` window worth putting numbers on.
SPELL_KINDS = {"pressure_shift", "momentum_swing", "control_vs_chaos", "rhythm_change"}
SPELL_METRICS = ["shots", "xg", "possession_share", "pass_completion", "ppda"]


class ToolError(Exception):
    """A tool call failed (the core refused it or could not be reached)."""


class StaleRequest(ToolError):
    """The caller asked about a match the core is no longer playing."""


class Tools(Protocol):
    async def call(self, name: str, args: dict[str, Any]) -> dict[str, Any]: ...


class McpTools:
    """Calls tools on an MCP client session (`mcp.Client`) and returns their structured result."""

    def __init__(self, client: Any) -> None:
        self.client = client
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def call(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        self.calls.append((name, args))
        result = await self.client.call_tool(name, args)
        if result.is_error:
            text = " ".join(getattr(c, "text", "") for c in result.content)
            raise ToolError(f"{name}: {text.removeprefix(f'Error executing tool {name}: ')}")
        return result.structured_content or {}


def _support(insight: Insight, by_type: dict[str, int]) -> set[str]:
    """What the evidence supports claiming: kind, reason code, event types and fact flags."""
    facts = insight.facts
    tokens = {insight.kind.value, insight.why_it_matters.code, *by_type}
    for key, value in facts.items():
        if value is True:
            tokens.add(key)
        elif isinstance(value, str):
            tokens.add(f"{key}={value}")
    # The match context (ADR-017): the score and the players on the pitch at the moment.
    state = facts.get("ctx_game_state")
    if state in ("leading", "level", "trailing"):
        tokens.add(
            {"leading": "score=lead", "level": "score=level", "trailing": "score=trail"}[state]
        )
    if min(facts.get("ctx_players_for", 11), facts.get("ctx_players_against", 11)) < 11:
        tokens.add("red_card")  # "ten men" is a claim the context supports
    if "team_goals" in facts and "opponent_goals" in facts:
        tg, og = facts["team_goals"], facts["opponent_goals"]
        tokens.add("score=lead" if tg > og else "score=level" if tg == og else "score=trail")
        if insight.kind.value == "goal" and tg == og:
            tokens.add("equalising_goal")
    return tokens


def _numbers(facts: dict[str, Any]) -> dict[str, float]:
    out: dict[str, float] = {}
    for key, value in facts.items():
        if isinstance(value, bool):
            continue
        if isinstance(value, int | float):
            out[key] = float(value)
        elif isinstance(value, list):
            for i, v in enumerate(value):
                if isinstance(v, int | float) and not isinstance(v, bool):
                    out[f"{key}_{i}"] = float(v)
    return out


async def gather(tools: Tools, insight_id: str) -> FactSheet:
    detail = await tools.call("get_insight", {"insight_id": insight_id})
    info = await tools.call("match_info", {})
    insight = Insight.model_validate(detail["insight"])
    evidence = detail.get("evidence", {})
    sample = evidence.get("sample", [])
    by_type = evidence.get("by_type", {})

    roster: dict[str, str] = {}
    short: dict[str, str] = {}
    for team in info.get("teams", []):
        roster[team["team_id"]] = team["name"]
        short[team["team_id"]] = team["short"]
        for p in team.get("starters", []) + team.get("bench", []):
            roster[p["player_id"]] = p["name"]

    mentionable = {t.root for t in insight.teams} | {p.root for p in insight.players or []}
    mentionable |= {e["team_id"] for e in sample} | {
        e["player_id"] for e in sample if e.get("player_id")
    }
    names = {eid: roster[eid] for eid in sorted(mentionable) if eid in roster}

    numbers = _numbers(insight.facts)
    numbers["evidence_count"] = float(evidence.get("count", len(insight.evidence_event_ids)))
    minutes = sorted({int(e["minute"]) for e in sample})
    minute = int(insight.facts.get("minute", minutes[-1] if minutes else 0))

    metrics: list[MetricFact] = []
    facts = insight.facts
    if insight.kind.value in SPELL_KINDS and "after_to_minute" in facts:
        start = facts.get("before_to_minute", facts.get("after_from_minute"))
        stats = await tools.call(
            "team_stats",
            {
                "team": insight.teams[0].root,
                "metrics": SPELL_METRICS,
                "period": insight.period,
                "from_minute": start,
                "to_minute": facts["after_to_minute"],
            },
        )
        for m in stats.get("metrics", []):
            if m.get("value") is None:
                continue
            metrics.append(
                MetricFact(name=m["metric"], value=m["value"], unit=m["unit"], basis=m["basis"])
            )
            numbers[f"spell_{m['metric']}"] = float(m["value"])

    return FactSheet(
        insight=insight,
        minute=minute,
        numbers=numbers,
        minutes=minutes,
        names=names,
        roster=roster,
        team_short=short,
        support=_support(insight, by_type),
        metrics=metrics,
    )
