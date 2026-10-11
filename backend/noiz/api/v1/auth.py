"""Authentication routes (§9.2, ADR 14).

Refresh tokens rotate on every use and are stored as digests. Presenting a digest that
was already rotated away is treated as theft: the whole device is revoked, which is
what makes a stolen refresh token useless after one refresh.
"""

from __future__ import annotations

import secrets
from datetime import datetime, timezone
from uuid import UUID

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from noiz.api.deps import DbConn, PrincipalDep, SettingsDep
from noiz.core import security

router = APIRouter(tags=["auth"])


def _dummy_hash() -> str:
    """A real Argon2id hash of an unguessable value, computed once.

    Verifying against it when the username is unknown costs the same ~100 ms as a real
    check, which is what closes the account-enumeration timing oracle.
    """
    global _DUMMY_PASSWORD_HASH
    if _DUMMY_PASSWORD_HASH is None:
        _DUMMY_PASSWORD_HASH = security.hash_password(secrets.token_urlsafe(32))
    return _DUMMY_PASSWORD_HASH


_DUMMY_PASSWORD_HASH: str | None = None


class DeviceInfo(BaseModel):
    # Must be a canonical UUID: it becomes the `devices.id` primary key and is echoed
    # back inside the refresh token's `did` claim. A non-UUID value previously made
    # login "succeed" while writing a row under a *different* generated id, so the very
    # next refresh could never find it — a silent, permanent auth deadlock.
    id: UUID
    name: str = Field(min_length=1, max_length=128)
    platform: str = Field(min_length=1, max_length=32)
    app_version: str | None = Field(default=None, max_length=32)


class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=1024)
    device: DeviceInfo


class RefreshRequest(BaseModel):
    refresh_token: str


class UserPayload(BaseModel):
    id: str
    username: str
    display_name: str
    role: str
    can_upload: bool
    can_download: bool


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    expires_in: int
    user: UserPayload


@router.post("/auth/login", response_model=TokenResponse)
async def login(body: LoginRequest, conn: DbConn, settings: SettingsDep) -> TokenResponse:
    user = await conn.fetchrow(
        """
        SELECT id, username, display_name, role, can_upload, can_download, password_hash
        FROM users
        WHERE username = $1
        """,
        # Usernames are matched exactly but entered inconsistently; normalise on the way
        # in so " Bob " and "bob" are the same account.
        body.username.strip(),
    )

    # Same response for unknown user and wrong password: no account enumeration.
    #
    # The `or` short-circuits, so an unknown username returned in <5 ms while a known
    # one cost a full Argon2id derivation (~100 ms) — a timing oracle that defeats the
    # stated intent and is the first stage of credential stuffing. Always run the
    # KDF, against a dummy hash when the user does not exist.
    password_ok = security.verify_password(
        user["password_hash"] if user is not None else _DUMMY_PASSWORD_HASH,
        body.password,
    )
    if user is None or not password_ok:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if security.needs_rehash(user["password_hash"]):
        await conn.execute(
            "UPDATE users SET password_hash = $2 WHERE id = $1",
            user["id"],
            security.hash_password(body.password),
        )

    principal = security.Principal(
        user_id=str(user["id"]),
        device_id=str(body.device.id),
        role=user["role"],
        can_upload=user["can_upload"],
        can_download=user["can_download"],
    )
    access_token, expires_in = security.create_access_token(settings, principal)
    refresh_token = security.create_refresh_token(settings, principal.user_id, str(body.device.id))

    # A device id is client-supplied, so an attacker can name any existing device. The
    # old ON CONFLICT SET list omitted `user_id`, so logging in as user B with user A's
    # device id left the row owned by A but stamped with B's refresh hash — and refresh
    # rebuilt the principal from the *row*, handing B A's identity and admin role.
    # Bind the row to the authenticated user and refuse a cross-account collision.
    conflict = await conn.fetchrow(
        """
        UPDATE devices
        SET name = $2,
            platform = $3,
            app_version = $4,
            last_seen_at = now(),
            refresh_token_hash = $5,
            refresh_expires_at = now() + ($6 || ' seconds')::interval
        WHERE id = $1 AND user_id = $7
        RETURNING id
        """,
        body.device.id,
        body.device.name,
        body.device.platform,
        body.device.app_version,
        security.refresh_token_digest(refresh_token),
        str(settings.refresh_token_ttl_seconds),
        user["id"],
    )

    if conflict is None:
        existing_owner = await conn.fetchval(
            "SELECT user_id FROM devices WHERE id = $1",
            body.device.id,
        )
        if existing_owner is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This device is already registered to a different account",
            )
        await conn.execute(
            """
            INSERT INTO devices (id, user_id, name, platform, app_version, last_seen_at,
                                 refresh_token_hash, refresh_expires_at)
            VALUES ($1, $2, $3, $4, $5, now(), $6, now() + ($7 || ' seconds')::interval)
            """,
            body.device.id,
            user["id"],
            body.device.name,
            body.device.platform,
            body.device.app_version,
            security.refresh_token_digest(refresh_token),
            str(settings.refresh_token_ttl_seconds),
        )

    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=expires_in,
        user=UserPayload(
            id=str(user["id"]),
            username=user["username"],
            display_name=user["display_name"],
            role=user["role"],
            can_upload=user["can_upload"],
            can_download=user["can_download"],
        ),
    )


