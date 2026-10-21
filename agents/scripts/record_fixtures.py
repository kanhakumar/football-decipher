"""Record the core's answers for the demo match (seed 154, synthetic) into a test fixture.

Starts a replay on a running core, waits for it to finish, then makes every call the agent tests
need through the real MCP server, recording each HTTP response. The tests replay them offline.

Usage: python scripts/record_fixtures.py [--core http://127.0.0.1:8080]
"""

import argparse
import json
import time
from pathlib import Path

import anyio
import httpx
from mcp import Client

from smi_agents.facts import McpTools, gather
from smi_agents.mcp_server import build_server

OUT = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "core-154.json"
SEED = 154


def key(request: httpx.Request) -> str:
    query = request.url.query.decode()
    return f"{request.method} {request.url.path}" + (f"?{query}" if query else "")


async def record(core: str) -> dict:
    responses: dict[str, dict] = {}

    async def keep(response: httpx.Response) -> None:
        await response.aread()
        body = (
            response.json() if "json" in response.headers.get("content-type", "") else response.text
        )
        responses[key(response.request)] = {"status": response.status_code, "body": body}

    async with httpx.AsyncClient(
        base_url=core, timeout=30, event_hooks={"response": [keep]}
    ) as http:
        start = await http.post("/api/match/start", json={"seed": SEED, "speed": 600})
        start.raise_for_status()
        while (await http.get("/api/match/state")).json()["status"] != "finished":
            time.sleep(0.5)
        async with Client(build_server(http)) as client:
            tools = McpTools(client)
            insights = (await tools.call("list_insights", {}))["insights"]
            for i in insights:
                await gather(tools, i["insight_id"])
            await tools.call("match_state", {})
            await tools.call("player_stats", {"player": "P_HOM_09"})
            await tools.call("player_workload", {"player": "P_HOM_02"})
            await tools.call("team_shape", {"team": "T_AWY", "last_minutes": 15})
            await tools.call("recent_events", {"n": 5})
            try:
                await tools.call("team_stats", {"team": "T_NOPE"})
            except Exception:
                pass
        await http.get("/api/stream/insights")
    for k in [k for k in responses if k.startswith("POST") or k.startswith("GET /api/match/")]:
        del responses[k]
    return {"seed": SEED, "responses": dict(sorted(responses.items()))}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", default="http://127.0.0.1:8080")
    data = anyio.run(record, parser.parse_args().core)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    rows = [f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in data["responses"].items()]
    # One response per line, so a re-recording diffs readably.
    body = ",\n".join(rows)
    text = f'{{"seed": {SEED}, "responses": {{\n{body}\n}}}}\n'
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {len(data['responses'])} responses to {OUT}")


if __name__ == "__main__":
    main()
