"""The narration workflow (Agent Framework graph): one insight -> one verified line for one
(persona, language).

    Gather -> Explain -> Personalize -> Narrate -> Verify --ok, en--------------------> Publish
                                         ^  retry  |  ok, hi/es -> Localize -> Verify --ok-> Publish
                                         +---------+                ^ retry   |
                                    3 rejects at either stage -> Template ----+-------> Publish

Gather calls MCP tools only (rule 4). Explain, Narrate and Localize call the model; Verify and
Template are code (D7). A model reply that is not valid JSON counts as a rejection.
"""

import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Literal, Never

from agent_framework import Executor, Workflow, WorkflowBuilder, WorkflowContext, handler
from pydantic import ValidationError

from smi_agents import templates
from smi_agents.facts import StaleRequest, Tools, gather
from smi_agents.model import Model, Task, system_prompt
from smi_agents.types import FactSheet, Lang, Line, NarrationRequest, Verdict
from smi_agents.verifier import verify

MAX_ATTEMPTS = 3
# Publish and Template are terminal by design; the framework warns about them on every build.
logging.getLogger("agent_framework._workflows._validation").setLevel(logging.ERROR)
Stage = Literal["narrate", "localize"]

TONES = {
    "analyst": "Precise and technical. Lead with the decisive metric and say what it shows.",
    "casual": "Plain words for a casual viewer. One idea, no jargon.",
    "club_fan": "Speak to a fan of the favourite team, from their side of the moment.",
    "player_focus": "Put the favourite player first if they are involved; otherwise stay factual.",
    "metric_focus": "Build the line around the focus metric if it is among the facts.",
}


@dataclass
class Attempt:
    stage: Stage
    attempt: int
    ok: bool
    reasons: list[str]


@dataclass
class Job:
    request: NarrationRequest
    sheet: FactSheet
    explanation: dict[str, Any] = field(default_factory=dict)
    brief: dict[str, Any] = field(default_factory=dict)
    english: Line | None = None
    trail: list[Attempt] = field(default_factory=list)


@dataclass
class Draft:
    job: Job
    stage: Stage
    attempt: int
    line: Line | None
    error: str | None = None

    @property
    def lang(self) -> Lang:
        return "en" if self.stage == "narrate" else self.job.request.lang


@dataclass
class Checked:
    draft: Draft
    verdict: Verdict


@dataclass
class Narration:
    """The workflow's output: a line plus how it was obtained."""

    request: NarrationRequest
    sheet: FactSheet
    line: Line
    verified: bool
    template_fallback: bool
    trail: list[Attempt]
    model: str


def _parse_line(reply: str) -> Line:
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", reply.strip())
    return Line.model_validate(json.loads(text))


def _payload(job: Job, **extra: Any) -> dict[str, Any]:
    return {
        "sheet": job.sheet.model_dump(mode="json"),
        "request": job.request.model_dump(mode="json"),
        **extra,
    }


async def _ask(model: Model, task: Task, payload: dict[str, Any]) -> tuple[Line | None, str | None]:
    try:
        return _parse_line(await model.complete(task, system_prompt(task), payload)), None
    except (ValueError, ValidationError) as e:
        return None, f"unparseable_reply:{type(e).__name__}"


class Gather(Executor):
    def __init__(self, tools: Tools) -> None:
        super().__init__(id="gather")
        self.tools = tools

    @handler
    async def run(self, req: NarrationRequest, ctx: WorkflowContext[Job]) -> None:
        sheet = await gather(self.tools, req.insight_id)
        if req.match_id and sheet.insight.match_id != req.match_id:
            found = sheet.insight.match_id
            raise StaleRequest(f"{req.insight_id} is from {found}, not {req.match_id}")
        await ctx.send_message(Job(req, sheet))


class Explain(Executor):
    def __init__(self, model: Model) -> None:
        super().__init__(id="explain")
        self.model = model

    @handler
    async def run(self, job: Job, ctx: WorkflowContext[Job]) -> None:
        reply = await self.model.complete("explain", system_prompt("explain"), _payload(job))
        try:
            job.explanation = json.loads(reply)
        except ValueError:
            job.explanation = {"reason": job.sheet.code}
        await ctx.send_message(job)


class Personalize(Executor):
    """Code: turns the request into a brief for the Narrator (tone, perspective, focus)."""

    def __init__(self) -> None:
        super().__init__(id="personalize")

    @handler
    async def run(self, job: Job, ctx: WorkflowContext[Job]) -> None:
        req, sheet = job.request, job.sheet
        teams = [t.root for t in sheet.insight.teams]
        job.brief = {
            "persona": req.persona,
            "tone": TONES[req.persona],
            "max_metrics": templates.METRICS_BY_PERSONA[req.persona],
            "perspective_team": sheet.names.get(req.favourite_team or "")
            if req.favourite_team in teams
            else None,
            "focus_player": sheet.names.get(req.favourite_player or ""),
            "focus_metric": req.metric_focus,
        }
        await ctx.send_message(job)


class Narrate(Executor):
    def __init__(self, model: Model) -> None:
        super().__init__(id="narrate")
        self.model = model

    async def _draft(self, job: Job, attempt: int, feedback: list[str]) -> Draft:
        payload = _payload(
            job, explanation=job.explanation, brief=job.brief, feedback=feedback, lang="en"
        )
        line, error = await _ask(self.model, "narrate", payload)
        return Draft(job, "narrate", attempt, line, error)

    @handler
    async def first(self, job: Job, ctx: WorkflowContext[Draft]) -> None:
        await ctx.send_message(await self._draft(job, 1, []))

    @handler
    async def retry(self, checked: Checked, ctx: WorkflowContext[Draft]) -> None:
        d = checked.draft
        await ctx.send_message(await self._draft(d.job, d.attempt + 1, checked.verdict.reasons))


