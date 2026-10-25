"""Offline test rig: a fake core replaying recorded answers for the demo match (seed 154), the
real MCP server on top of it, and an in-memory MCP client."""

import json
from pathlib import Path

import httpx
import pytest
from mcp import Client

from smi_agents.facts import McpTools, gather
from smi_agents.mcp_server import build_server

FIXTURE = Path(__file__).parent / "fixtures" / "core-154.json"
SCHEMA = Path(__file__).resolve().parents[2] / "schema"


class FakeCore:
    """Serves the recorded responses; records posted overlays; 404 for anything unrecorded."""

    def __init__(self) -> None:
        self.responses = json.loads(FIXTURE.read_text("utf-8"))["responses"]
        self.posted: list[dict] = []
        self.requests: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        query = request.url.query.decode()
        key = f"{request.method} {request.url.path}" + (f"?{query}" if query else "")
        self.requests.append(key)
        if request.method == "POST" and request.url.path == "/api/overlays":
            self.posted.append(json.loads(request.content))
            return httpx.Response(202, json=self.posted[-1])
        if key not in self.responses:
            return httpx.Response(404, json={"error": f"not recorded: {key}"})
        r = self.responses[key]
        if isinstance(r["body"], str):
            return httpx.Response(
                r["status"], text=r["body"], headers={"content-type": "text/event-stream"}
            )
        return httpx.Response(r["status"], json=r["body"])


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def core() -> FakeCore:
    return FakeCore()


@pytest.fixture
async def http(core):
    async with httpx.AsyncClient(
        base_url="http://core.test", transport=httpx.MockTransport(core)
    ) as client:
        yield client


@pytest.fixture
async def mcp(http):
    async with Client(build_server(http)) as client:
        yield client


@pytest.fixture
def tools(mcp) -> McpTools:
    return McpTools(mcp)


@pytest.fixture
async def sheets(tools):
    """FactSheets for the demo match's key insights, by name."""
    ids = {"goal": "I_0012", "equaliser": "I_0027", "red": "I_0025", "press": "I_0024"}
    return {name: await gather(tools, iid) for name, iid in ids.items()}


def all_insight_ids(core: FakeCore) -> list[str]:
    return [
        i["insight_id"]
        for i in core.responses["GET /api/insights?min_importance=0.0"]["body"]["insights"]
    ]
