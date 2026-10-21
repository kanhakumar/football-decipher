"""Template lines (templates.yaml): the fallback after failed verification (D7), the stub model's
output, and the persona framing and metric cards shared with model-written lines."""

import re
from functools import cache
from importlib.resources import files
from typing import Any

import yaml

from smi_agents.types import FactSheet, Lang, Line, LineMetric, NarrationRequest

_FIELD = re.compile(r"\{(\w+)(?::(\w+))?\}")
# Facts never shown as metric cards (they are in the text or are context).
_NOT_METRICS = {"minute", "team_goals", "opponent_goals", "evidence_count"}
METRICS_BY_PERSONA = {
    "analyst": 4,
    "metric_focus": 3,
    "casual": 2,
    "club_fan": 2,
    "player_focus": 2,
}


class TemplateError(KeyError):
    pass


@cache
def table() -> dict[str, Any]:
    return yaml.safe_load(files("smi_agents").joinpath("templates.yaml").read_text("utf-8"))


def codes() -> set[str]:
    return set(table()["codes"])


def _values(sheet: FactSheet, req: NarrationRequest | None, lang: Lang) -> dict[str, Any]:
    names = sheet.roster
    players = [p.root for p in sheet.insight.players or []]
    values: dict[str, Any] = dict(sheet.numbers)
    values |= {
        "team": names.get(sheet.subject_team, sheet.subject_team),
        "opponent": names.get(sheet.other_team or "", ""),
        "player": names.get(players[0], "") if players else "",
        "minute": sheet.minute,
        "basis": table()["basis"][sheet.insight.basis.value][lang],
    }
    if req and req.favourite_team:
        values["fav"] = names.get(req.favourite_team, req.favourite_team)
    if req and req.favourite_player:
        values["fav_player"] = names.get(req.favourite_player, req.favourite_player)
    return values


def fill(text: str, values: dict[str, Any]) -> str:
    def one(m: re.Match[str]) -> str:
        key, fmt = m.group(1), m.group(2)
        if key not in values:
            raise TemplateError(key)
        v = values[key]
        if fmt == "int":
            return str(round(float(v)))
        if fmt == "pct":
            return str(round(float(v) * 100))
        if fmt in ("1", "2"):
            return f"{float(v):.{fmt}f}"
        return str(v)

    return _FIELD.sub(one, text)


def _metric(sheet: FactSheet, name: str, lang: Lang) -> LineMetric | None:
    label = table()["labels"].get(name, {}).get(lang)
    if label is None or name not in sheet.numbers:
        return None
    value = sheet.numbers[name]
    unit = next((m.unit for m in sheet.metrics if f"spell_{m.name}" == name), None)
    if unit == "%":  # the core already reports it in percent
        return LineMetric(label=label, value=round(value, 1), unit="%")
    if "share" in name and 0 <= value <= 1:  # a fraction from the insight's facts
        return LineMetric(label=label, value=round(value * 100, 1), unit="%")
    if name.endswith("xg") or "rate" in name:
        return LineMetric(label=label, value=round(value, 2))
    if value == int(value):
        return LineMetric(label=label, value=int(value))
    return LineMetric(label=label, value=round(value, 1))


def metrics(sheet: FactSheet, req: NarrationRequest, lang: Lang) -> list[LineMetric]:
    """Metric cards: the reason's params first, then other facts, then spell metrics."""
    params = [k for k in sheet.insight.why_it_matters.params if k not in _NOT_METRICS]
    others = [k for k in sheet.insight.facts if k not in _NOT_METRICS and k not in params]
    spell = [k for k in sheet.numbers if k.startswith("spell_")]
    order = list(dict.fromkeys(params + others + spell))
    if req.metric_focus:
        focus = [k for k in order if k in (req.metric_focus, f"spell_{req.metric_focus}")]
        order = focus + [k for k in order if k not in focus]
    picked = [m for k in order if (m := _metric(sheet, k, lang)) is not None]
    return picked[: METRICS_BY_PERSONA.get(req.persona, 2)]


def frame(body: str, sheet: FactSheet, req: NarrationRequest, lang: Lang) -> str:
    """Persona framing: a fan's perspective, a player in focus, or the analyst's basis line."""
    personas = table()["personas"]
    values = _values(sheet, req, lang)
    teams = [t.root for t in sheet.insight.teams]
    if req.persona == "club_fan" and req.favourite_team in teams:
        polarity = table()["polarity"].get(sheet.code, "good")
        good_for_fan = (polarity == "good") == (req.favourite_team == teams[0])
        side = "neutral" if polarity == "neutral" else "for" if good_for_fan else "against"
        body = fill(personas["club_fan"][lang][side], values) + body
    if req.persona == "player_focus" and req.favourite_player in sheet.names:
        body = fill(personas["player_focus"][lang]["focus"], values) + body
    if req.persona == "analyst":
        body = body + " " + fill(personas["analyst"][lang]["tail"], values)
    return body


def render(sheet: FactSheet, req: NarrationRequest, lang: Lang) -> Line:
    """The template line for this insight, persona and language."""
    entry = table()["codes"].get(sheet.code, {}).get(lang)
    if entry is None:
        raise TemplateError(f"{sheet.code}/{lang}")
    values = _values(sheet, req, lang)
    return Line(
        headline=fill(entry["headline"], values),
        body=frame(fill(entry["body"], values), sheet, req, lang),
        metrics=metrics(sheet, req, lang),
    )
