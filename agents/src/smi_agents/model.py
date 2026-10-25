"""The model seam: every LLM call in the workflow goes through `Model.complete`.

`StubModel` is deterministic and offline (tests, rehearsals). `ScriptedModel` replays queued replies
to exercise the Verifier's retry and fallback paths. `ChatClientModel` adapts any Agent Framework
chat client; `FoundryModel` is the one over the Foundry deployment. Nothing else changes when the
model is swapped: the Verifier, retry and template fallback guard every reply.
"""

import json
import os
from collections import defaultdict
from collections.abc import Iterable, Mapping
from functools import cache
from importlib.resources import files
from typing import Any, Literal, Protocol

from smi_agents import settings, templates
from smi_agents.types import FactSheet, Line, NarrationRequest

Task = Literal["explain", "narrate", "localize"]


@cache
def system_prompt(task: Task) -> str:
    return files("smi_agents").joinpath(f"prompts/{task}.md").read_text("utf-8")


class Model(Protocol):
    name: str

    async def complete(self, task: Task, system: str, payload: dict[str, Any]) -> str:
        """Answers one task with JSON text. `payload` is the small JSON the prompt carries."""
        ...


class StubModel:
    """Deterministic stand-in: explains with the reason code and writes the template line."""

    name = "stub"

    async def complete(self, task: Task, system: str, payload: dict[str, Any]) -> str:
        sheet = FactSheet.model_validate(payload["sheet"])
        if task == "explain":
            params = list(sheet.insight.why_it_matters.params)
            return json.dumps({"reason": sheet.code, "key_facts": params})
        req = NarrationRequest.model_validate(payload["request"])
        lang = payload.get("lang", "en")
        return templates.render(sheet, req, lang).model_dump_json()


class ScriptedModel:
    """Replays queued replies per task, then defers to `then` (default: the stub)."""

    name = "scripted"

    def __init__(self, replies: dict[Task, Iterable[str | Line]], then: Model | None = None):
        self.replies: dict[str, list[str]] = defaultdict(list)
        for task, items in replies.items():
            self.replies[task] = [r.model_dump_json() if isinstance(r, Line) else r for r in items]
        self.then = then or StubModel()
        self.calls: list[tuple[Task, dict[str, Any]]] = []

    async def complete(self, task: Task, system: str, payload: dict[str, Any]) -> str:
        self.calls.append((task, payload))
        if self.replies[task]:
            return self.replies[task].pop(0)
        return await self.then.complete(task, system, payload)


JSON_OPTIONS: dict[str, Any] = {"response_format": {"type": "json_object"}, "temperature": 0.3}


class ChatClientModel:
    """Any Agent Framework chat client (`get_response`), asked for a JSON object."""

    def __init__(
        self, client: Any, name: str = "chat-client", options: dict[str, Any] | None = None
    ) -> None:
        self.client = client
        self.name = name
        self.options = options or JSON_OPTIONS

    async def complete(self, task: Task, system: str, payload: dict[str, Any]) -> str:
        from agent_framework import Message

        messages = [
            Message("system", [system]),
            Message("user", [json.dumps(payload, ensure_ascii=False)]),
        ]
        response = await self.client.get_response(messages, options=self.options)
        return response.text


DEFAULT_DEPLOYMENT = "gpt-5-mini"
# gpt-5 models reason and take no temperature; low effort keeps a moment near 2-4 s (D4).
FOUNDRY_OPTIONS: dict[str, Any] = {
    "response_format": {"type": "json_object"},
    "reasoning": {"effort": "minimal"},
}


class FoundryModel(ChatClientModel):
    """The Foundry deployment through Agent Framework's Foundry chat client (Responses API),
    signed in with `DefaultAzureCredential` (managed identity in Azure, `az login` locally)."""

    def __init__(self, endpoint: str, deployment: str = DEFAULT_DEPLOYMENT, client: Any = None):
        own = client is None
        super().__init__(
            client or self._client(endpoint, deployment), name="", options=FOUNDRY_OPTIONS
        )
        self.endpoint = endpoint
        self.deployment = deployment
        self.name = f"foundry:{deployment}"
        self.own_client = own

    @staticmethod
    def _client(endpoint: str, deployment: str) -> Any:
        from agent_framework_foundry import FoundryChatClient
        from azure.identity.aio import DefaultAzureCredential

        return FoundryChatClient(
            project_endpoint=endpoint, model=deployment, credential=DefaultAzureCredential()
        )

    async def complete(self, task: Task, system: str, payload: dict[str, Any]) -> str:
        # The deployment and reasoning effort follow the settings (ADR-019), per call.
        s = settings.current
        if s.model_deployment != self.deployment and self.own_client:
            self.client = self._client(self.endpoint, s.model_deployment)
            self.deployment = s.model_deployment
            self.name = f"foundry:{s.model_deployment}"
        self.options = {**FOUNDRY_OPTIONS, "reasoning": {"effort": s.reasoning_effort}}
        return await super().complete(task, system, payload)

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ, client: Any = None) -> "FoundryModel":
        endpoint = env.get("AZURE_AI_PROJECT_ENDPOINT")
        if not endpoint:
            raise ValueError(
                "--model foundry needs AZURE_AI_PROJECT_ENDPOINT (the project endpoint)"
            )
        return cls(endpoint, env.get("SMI_MODEL_DEPLOYMENT") or DEFAULT_DEPLOYMENT, client)
