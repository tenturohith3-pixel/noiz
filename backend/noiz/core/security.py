"""Auth primitives and stream URL signing (§9.4, §12).

Three separate secrets with three separate jobs:
  * Argon2id password hashes at rest (never reversible);
  * HS256 JWTs for the API (`Authorization: Bearer`), short-lived, refresh-rotated;
  * an HMAC stream signature that nginx can verify with a 2 ms `auth_request` probe,
    so the byte path never touches the database.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import time
from dataclasses import dataclass
from typing import Any, Final

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError

from noiz.core.settings import Settings

# ADR 14 / §12: m=64 MiB, t=3, p=2.
_password_hasher: Final = PasswordHasher(time_cost=3, memory_cost=64 * 1024, parallelism=2)

ACCESS_TOKEN_TYPE: Final = "access"
REFRESH_TOKEN_TYPE: Final = "refresh"


class AuthError(Exception):
    """Raised for any credential failure; mapped to a 401 problem+json by the app."""

    def __init__(self, detail: str = "Invalid or expired credentials") -> None:
        super().__init__(detail)
        self.detail = detail


# ---------------------------------------------------------------------------
# Passwords
# ---------------------------------------------------------------------------


def hash_password(password: str) -> str:
    return _password_hasher.hash(password)


def verify_password(password_hash: str, password: str) -> bool:
    try:
        return _password_hasher.verify(password_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


def needs_rehash(password_hash: str) -> bool:
    """True when the stored hash predates a cost-parameter bump."""
    try:
        return _password_hasher.check_needs_rehash(password_hash)
    except InvalidHashError:
        return True


# ---------------------------------------------------------------------------
# JWT
# ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class Principal:
    user_id: str
    device_id: str
    role: str
    can_upload: bool
    can_download: bool


def create_access_token(settings: Settings, principal: Principal) -> tuple[str, int]:
    now = int(time.time())
    expires_in = settings.access_token_ttl_seconds
    payload: dict[str, Any] = {
        "sub": principal.user_id,
        "did": principal.device_id,
        "role": principal.role,
        "up": principal.can_upload,
        "dl": principal.can_download,
        "typ": ACCESS_TOKEN_TYPE,
        "iat": now,
        "exp": now + expires_in,
        # `jti` gives the refresh flow something to correlate against.
        "jti": base64.urlsafe_b64encode(hashlib.sha256(f"{principal.user_id}{now}".encode()).digest())
        .decode()
        .rstrip("="),
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm), expires_in


def create_refresh_token(settings: Settings, user_id: str, device_id: str) -> str:
    now = int(time.time())
    payload = {
        "sub": user_id,
        "did": device_id,
        "typ": REFRESH_TOKEN_TYPE,
        "iat": now,
        "exp": now + settings.refresh_token_ttl_seconds,
    }
    return jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)


def decode_token(settings: Settings, token: str, expected_type: str) -> dict[str, Any]:
    try:
        payload = jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except jwt.PyJWTError as exc:  # expired, bad signature, malformed
        raise AuthError(str(exc)) from exc

    if payload.get("typ") != expected_type:
        raise AuthError(f"expected a {expected_type} token")
    return payload


def refresh_token_digest(token: str) -> str:
    """Refresh tokens are stored hashed; a leak of the table cannot mint sessions."""
    return hashlib.sha256(token.encode()).hexdigest()


def principal_from_access_token(settings: Settings, token: str) -> Principal:
    payload = decode_token(settings, token, ACCESS_TOKEN_TYPE)
    return Principal(
        user_id=payload["sub"],
        device_id=payload.get("did", ""),
        role=payload.get("role", "member"),
        can_upload=bool(payload.get("up", False)),
        can_download=bool(payload.get("dl", True)),
    )


# ---------------------------------------------------------------------------
# Stream URL signing (§9.4)
# ---------------------------------------------------------------------------


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def sign_stream_url(
    settings: Settings,
    *,
    user_id: str,
    track_id: str,
    rendition: str,
    ttl_seconds: int | None = None,
    now: int | None = None,
) -> tuple[str, int]:
    """Returns `(signature, expires_at_epoch_seconds)`.

    sig = base64url(HMAC-SHA256(secret, "{user_id}|{track_id}|{rendition}|{exp}"))
    """
    issued = now if now is not None else int(time.time())
    exp = issued + (ttl_seconds if ttl_seconds is not None else settings.stream_url_ttl_seconds)
    message = f"{user_id}|{track_id}|{rendition}|{exp}".encode()
    signature = _b64url(
        hmac.new(settings.stream_hmac_secret.encode(), message, hashlib.sha256).digest(),
    )
    return signature, exp


def verify_stream_signature(
    settings: Settings,
    *,
    user_id: str,
    track_id: str,
    rendition: str,
    exp: int,
    signature: str,
    now: int | None = None,
) -> bool:
    """Constant-time check used by both nginx's auth_request probe and the dev path."""
    current = now if now is not None else int(time.time())
    if exp < current:
        return False

    expected, _ = sign_stream_url(
        settings,
        user_id=user_id,
        track_id=track_id,
        rendition=rendition,
        ttl_seconds=0,
        now=exp,
    )
    return hmac.compare_digest(expected, signature)


def behaviour_token(settings: Settings, *, endpoint: str, now: int | None = None) -> str:
    """Short-lived token used by the nginx `auth_request` target (§9.4).

    Scoped to one endpoint so a leaked token cannot be replayed against another path.
    """
    issued = now if now is not None else int(time.time())
    exp = issued + 60
    message = f"{endpoint}|{exp}".encode()
    return f"{exp}:{_b64url(hmac.new(settings.stream_hmac_secret.encode(), message, hashlib.sha256).digest())}"
