"""The cloud adapters, offline: Foundry model over a fake chat client, the narration cache twins,
CORS and tracing settings from env."""

import json
from types import SimpleNamespace

import httpx
import pytest
from azure.core.exceptions import ResourceNotFoundError

from smi_agents import tracing
from smi_agents.__main__ import model_for
from smi_agents.cache import CosmosCache, InMemoryCache, from_doc, item_id, open_cache, to_doc
from smi_agents.model import FoundryModel, StubModel, Task, system_prompt
from smi_agents.service import cors_origins, create_app
from smi_agents.types import NarrationRequest
from smi_agents.workflow import Narrator

pytestmark = pytest.mark.anyio

ENDPOINT = "https://smi.services.ai.azure.com/api/projects/smi"


class FakeChatClient:
    """Records each request and answers it with the stub, as a well-behaved deployment would."""

    def __init__(self) -> None:
        self.requests: list[tuple[list, dict]] = []
        self.stub = StubModel()
        tasks: tuple[Task, ...] = ("explain", "narrate", "localize")
        self.tasks = {system_prompt(t): t for t in tasks}

    async def get_response(self, messages, options):
        self.requests.append((messages, options))
        system, user = messages
        task = self.tasks[system.text]
        reply = await self.stub.complete(task, system.text, json.loads(user.text))
        return SimpleNamespace(text=reply)


class FakeContainer:
    """The async Cosmos container calls the cache makes, over a dict keyed like Cosmos."""

    def __init__(self) -> None:
        self.items: dict[tuple[str, str], dict] = {}

    async def read_item(self, item: str, partition_key: str) -> dict:
        if (partition_key, item) not in self.items:
            raise ResourceNotFoundError("Entity with the specified id does not exist")
        return json.loads(json.dumps(self.items[(partition_key, item)]))

    async def upsert_item(self, body: dict) -> dict:
        self.items[(body["insight_id"], body["id"])] = json.loads(json.dumps(body))
        return body


async def test_foundry_model_asks_the_deployment_for_json_with_the_system_prompt(tools):
    client = FakeChatClient()
    model = FoundryModel.from_env({"AZURE_AI_PROJECT_ENDPOINT": ENDPOINT}, client=client)
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0008", lang="es"))
    assert n.verified and not n.template_fallback and n.model == "foundry:gpt-5-mini"
    assert [client.tasks[m[0].text] for m, _ in client.requests] == [
        "explain", "narrate", "localize"
    ]  # fmt: skip
    for messages, options in client.requests:
        assert [m.role for m in messages] == ["system", "user"]
        assert json.loads(messages[1].text)["request"]["insight_id"] == "I_0008"
        assert options["response_format"] == {"type": "json_object"}
        assert "temperature" not in options  # gpt-5 models refuse it


def test_foundry_model_reads_the_deployment_from_env():
    model = FoundryModel.from_env(
        {"AZURE_AI_PROJECT_ENDPOINT": ENDPOINT, "SMI_MODEL_DEPLOYMENT": "gpt-5"}, client=object()
    )
    assert (model.endpoint, model.deployment, model.name) == (ENDPOINT, "gpt-5", "foundry:gpt-5")


def test_model_for_foundry_needs_the_project_endpoint():
    with pytest.raises(SystemExit, match="AZURE_AI_PROJECT_ENDPOINT"):
        model_for("foundry", env={"SMI_MODEL_DEPLOYMENT": "gpt-5-mini"})
    with pytest.raises(SystemExit, match="unknown model"):
        model_for("gpt", env={})


async def test_model_for_foundry_builds_the_real_client_without_calling_azure():
    model = model_for("foundry", env={"AZURE_AI_PROJECT_ENDPOINT": ENDPOINT})
    assert isinstance(model, FoundryModel) and model.client.model == "gpt-5-mini"


async def test_a_narration_survives_the_cache_document_round_trip(tools):
    n = await Narrator(tools, StubModel()).narrate(NarrationRequest(insight_id="I_0020", lang="hi"))
    back = from_doc(json.loads(json.dumps(to_doc(n))))
    assert back == n


async def test_the_in_memory_cache_serves_repeats_without_the_core(tools, core):
    cache = InMemoryCache()
    req = NarrationRequest(insight_id="I_0008", persona="analyst")
    first = await Narrator(tools, StubModel(), cache).narrate(req)
    calls = len(core.requests)
    again = await Narrator(tools, StubModel(), cache).narrate(req)  # another replica, same cache
    assert again is first and len(core.requests) == calls
    assert await cache.get(NarrationRequest(insight_id="I_0008")) is None


async def test_the_cosmos_cache_shares_lines_across_restarts(tools, core):
    container = FakeContainer()
    req = NarrationRequest(insight_id="I_0019", persona="club_fan", favourite_team="T_HOM")
    first = await Narrator(tools, StubModel(), CosmosCache(container)).narrate(req)
    [(partition, item)] = container.items
    assert (partition, item) == ("I_0019", item_id(req)) and len(item) == 64
    calls = len(core.requests)
    again = await Narrator(tools, StubModel(), CosmosCache(container)).narrate(req)
    assert again == first and len(core.requests) == calls
    assert await CosmosCache(container).get(req.model_copy(update={"lang": "es"})) is None


async def test_without_a_cosmos_endpoint_the_cache_is_in_memory():
    async with open_cache({}) as cache:
        assert isinstance(cache, InMemoryCache)


def test_cors_origins_come_from_env():
    assert cors_origins({}) == ["*"]
    env = {"SMI_CORS_ORIGINS": "https://viewer.example.net, http://127.0.0.1:5500"}
    assert cors_origins(env) == ["https://viewer.example.net", "http://127.0.0.1:5500"]


async def test_the_service_allows_only_the_configured_origins(tools, monkeypatch):
    monkeypatch.setenv("SMI_CORS_ORIGINS", "https://viewer.example.net")
    app = create_app(Narrator(tools, StubModel()))
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agents.test") as client:

        async def allowed(origin: str) -> str | None:
            res = await client.get("/health", headers={"Origin": origin})
            return res.headers.get("access-control-allow-origin")

        assert await allowed("https://viewer.example.net") == "https://viewer.example.net"
        assert await allowed("https://elsewhere.example.net") is None


def test_tracing_is_off_without_a_connection_string():
    assert tracing.setup({}) is False
