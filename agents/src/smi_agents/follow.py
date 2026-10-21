"""Follows the core's insight stream and narrates moment-path insights as they arrive (D4).

Each insight at or above `min_importance` becomes one overlay for the default persona and language.
When an insight is retracted or replaced, the overlays shown for it are retracted too.
"""

import json
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from typing import Any

import httpx

from smi_agents import overlay
from smi_agents.types import Lang, NarrationRequest, Persona
from smi_agents.workflow import Narrator

log = logging.getLogger("smi_agents.follow")
Post = Callable[[dict[str, Any]], Awaitable[None]]


async def insight_stream(http: httpx.AsyncClient) -> AsyncIterator[dict[str, Any]]:
    """The core's insight stream (SSE): every insight version, until the match ends."""
    async with http.stream("GET", "/api/stream/insights", timeout=None) as res:
        res.raise_for_status()
        async for line in res.aiter_lines():
            if line.startswith("data:"):
                yield json.loads(line[5:])


async def follow(
    insights: AsyncIterator[dict[str, Any]],
    narrator: Narrator,
    post: Post,
    *,
    persona: Persona = "casual",
    lang: Lang = "en",
    min_importance: float = 0.6,
) -> int:
    """Narrates and posts; returns how many overlays were shown."""
    shown: dict[str, list[dict[str, Any]]] = {}
    next_number = 1
    async for insight in insights:
        iid = insight["insight_id"]
        retracted = insight.get("status") == "retracted"
        stale = ([iid] if retracted else []) + (
            [insight["supersedes"]] if insight.get("supersedes") else []
        )
        for old in stale:
            for o in shown.pop(old, []):
                await post(overlay.retract(o))
        if retracted or insight["importance"] < min_importance:
            continue
        n = await narrator.narrate(NarrationRequest(insight_id=iid, persona=persona, lang=lang))
        o = overlay.show(n, overlay.overlay_id(next_number))
        next_number += 1
        log.info(
            "%s %s -> %s (verified=%s, template=%s)",
            iid,
            insight["kind"],
            o["overlay_id"],
            n.verified,
            n.template_fallback,
        )
        shown.setdefault(iid, []).append(o)
        await post(o)
    return next_number - 1
