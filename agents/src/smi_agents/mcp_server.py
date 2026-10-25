"""Thin MCP server over the core's tool endpoints (rule 4, D9: agents reach data only via these).

Each tool forwards to one core endpoint and returns its JSON unchanged. Answers are small and
capped by the core; nothing here reads bulk events or frames.

Run: python -m smi_agents.mcp_server            (stdio)
     python -m smi_agents.mcp_server --http 8090  (streamable HTTP at /mcp)
The core URL comes from SMI_CORE_URL (default http://127.0.0.1:8080).
"""

import argparse
import logging
import os
from typing import Annotated, Any

import anyio
import httpx
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from pydantic import Field

from smi_agents.auth import core_headers

CORE_URL = os.environ.get("SMI_CORE_URL", "http://127.0.0.1:8080")

Json = dict[str, Any]
TeamId = Annotated[str, Field(description="Team id, e.g. T_HOM", pattern=r"^T_[A-Z0-9_]{1,16}$")]
PlayerId = Annotated[
    str, Field(description="Player id, e.g. P_HOM_09", pattern=r"^P_[A-Z0-9_]{1,24}$")
]
Period = Annotated[int | None, Field(description="Period (1 or 2; 3-5 extra time)", ge=1, le=5)]
FromMinute = Annotated[
    float | None, Field(description="Window start, display minute (second half starts at 45)")
]
ToMinute = Annotated[float | None, Field(description="Window end, display minute")]
LastMinutes = Annotated[
    float | None, Field(description="The last N minutes of the current period (overrides from/to)")
]
Metrics = Annotated[
    list[str] | None, Field(description="Metric ids, e.g. goals, xg, ppda (default set if omitted)")
]


def build_server(http: httpx.AsyncClient) -> MCPServer:
    """The MCP server, calling the core through `http` (whose base_url is the core)."""
    server = MCPServer(
        "smi-core",
        instructions=(
            "Facts about the current synthetic match, computed by the deterministic core. "
            "Every number carries a basis tag; explain only what these tools return."
        ),
    )

    async def get(path: str, **params: Any) -> Json:
        query = {
            k: (",".join(v) if isinstance(v, list) else v)
            for k, v in params.items()
            if v is not None
        }
        try:
            res = await http.get(path, params=query)
        except httpx.HTTPError as e:
            raise ToolError(f"core unreachable: {e}") from e
        if res.status_code >= 400:
            try:
                reason = res.json().get("error", res.text)
            except ValueError:
                reason = res.text
            raise ToolError(f"core answered {res.status_code}: {reason}")
        return res.json()

    def window(period, from_minute, to_minute, last_minutes) -> Json:
        return {
            "period": period,
            "from_minute": from_minute,
            "to_minute": to_minute,
            "last_minutes": last_minutes,
        }

    @server.tool()
    async def match_state() -> Json:
        """Playback status, live and viewer clocks, score, possession and players on the pitch."""
        return await get("/api/tools/match_state")

    @server.tool()
    async def match_info() -> Json:
        """Team names and squads (player id, name, shirt, position)."""
        return await get("/api/tools/match_info")

    @server.tool()
    async def team_stats(
        team: TeamId,
        metrics: Metrics = None,
        period: Period = None,
        from_minute: FromMinute = None,
        to_minute: ToMinute = None,
        last_minutes: LastMinutes = None,
    ) -> Json:
        """Team metrics over a window, each with value, unit, basis and capability level."""
        w = window(period, from_minute, to_minute, last_minutes)
        return await get("/api/tools/team_stats", team=team, metrics=metrics, **w)

    @server.tool()
    async def player_stats(
        player: PlayerId,
        metrics: Metrics = None,
        period: Period = None,
        from_minute: FromMinute = None,
        to_minute: ToMinute = None,
        last_minutes: LastMinutes = None,
    ) -> Json:
        """Player metrics over a window, each with value, unit, basis and capability level."""
        w = window(period, from_minute, to_minute, last_minutes)
        return await get("/api/tools/player_stats", player=player, metrics=metrics, **w)

    @server.tool()
    async def player_workload(
        player: PlayerId,
        period: Period = None,
        from_minute: FromMinute = None,
        to_minute: ToMinute = None,
        last_minutes: LastMinutes = None,
    ) -> Json:
        """Distance, high-speed distance, top speed, sprints and fatigue (tracking or estimate)."""
        w = window(period, from_minute, to_minute, last_minutes)
        return await get("/api/tools/player_workload", player=player, **w)

    @server.tool()
    async def team_shape(
        team: TeamId,
        period: Period = None,
        from_minute: FromMinute = None,
        to_minute: ToMinute = None,
        last_minutes: LastMinutes = None,
    ) -> Json:
        """Average player positions (own team frame, metres) over a window."""
        w = window(period, from_minute, to_minute, last_minutes)
        return await get("/api/tools/team_shape", team=team, **w)

    @server.tool()
    async def recent_events(
        n: Annotated[int, Field(description="How many (at most 50)", ge=1, le=50)] = 10,
        team: TeamId | None = None,
    ) -> Json:
        """The latest events, compact (id, minute, type, team, player, outcome, location)."""
        return await get("/api/tools/recent_events", n=n, team=team)

    @server.tool()
    async def list_insights(
        min_importance: Annotated[float, Field(ge=0, le=1)] = 0.0,
        kind: Annotated[str | None, Field(description="Insight kind, e.g. goal")] = None,
    ) -> Json:
        """Active insights, best first (facts and reason codes, no prose)."""
        return await get("/api/insights", min_importance=min_importance, kind=kind)

    @server.tool()
    async def get_insight(
        insight_id: Annotated[str, Field(pattern=r"^I_[0-9]{1,6}$")],
    ) -> Json:
        """One insight (latest version) with a summary of its evidence (at most 20 events)."""
        return await get(f"/api/tools/insight/{insight_id}")

    return server


async def serve(http_port: int | None) -> None:
    async with httpx.AsyncClient(base_url=CORE_URL, timeout=10, headers=core_headers()) as http:
        server = build_server(http)
        if http_port is None:
            await server.run_stdio_async()
        else:
            await server.run_streamable_http_async(host="127.0.0.1", port=http_port)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--http", type=int, metavar="PORT", help="serve streamable HTTP")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # one line per core call is noise
    logging.getLogger("mcp").setLevel(logging.ERROR)  # tool errors go back to the client anyway
    anyio.run(serve, parser.parse_args().http)


if __name__ == "__main__":
    main()
