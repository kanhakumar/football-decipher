"""Sign-in and keys (ADR-018), the same rules as the core's gate.

People send an Entra ID access token for the API (`Authorization: Bearer ...`); services send the
API key (`X-API-Key`). `SMI_AUTH=on` turns the check on; locally it is off and every caller is
`local`. `SMI_AUTH_AUDIENCE` is the API app's client id, `SMI_API_KEY` the service key. The agent
service also sends the key when it calls the core (`core_headers`).
"""

import hmac
import os
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

import jwt
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from smi_agents import settings

SCOPE = "access_as_user"
KEYS_URL = "https://login.microsoftonline.com/common/discovery/v2.0/keys"
PUBLIC = {"/health"}
LOCAL_USER = "local"
ANONYMOUS = "anonymous"  # sign-in not required (access:sign_in_required false, ADR-019)


class AuthError(Exception):
    pass


def core_headers(env: Mapping[str, str] = os.environ) -> dict[str, str]:
    """Headers for calls to the core: the API key when one is set."""
    key = env.get("SMI_API_KEY", "").strip()
    return {"X-API-Key": key} if key else {}


class EntraTokens:
    """Checks RS256 signature (identity platform keys), audience, expiry, an issuer matching the
    token's own tenant (work, school or personal accounts) and the `access_as_user` scope."""

    def __init__(self, audience: str, keys: Any = None) -> None:
        self.audience = audience
        self.keys = keys or jwt.PyJWKClient(KEYS_URL, cache_keys=True)

    def verify(self, token: str) -> str:
        try:
            key = self.keys.get_signing_key_from_jwt(token).key
            claims = jwt.decode(
                token,
                key,
                algorithms=["RS256"],
                audience=self.audience,
                options={"require": ["exp", "iss", "tid", "scp"]},
            )
        except jwt.PyJWTError as e:
            raise AuthError(f"invalid token: {e}") from None
        if claims["iss"] != f"https://login.microsoftonline.com/{claims['tid']}/v2.0":
            raise AuthError("token issuer does not match its tenant")
        if SCOPE not in str(claims["scp"]).split():
            raise AuthError(f"token lacks {SCOPE}")
        user = claims.get("oid") or claims.get("sub")
        if not user:
            raise AuthError("no user id")
        return str(user)


@dataclass(frozen=True)
class Gate:
    api_key: str | None
    tokens: EntraTokens | None

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Gate | None":
        """None when SMI_AUTH is not on."""
        if env.get("SMI_AUTH", "").strip() != "on":
            return None
        key = env.get("SMI_API_KEY", "").strip() or None
        audience = env.get("SMI_AUTH_AUDIENCE", "").strip()
        if not key and not audience:
            raise ValueError("SMI_AUTH=on needs SMI_API_KEY or SMI_AUTH_AUDIENCE")
        return cls(key, EntraTokens(audience) if audience else None)

    def caller(self, headers: Mapping[str, str]) -> str:
        """`service` for the API key, the user's id for a valid token; AuthError otherwise."""
        sent = headers.get("x-api-key")
        if sent is not None:
            if self.api_key and hmac.compare_digest(sent.encode(), self.api_key.encode()):
                return "service"
            raise AuthError("wrong API key")
        scheme, _, token = headers.get("authorization", "").partition(" ")
        if scheme.lower() == "bearer" and token:
            if self.tokens is None:
                raise AuthError("sign-in is not configured")
            return self.tokens.verify(token)
        raise AuthError("sign in, or send an API key")


class AuthMiddleware:
    """ASGI middleware: sets `request.state.user`; refuses with 401 when the gate is on and the
    caller has no valid token or key. Preflights and /health pass (CORS runs outside this)."""

    def __init__(self, app: ASGIApp, gate: Gate | None) -> None:
        self.app = app
        self.gate = gate

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request = Request(scope)
        scope.setdefault("state", {})
        if self.gate is None or request.method == "OPTIONS" or request.url.path in PUBLIC:
            scope["state"]["user"] = LOCAL_USER
            await self.app(scope, receive, send)
            return
        try:
            scope["state"]["user"] = self.gate.caller(request.headers)
        except AuthError as e:
            if not settings.current.sign_in_required:
                scope["state"]["user"] = ANONYMOUS
                await self.app(scope, receive, send)
                return
            response = JSONResponse(
                {"error": str(e)}, status_code=401, headers={"WWW-Authenticate": "Bearer"}
            )
            await response(scope, receive, send)
            return
        await self.app(scope, receive, send)
