"""Per-user viewing preferences (ADR-018): persona, language, favourites, metric, delay and the
Moments filters. Kept per signed-in user (the token's `oid`), so a person gets their view on any
device. `InMemoryPreferences` by default; `CosmosPreferences` (database `fd`, container
`preferences`, partition key `/user_id`) when SMI_COSMOS_ENDPOINT is set.
"""

import hashlib
import logging
import os
from collections.abc import AsyncIterator, Mapping
from contextlib import asynccontextmanager
from typing import Any, Literal, Protocol

from pydantic import BaseModel, Field

from smi_agents.cache import DATABASE
from smi_agents.types import Lang, Persona

log = logging.getLogger(__name__)

CONTAINER = "preferences"
Group = Literal["chances", "flow", "tactics", "players"]


class Preferences(BaseModel, extra="forbid"):
    persona: Persona = "casual"
    lang: Lang = "en"
    team: str = Field(default="", pattern=r"^(T_[A-Z0-9_]{1,16})?$")
    player: str = Field(default="", pattern=r"^(P_[A-Z0-9_]{1,24})?$")
    metric: str = Field(default="", pattern=r"^([a-z][a-z0-9_]{1,40})?$")
    groups: list[Group] = Field(default_factory=lambda: ["chances", "flow", "tactics", "players"])


class PreferenceStore(Protocol):
    async def get(self, user: str) -> Preferences | None: ...

    async def put(self, user: str, prefs: Preferences) -> None: ...


def item_id(user: str) -> str:
    return hashlib.sha256(user.encode("utf-8")).hexdigest()


class InMemoryPreferences:
    def __init__(self) -> None:
        self.items: dict[str, Preferences] = {}

    async def get(self, user: str) -> Preferences | None:
        return self.items.get(user)

    async def put(self, user: str, prefs: Preferences) -> None:
        self.items[user] = prefs


class CosmosPreferences:
    def __init__(self, container: Any) -> None:
        self.container = container

    async def get(self, user: str) -> Preferences | None:
        from azure.core.exceptions import ResourceNotFoundError

        try:
            item = await self.container.read_item(item=item_id(user), partition_key=user)
        except ResourceNotFoundError:
            return None
        return Preferences.model_validate(item["preferences"])

    async def put(self, user: str, prefs: Preferences) -> None:
        item = {"id": item_id(user), "user_id": user, "preferences": prefs.model_dump()}
        await self.container.upsert_item(item)


@asynccontextmanager
async def open_preferences(
    env: Mapping[str, str] = os.environ,
) -> AsyncIterator[PreferenceStore]:
    endpoint = env.get("SMI_COSMOS_ENDPOINT")
    if not endpoint:
        yield InMemoryPreferences()
        return
    from azure.cosmos.aio import CosmosClient
    from azure.identity.aio import DefaultAzureCredential

    async with DefaultAzureCredential() as credential, CosmosClient(endpoint, credential) as client:
        container = client.get_database_client(DATABASE).get_container_client(CONTAINER)
        log.info("preferences: Cosmos DB %s/%s", DATABASE, CONTAINER)
        yield CosmosPreferences(container)
