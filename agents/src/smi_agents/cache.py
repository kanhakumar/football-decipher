"""Narration cache (D3): one verified line per request, shared by every viewer who asks for it.

`InMemoryCache` is the default (one process). `CosmosCache` keeps lines in Cosmos DB (database
`smi`, container `narrations`, partition key `/insight_id`) so replicas and restarts share them;
`open_cache` picks it when SMI_COSMOS_ENDPOINT is set and signs in with `DefaultAzureCredential`.
"""

import hashlib
import json
import logging
import os
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from dataclasses import asdict
from typing import TYPE_CHECKING, Any, Protocol

from smi_agents.types import FactSheet, Line, NarrationRequest

if TYPE_CHECKING:
    from smi_agents.workflow import Narration

log = logging.getLogger(__name__)

DATABASE = "fd"
CONTAINER = "narrations"


class NarrationCache(Protocol):
    async def get(self, req: NarrationRequest) -> "Narration | None": ...

    async def put(self, n: "Narration") -> None: ...


def request_key(req: NarrationRequest) -> str:
    return json.dumps(req.model_dump(mode="json"), sort_keys=True, separators=(",", ":"))


def item_id(req: NarrationRequest) -> str:
    """Cosmos ids may not contain / \\ ? #; a digest of the request key never does."""
    return hashlib.sha256(request_key(req).encode("utf-8")).hexdigest()


def to_doc(n: "Narration") -> dict[str, Any]:
    return {
        "request": n.request.model_dump(mode="json"),
        "sheet": n.sheet.model_dump(mode="json"),
        "line": n.line.model_dump(mode="json"),
        "verified": n.verified,
        "template_fallback": n.template_fallback,
        "trail": [asdict(a) for a in n.trail],
        "model": n.model,
    }


def from_doc(doc: Mapping[str, Any]) -> "Narration":
    from smi_agents.workflow import Attempt, Narration

    return Narration(
        request=NarrationRequest.model_validate(doc["request"]),
        sheet=FactSheet.model_validate(doc["sheet"]),
        line=Line.model_validate(doc["line"]),
        verified=doc["verified"],
        template_fallback=doc["template_fallback"],
        trail=[Attempt(**a) for a in doc["trail"]],
        model=doc["model"],
    )


class InMemoryCache:
    def __init__(self) -> None:
        self.items: dict[str, Narration] = {}

    async def get(self, req: NarrationRequest) -> "Narration | None":
        return self.items.get(request_key(req))

    async def put(self, n: "Narration") -> None:
        self.items[request_key(n.request)] = n


class CosmosCache:
    """Over an async Cosmos container client. A Cosmos failure costs a cache miss, not a line."""

    def __init__(self, container: Any) -> None:
        self.container = container

    async def get(self, req: NarrationRequest) -> "Narration | None":
        from azure.core.exceptions import AzureError, ResourceNotFoundError

        try:
            item = await self.container.read_item(item=item_id(req), partition_key=req.insight_id)
        except ResourceNotFoundError:
            return None
        except AzureError as e:
            log.warning("narration cache read failed: %s", e)
            return None
        return from_doc(item["narration"])

    async def put(self, n: "Narration") -> None:
        from azure.core.exceptions import AzureError

        item = {
            "id": item_id(n.request),
            "insight_id": n.request.insight_id,
            "key": request_key(n.request),
            "narration": to_doc(n),
        }
        try:
            await self.container.upsert_item(item)
        except AzureError as e:
            log.warning("narration cache write failed: %s", e)


@asynccontextmanager
async def open_cache(env: Mapping[str, str] = os.environ) -> AsyncIterator[NarrationCache]:
    endpoint = env.get("SMI_COSMOS_ENDPOINT")
    if not endpoint:
        yield InMemoryCache()
        return
    from azure.cosmos.aio import CosmosClient
    from azure.identity.aio import DefaultAzureCredential

    async with DefaultAzureCredential() as credential, CosmosClient(endpoint, credential) as client:
        container = client.get_database_client(DATABASE).get_container_client(CONTAINER)
        log.info("narration cache: Cosmos DB %s/%s", DATABASE, CONTAINER)
        yield CosmosCache(container)
