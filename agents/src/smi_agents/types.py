"""Workflow-internal types. The contracts (Insight, Overlay) are generated in smi_agents.models."""

from typing import Literal

from pydantic import BaseModel, Field

from smi_agents.models.insight import Insight

Lang = Literal["en", "hi", "es"]
Persona = Literal["analyst", "casual", "club_fan", "player_focus", "metric_focus"]
LANGS: tuple[Lang, ...] = ("en", "hi", "es")


class MetricFact(BaseModel):
    """One metric the core returned for this insight's window, with its basis tag."""

    name: str
    value: float
    unit: str
    basis: str


class FactSheet(BaseModel):
    """Everything the narration may use for one insight, gathered through MCP tools.

    `numbers` is the only source of numbers a line may contain; `names` the only teams and
    players it may mention; `support` the only event claims it may make (rule 3, D7).
    """

    insight: Insight
    minute: int
    numbers: dict[str, float]
    minutes: list[int] = Field(default_factory=list, description="Display minutes of the evidence")
    names: dict[str, str] = Field(description="Entity id -> display name, for this insight")
    roster: dict[str, str] = Field(description="Every team and player id -> display name")
    team_short: dict[str, str] = Field(default_factory=dict)
    support: set[str] = Field(default_factory=set)
    metrics: list[MetricFact] = Field(default_factory=list)

    @property
    def code(self) -> str:
        return self.insight.why_it_matters.code

    @property
    def subject_team(self) -> str:
        return self.insight.teams[0].root

    @property
    def other_team(self) -> str | None:
        return self.insight.teams[1].root if len(self.insight.teams) > 1 else None


class LineMetric(BaseModel):
    label: str
    value: int | float | str
    unit: str | None = None


class Line(BaseModel):
    """A narrated line: what an overlay card shows."""

    headline: str
    body: str = ""
    metrics: list[LineMetric] = Field(default_factory=list)


class NarrationRequest(BaseModel):
    insight_id: str = Field(pattern=r"^I_[0-9]{1,6}$")
    persona: Persona = "casual"
    lang: Lang = "en"
    favourite_team: str | None = None
    favourite_player: str | None = None
    metric_focus: str | None = None


class Verdict(BaseModel):
    ok: bool
    reasons: list[str] = Field(default_factory=list)
