"""Shared FastAPI dependencies."""

from __future__ import annotations

from typing import Annotated

import asyncpg
from fastapi import Depends, Header, HTTPException, Request, status

from noiz.core import security
from noiz.core.db import db_conn
from noiz.core.settings import Settings


def settings_dep(request: Request) -> Settings:
    return request.app.state.settings


async def current_principal(
    settings: Annotated[Settings, Depends(settings_dep)],
    authorization: Annotated[str | None, Header()] = None,
) -> security.Principal:
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = authorization.split(" ", 1)[1].strip()
    try:
        return security.principal_from_access_token(settings, token)
    except security.AuthError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=exc.detail,
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


async def require_uploader(
    principal: Annotated[security.Principal, Depends(current_principal)],
) -> security.Principal:
    if not principal.can_upload:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="This account is not allowed to upload",
        )
    return principal


async def require_admin(
    principal: Annotated[security.Principal, Depends(current_principal)],
) -> security.Principal:
    if principal.role != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Administrator role required",
        )
    return principal


DbConn = Annotated[asyncpg.Connection, Depends(db_conn)]
SettingsDep = Annotated[Settings, Depends(settings_dep)]
PrincipalDep = Annotated[security.Principal, Depends(current_principal)]
AdminDep = Annotated[security.Principal, Depends(require_admin)]
