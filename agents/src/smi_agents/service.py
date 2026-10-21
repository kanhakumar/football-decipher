"""The agent service's HTTP API: on-demand narration for viewers (D3, D4).

  POST /api/narrate  NarrationRequest JSON -> {"overlay": ..., "narration": {...}}
  GET  /health

A viewer asks for each moment in its own persona and language; identical requests share one
workflow run and the cache. The service reaches the core only through its MCP server, here in
process (an in-memory MCP session over the same tools).

Run: python -m smi_agents serve [--host 127.0.0.1] [--port 8090]
"""

import itertools
import os
import time
from collections.abc import Mapping
from typing import Any

from pydantic import ValidationError
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from smi_agents import overlay
from smi_agents.facts import StaleRequest, ToolError
from smi_agents.types import NarrationRequest
from smi_agents.workflow import Narrator


def _tool_error(e: BaseException) -> ToolError | None:
    if isinstance(e, ToolError):
        return e
    if isinstance(e, BaseExceptionGroup):
        return next((t for sub in e.exceptions if (t := _tool_error(sub))), None)
    return None


def cors_origins(env: Mapping[str, str] = os.environ) -> list[str]:
    """SMI_CORS_ORIGINS, comma-separated: `*` locally, the viewer's origin when deployed."""
    raw = env.get("SMI_CORS_ORIGINS", "*")
    return [o.strip() for o in raw.split(",") if o.strip()] or ["*"]


def create_app(narrator: Narrator, origins: list[str] | None = None) -> Starlette:
    numbers = itertools.count(1)
    ids: dict[tuple, str] = {}  # one overlay id per distinct narration

    async def health(_: Request) -> JSONResponse:
        return JSONResponse({"status": "ok", "model": narrator.model.name})

    async def narrate(request: Request) -> JSONResponse:
        try:
            req = NarrationRequest.model_validate(await request.json())
        except (ValueError, ValidationError) as e:
            return JSONResponse({"error": f"invalid request: {e}"}, status_code=400)
        started = time.perf_counter()
        try:
            n = await narrator.narrate(req)
        except Exception as e:  # tool failures may arrive wrapped in task-group exception groups
            error = _tool_error(e)
            if error is None:
                raise
            if isinstance(error, StaleRequest):
                status = 409
            else:
                status = 404 if "answered 404" in str(error) else 502
            return JSONResponse({"error": str(error)}, status_code=status)
        latency_ms = round((time.perf_counter() - started) * 1000, 1)
        key = tuple(req.model_dump().values())
        oid = ids.setdefault(key, overlay.overlay_id(next(numbers)))
        body: dict[str, Any] = {
            "overlay": overlay.show(n, oid),
            "narration": {
                "verified": n.verified,
                "template_fallback": n.template_fallback,
                "model": n.model,
                "latency_ms": latency_ms,
                "attempts": [
                    {"stage": a.stage, "attempt": a.attempt, "ok": a.ok, "reasons": a.reasons}
                    for a in n.trail
                ],
            },
        }
        return JSONResponse(body)

    return Starlette(
        routes=[Route("/health", health), Route("/api/narrate", narrate, methods=["POST"])],
        # The viewer is a static page on another origin.
        middleware=[
            Middleware(
                CORSMiddleware,
                allow_origins=cors_origins() if origins is None else origins,
                allow_methods=["*"],
                allow_headers=["*"],
            )
        ],
    )