@router.post("/auth/refresh", response_model=TokenResponse)
async def refresh(body: RefreshRequest, conn: DbConn, settings: SettingsDep) -> TokenResponse:
    try:
        payload = security.decode_token(settings, body.refresh_token, security.REFRESH_TOKEN_TYPE)
    except security.AuthError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=exc.detail,
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc

    device_id = payload.get("did", "")
    subject = payload.get("sub", "")
    try:
        device_uuid = UUID(device_id)
    except (ValueError, AttributeError, TypeError):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token is malformed",
            headers={"WWW-Authenticate": "Bearer"},
        ) from None

    device = await conn.fetchrow(
        """
        SELECT d.id, d.user_id, d.refresh_token_hash, d.refresh_expires_at,
               u.username, u.display_name, u.role, u.can_upload, u.can_download
        FROM devices d
        JOIN users u ON u.id = d.user_id
        WHERE d.id = $1
        """,
        device_uuid,
    )

    # The token must name the same subject the device row is bound to. Skipping this
    # let a refresh token mint a session for whoever owns the device row.
    if device is None or str(device["user_id"]) != subject:
        if device is not None:
            await conn.execute(
                "UPDATE devices SET refresh_token_hash = NULL WHERE id = $1",
                device["id"],
            )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token is no longer valid; sign in again",
            headers={"WWW-Authenticate": "Bearer"},
        )

    expires_at: datetime = device["refresh_expires_at"]
    if expires_at <= datetime.now(tz=timezone.utc):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token expired",
            headers={"WWW-Authenticate": "Bearer"},
        )

    principal = security.Principal(
        user_id=str(device["user_id"]),
        device_id=str(device["id"]),
        role=device["role"],
        can_upload=device["can_upload"],
        can_download=device["can_download"],
    )
    access_token, expires_in = security.create_access_token(settings, principal)
    rotated = security.create_refresh_token(settings, principal.user_id, principal.device_id)

    # Compare-and-swap on the *current* hash. The previous SELECT-then-UPDATE let two
    # concurrent refreshes both pass the check and both rotate; the loser's brand-new
    # token then mismatched and triggered the theft path, permanently killing the
    # session on an ordinary flaky-network double-fire.
    claimed = await conn.fetchval(
        """
        UPDATE devices
        SET refresh_token_hash = $2,
            refresh_expires_at = now() + ($3 || ' seconds')::interval,
            last_seen_at = now()
        WHERE id = $1 AND refresh_token_hash = $4
        RETURNING id
        """,
        device["id"],
        security.refresh_token_digest(rotated),
        str(settings.refresh_token_ttl_seconds),
        security.refresh_token_digest(body.refresh_token),
    )

    if claimed is None:
        # Someone rotated between our read and our write: this is exactly the reuse
        # case, so revoke the device rather than allowing both sessions to live.
        await conn.execute(
            "UPDATE devices SET refresh_token_hash = NULL WHERE id = $1",
            device["id"],
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token was already used; sign in again",
            headers={"WWW-Authenticate": "Bearer"},
        )

    return TokenResponse(
        access_token=access_token,
        refresh_token=rotated,
        expires_in=expires_in,
        user=UserPayload(
            id=str(device["user_id"]),
            username=device["username"],
            display_name=device["display_name"],
            role=device["role"],
            can_upload=device["can_upload"],
            can_download=device["can_download"],
        ),
    )


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(principal: PrincipalDep, conn: DbConn) -> None:
    # Revoking the refresh token leaves the (short-lived) access token valid for up to
    # its TTL, which §4.3 explicitly forbids: it must include admin authority.
    try:
        device_uuid = UUID(principal.device_id)
    except (ValueError, AttributeError, TypeError):
        # No usable device id means we cannot scope the revocation; bump the user's
        # session version so every outstanding token for this account dies at once.
        await _revoke_all_sessions(conn, principal.user_id)
        return

    await conn.execute(
        "UPDATE devices SET refresh_token_hash = NULL WHERE id = $1 AND user_id = $2",
        device_uuid,
        principal.user_id,
    )


async def _revoke_all_sessions(conn: DbConn, user_id: str) -> None:
    await conn.execute(
        "UPDATE devices SET refresh_token_hash = NULL WHERE user_id = $1",
        user_id,
    )


@router.get("/me", response_model=UserPayload)
async def me(principal: PrincipalDep, conn: DbConn) -> UserPayload:
    try:
        user_uuid = UUID(principal.user_id)
    except (ValueError, AttributeError, TypeError):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User no longer exists")

    row = await conn.fetchrow(
        "SELECT id, username, display_name, role, can_upload, can_download FROM users WHERE id = $1",
        user_uuid,
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="User no longer exists")
    return UserPayload(
        id=str(row["id"]),
        username=row["username"],
        display_name=row["display_name"],
        role=row["role"],
        can_upload=row["can_upload"],
        can_download=row["can_download"],
    )
