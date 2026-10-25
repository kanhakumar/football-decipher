import httpx
import pytest
from test_cloud import ENDPOINT, FakeChatClient
from test_workflow import WRONG_NUMBER

from smi_agents import settings
from smi_agents.auth import EntraTokens, Gate
from smi_agents.model import FoundryModel, ScriptedModel
from smi_agents.preferences import InMemoryPreferences
from smi_agents.service import create_app
from smi_agents.types import NarrationRequest
from smi_agents.workflow import Narrator

pytestmark = pytest.mark.anyio


def answer(**values):
    return {"settings": values, "sources": {k: "app_configuration" for k in values}}


@pytest.fixture
def current(monkeypatch):
    """A fresh settings object in place of the module's, for one test."""
    fresh = settings.Settings({})
    monkeypatch.setattr(settings, "current", fresh)
    return fresh


def test_answers_from_the_core_update_the_settings(current, monkeypatch):
    changed = current.update(
        answer(**{"narration:max_attempts": 2, "access:sign_in_required": False})
    )
    assert sorted(changed) == ["access:sign_in_required", "narration:max_attempts"]
    assert (current.max_attempts, current.sign_in_required) == (2, False)
    # CORS origins come from the answer only when App Configuration sets them.
    monkeypatch.setenv("SMI_CORS_ORIGINS", "https://viewer.example.net")
    current.update(
        {"settings": {"access:cors_origins": "*"}, "sources": {"access:cors_origins": "default"}}
    )
    assert current.cors_origins == ["https://viewer.example.net"]
    current.update(answer(**{"access:cors_origins": "https://a.example, https://b.example"}))
    assert current.cors_origins == ["https://a.example", "https://b.example"]


async def test_a_poll_that_fails_keeps_the_settings(current):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503)

    async with httpx.AsyncClient(
        transport=httpx.MockTransport(handler), base_url="http://core"
    ) as http:
        assert await settings.refresh(http) is False
    assert current.max_attempts == 3


async def test_fewer_attempts_reach_the_template_sooner(tools, current):
    current.update(answer(**{"narration:max_attempts": 1}))
    model = ScriptedModel({"narrate": [WRONG_NUMBER.model_dump_json()]})
    n = await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012"))
    assert n.template_fallback and len(n.trail) == 1


async def test_the_model_follows_reasoning_effort(tools, current):
    current.update(answer(**{"narration:reasoning_effort": "low"}))
    client = FakeChatClient()
    model = FoundryModel.from_env({"AZURE_AI_PROJECT_ENDPOINT": ENDPOINT}, client=client)
    await Narrator(tools, model).narrate(NarrationRequest(insight_id="I_0012"))
    assert {options["reasoning"]["effort"] for _, options in client.requests} == {"low"}


async def test_without_required_sign_in_anyone_may_narrate_but_not_save(tools, current):
    current.update(answer(**{"access:sign_in_required": False}))
    gate = Gate("secret-key", EntraTokens("aud", keys=object()))
    app = create_app(
        Narrator(tools, ScriptedModel({})), gate=gate, preferences=InMemoryPreferences()
    )
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agents.test") as api:
        res = await api.post("/api/narrate", json={"insight_id": "I_0012", "mode": "template"})
        assert res.status_code == 200
        assert (await api.put("/api/me/preferences", json={})).status_code == 403
