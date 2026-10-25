"""Settings the agent service follows while it runs (ADR-019), from the core's GET /api/config.

The core merges config/app.yaml with Azure App Configuration; this module polls it (every 30 s)
and keeps the last answer. Until the first answer, or if the core is unreachable, the defaults
below apply (the same values as config/app.yaml, model deployment from SMI_MODEL_DEPLOYMENT).
"""

import asyncio
import logging
import os
from collections.abc import Mapping
from typing import Any

import httpx

log = logging.getLogger(__name__)

POLL_S = 30
STORE = "app_configuration"


def _defaults(env: Mapping[str, str]) -> dict[str, Any]:
    return {
        "narration:model_deployment": env.get("SMI_MODEL_DEPLOYMENT") or "gpt-5-mini",
        "narration:reasoning_effort": "minimal",
        "narration:max_attempts": 3,
        "access:sign_in_required": True,
        "access:cors_origins": None,  # None: the service's SMI_CORS_ORIGINS, read when asked
    }


class Settings:
    def __init__(self, env: Mapping[str, str] = os.environ) -> None:
        self.values: dict[str, Any] = _defaults(env)
        self.sources: dict[str, str] = {}

    def update(self, answer: Mapping[str, Any]) -> list[str]:
        """Takes a /api/config answer; returns the keys whose value changed. CORS origins come
        from it only when App Configuration sets them (else the service's own env applies)."""
        sources = dict(answer.get("sources", {}))
        changed = []
        for key, value in answer.get("settings", {}).items():
            if key == "access:cors_origins" and sources.get(key) != STORE:
                continue
            if self.values.get(key) != value:
                changed.append(key)
            self.values[key] = value
        self.sources = sources
        return changed

    @property
    def model_deployment(self) -> str:
        return str(self.values["narration:model_deployment"])

    @property
    def reasoning_effort(self) -> str:
        return str(self.values["narration:reasoning_effort"])

    @property
    def max_attempts(self) -> int:
        return int(self.values["narration:max_attempts"])

    @property
    def sign_in_required(self) -> bool:
        return bool(self.values["access:sign_in_required"])

    @property
    def cors_origins(self) -> list[str]:
        value = self.values["access:cors_origins"]
        raw = os.environ.get("SMI_CORS_ORIGINS", "*") if value is None else str(value)
        return [o.strip() for o in raw.split(",") if o.strip()] or ["*"]


current = Settings()


async def refresh(http: httpx.AsyncClient, settings: Settings | None = None) -> bool:
    """One poll of the core; False (and the settings unchanged) if it does not answer."""
    target = settings or current
    try:
        res = await http.get("/api/config", timeout=10)
        res.raise_for_status()
    except httpx.HTTPError as e:
        log.warning("settings: core did not answer: %s", e)
        return False
    for key in target.update(res.json()):
        log.info("setting %s = %s", key, target.values[key])
    return True


async def follow_settings(http: httpx.AsyncClient, every_s: float = POLL_S) -> None:
    while True:
        await refresh(http)
        await asyncio.sleep(every_s)
