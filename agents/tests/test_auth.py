import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from smi_agents.auth import AuthError, EntraTokens, Gate, core_headers
from smi_agents.model import StubModel
from smi_agents.preferences import InMemoryPreferences
from smi_agents.service import create_app
from smi_agents.workflow import Narrator

pytestmark = pytest.mark.anyio

AUDIENCE = "11111111-2222-3333-4444-555555555555"
TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER = rsa.generate_private_key(public_exponent=65537, key_size=2048)


class LocalKeys:
    """Stands in for the identity platform's key set: always the test key's public half."""

    class _Key:
        key = KEY.public_key()

    def get_signing_key_from_jwt(self, token: str) -> "_Key":
        return self._Key()


def token(
    signer=KEY, aud=AUDIENCE, issuer_tenant=TENANT, scope="access_as_user", ttl=600, oid="u1"
):
    claims = {"aud": aud, "iss": f"https://login.microsoftonline.com/{issuer_tenant}/v2.0",
              "tid": TENANT, "scp": scope, "oid": oid, "exp": int(time.time()) + ttl}  # fmt: skip
    return jwt.encode(claims, signer, algorithm="RS256", headers={"kid": "test-key"})


GATE = Gate("secret-key", EntraTokens(AUDIENCE, keys=LocalKeys()))


def test_a_valid_token_names_the_user_and_the_key_names_a_service():
    assert GATE.caller({"authorization": f"Bearer {token()}"}) == "u1"
    assert GATE.caller({"x-api-key": "secret-key"}) == "service"


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"x-api-key": "nope"},
        {"authorization": f"Bearer {token(ttl=-60)}"},
        {"authorization": f"Bearer {token(aud='someone-else')}"},
        {"authorization": f"Bearer {token(signer=OTHER)}"},
        {"authorization": f"Bearer {token(scope='User.Read')}"},
        {"authorization": f"Bearer {token(issuer_tenant='ffffffff-0000-0000-0000-000000000000')}"},
        {"authorization": "Bearer not-a-jwt"},
    ],
)
def test_bad_credentials_are_refused(headers):
    with pytest.raises(AuthError):
        GATE.caller(headers)


def test_the_gate_is_off_unless_asked_and_the_core_gets_the_key():
    assert Gate.from_env({}) is None
    with pytest.raises(ValueError):
        Gate.from_env({"SMI_AUTH": "on"})
    assert Gate.from_env({"SMI_AUTH": "on", "SMI_API_KEY": "k"}).api_key == "k"
    assert core_headers({"SMI_API_KEY": "k"}) == {"X-API-Key": "k"}
    assert core_headers({}) == {}


@pytest.fixture
async def api(tools):
    app = create_app(Narrator(tools, StubModel()), gate=GATE, preferences=InMemoryPreferences())
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://agents.test") as client:
        yield client


def as_user(oid: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token(oid=oid)}"}


async def test_the_service_refuses_strangers_but_not_health_or_preflights(api):
    assert (await api.get("/health")).status_code == 200
    stranger = await api.post("/api/narrate", json={"insight_id": "I_0012"})
    assert stranger.status_code == 401 and stranger.headers["www-authenticate"] == "Bearer"
    preflight = await api.options(
        "/api/narrate",
        headers={
            "Origin": "http://127.0.0.1:5500",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization",
        },  # fmt: skip
    )
    assert preflight.status_code == 200
    ok = await api.post("/api/narrate", json={"insight_id": "I_0012"}, headers=as_user("u1"))
    assert ok.status_code == 200


async def test_preferences_are_per_user(api):
    fresh = (await api.get("/api/me/preferences", headers=as_user("u1"))).json()
    assert fresh["persona"] == "casual" and not fresh["saved"]
    prefs = {"persona": "analyst", "lang": "hi", "team": "T_HOM", "player": "", "metric": "xg",
             "groups": ["chances", "tactics"]}  # fmt: skip
    saved = await api.put("/api/me/preferences", json=prefs, headers=as_user("u1"))
    assert saved.status_code == 200 and saved.json()["saved"]
    mine = (await api.get("/api/me/preferences", headers=as_user("u1"))).json()
    theirs = (await api.get("/api/me/preferences", headers=as_user("u2"))).json()
    assert (mine["persona"], mine["groups"]) == ("analyst", ["chances", "tactics"])
    assert theirs["persona"] == "casual"


async def test_bad_preferences_and_services_cannot_save(api):
    bad = await api.put("/api/me/preferences", json={"persona": "pundit"}, headers=as_user("u1"))
    assert bad.status_code == 400
    service = await api.put("/api/me/preferences", json={}, headers={"X-API-Key": "secret-key"})
    assert service.status_code == 403
