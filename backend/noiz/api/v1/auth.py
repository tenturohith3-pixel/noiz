"""Authentication routes (§9.2, ADR 14).

Refresh tokens rotate on every use and are stored as digests. Presenting a digest that
was already rotated away is treated as theft: the whole device is revoked, which is
what makes a stolen refresh token useless after one refresh.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from noiz.api.deps import DbConn, PrincipalDep, SettingsDep
from noiz.core import security

router = APIRouter(tags=["auth"])


class DeviceInfo(BaseModel):
    id: str
    name: str
    platform: str
    app_version: str | None = None


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
        body.username,
    )

    # Same response for unknown user and wrong password: no account enumeration.
    if user is None or not security.verify_password(user["password_hash"], body.password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
        )

    if security.needs_rehash(user["password_hash"]):
        await conn.execute(
            "UPDATE users SET password_hash = $2 WHERE id = $1",
            user["id"],
            security.hash_password(body.password),
        )

    principal = security.Principal(
        user_id=str(user["id"]),
        device_id=body.device.id,
        role=user["role"],
        can_upload=user["can_upload"],
        can_download=user["can_download"],
    )
    access_token, expires_in = security.create_access_token(settings, principal)
    refresh_token = security.create_refresh_token(settings, principal.user_id, body.device.id)

    await conn.execute(
        """
        INSERT INTO devices (id, user_id, name, platform, app_version, last_seen_at,
                             refresh_token_hash, refresh_expires_at)
        VALUES ($1, $2, $3, $4, $5, now(), $6, now() + ($7 || ' seconds')::interval)
        ON CONFLICT (id) DO UPDATE SET
            name = EXCLUDED.name,
            platform = EXCLUDED.platform,
            app_version = EXCLUDED.app_version,
            last_seen_at = now(),
            refresh_token_hash = EXCLUDED.refresh_token_hash,
            refresh_expires_at = EXCLUDED.refresh_expires_at
        """,
        uuid.UUID(body.device.id) if _is_uuid(body.device.id) else uuid.uuid4(),
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
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=exc.detail) from exc

    device_id = payload.get("did", "")
    digest = security.refresh_token_digest(body.refresh_token)

    device = await conn.fetchrow(
        """
        SELECT d.id, d.user_id, d.refresh_token_hash, d.refresh_expires_at,
               u.username, u.display_name, u.role, u.can_upload, u.can_download
        FROM devices d
        JOIN users u ON u.id = d.user_id
        WHERE d.id = $1
        """,
        uuid.UUID(device_id) if _is_uuid(device_id) else uuid.uuid4(),
    )
    if device is None or device["refresh_token_hash"] != digest:
        # Either unknown device or a rotated-away token: revoke and refuse.
        if device is not None:
            await conn.execute("UPDATE devices SET refresh_token_hash = NULL WHERE id = $1", device["id"])
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Refresh token is no longer valid; sign in again",
        )

    expires_at: datetime = device["refresh_expires_at"]
    if expires_at <= datetime.now(tz=timezone.utc):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Refresh token expired")

    principal = security.Principal(
        user_id=str(device["user_id"]),
        device_id=str(device["id"]),
        role=device["role"],
        can_upload=device["can_upload"],
        can_download=device["can_download"],
    )
    access_token, expires_in = security.create_access_token(settings, principal)
    rotated = security.create_refresh_token(settings, principal.user_id, principal.device_id)

    await conn.execute(
        """
        UPDATE devices
        SET refresh_token_hash = $2,
            refresh_expires_at = now() + ($3 || ' seconds')::interval,
            last_seen_at = now()
        WHERE id = $1
        """,
        device["id"],
        security.refresh_token_digest(rotated),
        str(settings.refresh_token_ttl_seconds),
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
    await conn.execute(
        "UPDATE devices SET refresh_token_hash = NULL WHERE id = $1 AND user_id = $2",
        uuid.UUID(principal.device_id) if _is_uuid(principal.device_id) else uuid.uuid4(),
        uuid.UUID(principal.user_id) if _is_uuid(principal.user_id) else uuid.uuid4(),
    )


@router.get("/me", response_model=UserPayload)
async def me(principal: PrincipalDep, conn: DbConn) -> UserPayload:
    row = await conn.fetchrow(
        "SELECT id, username, display_name, role, can_upload, can_download FROM users WHERE id = $1",
        uuid.UUID(principal.user_id) if _is_uuid(principal.user_id) else uuid.uuid4(),
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


def _is_uuid(value: str) -> bool:
    try:
        uuid.UUID(value)
    except (ValueError, AttributeError, TypeError):
        return False
    return True
