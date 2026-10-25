import json

import httpx
import jsonschema
import pytest

from conftest import SCHEMA
from smi_agents.model import StubModel
from smi_agents.service import create_app
from smi_agents.workflow import Narrator

pytestmark = pytest.mark.anyio

OVERLAY_SCHEMA = json.loads((SCHEMA / "overlay.schema.json").read_text("utf-8"))


@pytest.fixture
async def api(tools):
    app = create_app(Narrator(tools, StubModel()))
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agents.test") as client:
        yield client


async def test_narrate_returns_a_verified_overlay_for_the_viewers_settings(api):
    body = {"insight_id": "I_0025", "persona": "club_fan", "lang": "es",
            "favourite_team": "T_AWY", "match_id": "SYN-154"}  # fmt: skip
    res = await api.post("/api/narrate", json=body)
    assert res.status_code == 200
    data = res.json()
    jsonschema.validate(data["overlay"], OVERLAY_SCHEMA)
    assert (data["overlay"]["lang"], data["overlay"]["persona"]) == ("es", "club_fan")
    assert data["overlay"]["content"]["body"].startswith("Buenas noticias para la afición")
    assert data["narration"]["verified"] and data["narration"]["model"] == "stub"
    assert [a["stage"] for a in data["narration"]["attempts"]] == ["narrate", "localize"]


async def test_one_overlay_id_per_distinct_narration(api):
    def ask(lang):
        return api.post("/api/narrate", json={"insight_id": "I_0012", "lang": lang})

    first, again, other = [(await ask(lang)).json()["overlay"] for lang in ("en", "en", "hi")]
    assert first["overlay_id"] == again["overlay_id"] != other["overlay_id"]
    assert first["t_start_ms"] == other["t_start_ms"]  # same moment, same slot on the timeline


async def test_template_mode_answers_without_the_model(tools):
    class NoModel(StubModel):
        async def complete(self, *args, **kwargs):
            raise AssertionError("template mode must not call the model")

    app = create_app(Narrator(tools, NoModel()))
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agents.test") as api:
        body = {"insight_id": "I_0012", "lang": "hi"}
        quick = (await api.post("/api/narrate", json={**body, "mode": "template"})).json()
        jsonschema.validate(quick["overlay"], OVERLAY_SCHEMA)
        assert quick["narration"]["source"] == "template" and quick["narration"]["verified"]
        assert not quick["overlay"]["template_fallback"] and quick["narration"]["attempts"] == []
        bad = await api.post("/api/narrate", json={**body, "mode": "fast"})
        assert bad.status_code == 400


async def test_quick_and_model_lines_share_the_overlay_id(api):
    body = {"insight_id": "I_0012", "lang": "en"}
    quick = (await api.post("/api/narrate", json={**body, "mode": "template"})).json()
    full = (await api.post("/api/narrate", json=body)).json()
    assert quick["overlay"]["overlay_id"] == full["overlay"]["overlay_id"]
    assert full["narration"]["source"] == "model"


async def test_errors(api):
    bad = await api.post("/api/narrate", json={"insight_id": "8"})
    assert bad.status_code == 400
    missing = await api.post("/api/narrate", json={"insight_id": "I_0999"})
    assert missing.status_code == 404
    stale = await api.post("/api/narrate", json={"insight_id": "I_0012", "match_id": "SYN-42"})
    assert stale.status_code == 409 and "SYN-154" in stale.json()["error"]


async def test_cors_lets_the_viewer_call_it(api):
    res = await api.options(
        "/api/narrate",
        headers={"Origin": "http://127.0.0.1:5500", "Access-Control-Request-Method": "POST"},
    )
    assert res.headers["access-control-allow-origin"] in ("*", "http://127.0.0.1:5500")
    assert (await api.get("/health")).json() == {"status": "ok", "model": "stub"}