class Localize(Executor):
    def __init__(self, model: Model) -> None:
        super().__init__(id="localize")
        self.model = model

    @handler
    async def run(self, checked: Checked, ctx: WorkflowContext[Draft]) -> None:
        d = checked.draft
        job = d.job
        if d.stage == "narrate":  # the English line passed: translate it
            job.english = d.line
            attempt, feedback = 1, []
        else:
            attempt, feedback = d.attempt + 1, checked.verdict.reasons
        lang = job.request.lang
        payload = _payload(job, draft=job.english.model_dump(), lang=lang, feedback=feedback)
        line, error = await _ask(self.model, "localize", payload)
        await ctx.send_message(Draft(job, "localize", attempt, line, error))


class Verify(Executor):
    def __init__(self) -> None:
        super().__init__(id="verify")

    @handler
    async def run(self, draft: Draft, ctx: WorkflowContext[Checked]) -> None:
        if draft.line is None:
            verdict = Verdict(ok=False, reasons=[draft.error or "no_line"])
        else:
            verdict = verify(draft.line, draft.job.sheet, draft.lang)
        draft.job.trail.append(Attempt(draft.stage, draft.attempt, verdict.ok, verdict.reasons))
        await ctx.send_message(Checked(draft, verdict))


class Template(Executor):
    """Code: the template line in the target language, after MAX_ATTEMPTS rejections."""

    def __init__(self, model_name: str) -> None:
        super().__init__(id="template")
        self.model_name = model_name

    @handler
    async def run(self, checked: Checked, ctx: WorkflowContext[Never, Narration]) -> None:
        job = checked.draft.job
        lang = job.request.lang
        line = templates.render(job.sheet, job.request, lang)
        verdict = verify(line, job.sheet, lang)
        await ctx.yield_output(
            Narration(job.request, job.sheet, line, verdict.ok, True, job.trail, self.model_name)
        )


class Publish(Executor):
    def __init__(self, model_name: str) -> None:
        super().__init__(id="publish")
        self.model_name = model_name

    @handler
    async def run(self, checked: Checked, ctx: WorkflowContext[Never, Narration]) -> None:
        job, line = checked.draft.job, checked.draft.line
        assert line is not None
        await ctx.yield_output(
            Narration(job.request, job.sheet, line, True, False, job.trail, self.model_name)
        )


def _done(c: Checked) -> bool:
    return c.verdict.ok and (c.draft.stage == "localize" or c.draft.job.request.lang == "en")


def _to_localize(c: Checked) -> bool:
    if c.verdict.ok:
        return c.draft.stage == "narrate" and c.draft.job.request.lang != "en"
    return c.draft.stage == "localize" and c.draft.attempt < MAX_ATTEMPTS


def _retry_narrate(c: Checked) -> bool:
    return not c.verdict.ok and c.draft.stage == "narrate" and c.draft.attempt < MAX_ATTEMPTS


def _give_up(c: Checked) -> bool:
    return not c.verdict.ok and c.draft.attempt >= MAX_ATTEMPTS


def build(tools: Tools, model: Model) -> Workflow:
    gather_, explain = Gather(tools), Explain(model)
    personalize, narrate, localize = Personalize(), Narrate(model), Localize(model)
    verify_, template, publish = Verify(), Template(model.name), Publish(model.name)
    return (
        WorkflowBuilder(
            name="narration",
            description="Insight to verified overlay line",
            start_executor=gather_,
            output_from=[publish, template],
        )
        .add_chain([gather_, explain, personalize, narrate, verify_])
        .add_edge(localize, verify_)
        .add_edge(verify_, publish, condition=_done)
        .add_edge(verify_, localize, condition=_to_localize)
        .add_edge(verify_, narrate, condition=_retry_narrate)
        .add_edge(verify_, template, condition=_give_up)
        .build()
    )


class _WarmUp(Executor):
    @handler
    async def run(self, n: int, ctx: WorkflowContext[Never, int]) -> None:
        await ctx.yield_output(n)


async def warm_up(tools: Tools | None = None) -> None:
    """Pays the framework's first-run cost (and the first tool call) before a viewer is waiting."""
    await WorkflowBuilder(start_executor=_WarmUp(id="warm_up")).build().run(0)
    if tools is not None:
        try:
            await tools.call("match_state", {})
        except Exception:  # the core may not be up yet; narration reports that when it matters
            pass


class Narrator:
    """Runs the workflow per request and caches by (insight, persona, language, focus) (D3)."""

    def __init__(self, tools: Tools, model: Model) -> None:
        self.tools = tools
        self.model = model
        self.cache: dict[tuple, Narration] = {}
        self.locks: dict[tuple, asyncio.Lock] = {}

    async def narrate(self, req: NarrationRequest) -> Narration:
        key = tuple(req.model_dump().values())
        # Concurrent requests for the same line (several viewers) share one workflow run.
        async with self.locks.setdefault(key, asyncio.Lock()):
            if key not in self.cache:
                result = await build(self.tools, self.model).run(req)
                outputs = result.get_outputs()
                if not outputs:
                    raise RuntimeError(f"narration of {req.insight_id} produced no output")
                self.cache[key] = outputs[-1]
        return self.cache[key]
