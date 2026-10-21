"""Agent service commands.

  python -m smi_agents narrate --insight I_0001 [--persona casual] [--langs en,hi,es]
  python -m smi_agents follow [--min-importance 0.6] [--persona casual] [--lang en] [--publish]

Both reach the core only through the MCP server (started over stdio, or --mcp-url for a running
one); `follow` also reads the insight stream and posts overlays to the core API.
"""

import argparse
import json
import logging
import os
import sys
from contextlib import asynccontextmanager
from typing import Any

import anyio
import httpx
from mcp import Client, StdioServerParameters

from smi_agents import overlay
from smi_agents.facts import McpTools, ToolError
from smi_agents.follow import follow, insight_stream
from smi_agents.model import Model, StubModel
from smi_agents.types import LANGS, NarrationRequest
from smi_agents.workflow import Narration, Narrator

log = logging.getLogger("smi_agents")


def model_for(name: str) -> Model:
    if name == "stub":
        return StubModel()
    raise SystemExit(
        f"model '{name}' is not configured yet (only 'stub'; Foundry comes with Azure)"
    )


@asynccontextmanager
async def mcp_client(core_url: str, mcp_url: str | None):
    if mcp_url:
        target: Any = mcp_url
    else:
        env = {**os.environ, "SMI_CORE_URL": core_url}
        target = StdioServerParameters(
            command=sys.executable, args=["-m", "smi_agents.mcp_server"], env=env
        )
    async with Client(target) as client:
        yield client


def summary(n: Narration) -> str:
    tries = ", ".join(f"{a.stage}#{a.attempt} {'ok' if a.ok else a.reasons}" for a in n.trail)
    source = "template" if n.template_fallback else n.model
    return f"{n.request.lang} {n.request.persona}: verified={n.verified} via {source} [{tries}]"


async def narrate_cmd(args: argparse.Namespace) -> None:
    langs = [lang.strip() for lang in args.langs.split(",")]
    async with mcp_client(args.core, args.mcp_url) as client:
        narrator = Narrator(McpTools(client), model_for(args.model))
        for i, lang in enumerate(langs, start=1):
            req = NarrationRequest(
                insight_id=args.insight,
                persona=args.persona,
                lang=lang,
                favourite_team=args.team,
                favourite_player=args.player,
                metric_focus=args.metric,
            )
            n = await narrator.narrate(req)
            print(summary(n), file=sys.stderr)
            print(json.dumps(overlay.show(n, overlay.overlay_id(i)), ensure_ascii=False, indent=2))


async def follow_cmd(args: argparse.Namespace) -> None:
    async with (
        httpx.AsyncClient(base_url=args.core, timeout=30) as http,
        mcp_client(args.core, args.mcp_url) as client,
    ):

        async def post(o: dict[str, Any]) -> None:
            if not args.publish:
                print(json.dumps(o, ensure_ascii=False))
                return
            res = await http.post("/api/overlays", json=o)
            log.info("%s %s -> %s", o["op"], o["overlay_id"], res.status_code)

        narrator = Narrator(McpTools(client), model_for(args.model))
        count = await follow(
            insight_stream(http),
            narrator,
            post,
            persona=args.persona,
            lang=args.lang,
            min_importance=args.min_importance,
        )
        log.info("match over: %d overlays", count)


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="python -m smi_agents", description=__doc__.splitlines()[0]
    )
    parser.add_argument("--core", default=os.environ.get("SMI_CORE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--mcp-url", help="a running MCP server (default: start one over stdio)")
    parser.add_argument("--model", default="stub")
    sub = parser.add_subparsers(dest="command", required=True)

    n = sub.add_parser("narrate", help="narrate one insight and print the overlays")
    n.add_argument("--insight", required=True)
    n.add_argument("--persona", default="casual")
    n.add_argument("--langs", default=",".join(LANGS))
    n.add_argument("--team", help="favourite team id (club_fan)")
    n.add_argument("--player", help="favourite player id (player_focus)")
    n.add_argument("--metric", help="focus metric (metric_focus)")

    f = sub.add_parser("follow", help="narrate insights as the core publishes them")
    f.add_argument("--min-importance", type=float, default=0.6)
    f.add_argument("--persona", default="casual")
    f.add_argument("--lang", default="en")
    f.add_argument("--publish", action="store_true", help="post overlays to the core")

    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    for noisy in ("httpx", "agent_framework"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    logging.getLogger("mcp").setLevel(logging.ERROR)  # tool failures are reported below
    try:
        anyio.run(narrate_cmd if args.command == "narrate" else follow_cmd, args)
    except* (ToolError, httpx.ConnectError) as group:
        for e in _leaves(group):
            print(f"error: {e}", file=sys.stderr)
            if "unknown insight" in str(e):
                print(
                    "hint: the core publishes insights as the live side reaches them; check "
                    f"{args.core}/api/insights and try again",
                    file=sys.stderr,
                )
            elif isinstance(e, httpx.ConnectError) or "core unreachable" in str(e):
                print(f"hint: is the core running at {args.core}? (`sbt run`)", file=sys.stderr)
        sys.exit(1)


def _leaves(e: BaseException) -> list[BaseException]:
    """The actual errors inside (possibly nested) exception groups from the task groups."""
    if isinstance(e, BaseExceptionGroup):
        return [leaf for sub in e.exceptions for leaf in _leaves(sub)]
    return [e]


if __name__ == "__main__":
    main()
