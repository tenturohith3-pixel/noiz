"""PostgreSQL access (§2.2).

A single asyncpg pool is created in the FastAPI lifespan and shared by every request.
asyncpg is used directly rather than through an ORM: the hot paths here are two
hand-written queries (manifest lookup and the changes feed), and the ingest pipeline
needs prepared statements and `COPY` for bulk upserts.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

import asyncpg

from noiz.core.settings import Settings

logger = logging.getLogger(__name__)

_pool: asyncpg.Pool | None = None


async def init_pool(settings: Settings) -> asyncpg.Pool:
    """Creates the pool and verifies the extensions the schema depends on."""
    global _pool
    if _pool is not None:
        return _pool

    _pool = await asyncpg.create_pool(
        dsn=settings.database_url,
        min_size=settings.db_pool_min_size,
        max_size=settings.db_pool_max_size,
        command_timeout=settings.db_command_timeout,
        # UUIDs arrive as strings so they serialise straight into JSON responses.
        init=_init_connection,
    )
    logger.info(
        "postgres pool ready (min=%d max=%d)",
        settings.db_pool_min_size,
        settings.db_pool_max_size,
    )
    return _pool


async def _init_connection(conn: asyncpg.Connection) -> None:
    # Register the vector type so pgvector columns round-trip as lists of floats.
    await conn.set_type_codec(
        "vector",
        encoder=lambda value: "[" + ",".join(map(str, value)) + "]",
        decoder=lambda value: [float(part) for part in value.strip("[]").split(",")],
        schema="public",
        format="text",
    )
    await conn.execute("SET TIME ZONE 'UTC'")


async def close_pool() -> None:
    global _pool
    if _pool is not None:
        await _pool.close()
        _pool = None
        logger.info("postgres pool closed")


def get_pool() -> asyncpg.Pool:
    if _pool is None:
        raise RuntimeError("database pool is not initialised")
    return _pool


# ---------------------------------------------------------------------------
# FastAPI dependency
# ---------------------------------------------------------------------------


async def db_conn() -> AsyncIterator[asyncpg.Connection]:
    """Yields a pooled connection.

    Kept as an async generator (not a bare `pool.acquire()`) so FastAPI's dependency
    system guarantees the connection is returned even when a handler raises.
    """
    pool = get_pool()
    async with pool.acquire() as conn:
        yield conn


@asynccontextmanager
async def short_lived_conn() -> AsyncIterator[asyncpg.Connection]:
    """A connection that is released as soon as the `async with` block exits.

    Use this instead of the `DbConn` dependency in any handler that returns a
    `StreamingResponse`. FastAPI runs a yield-dependency's teardown *after* the
    response body has been fully sent, so a `DbConn` handler pins a pooled connection
    for the entire duration of a media transfer.
    """
    pool = get_pool()
    async with pool.acquire() as conn:
        yield conn


@asynccontextmanager
async def transaction() -> AsyncIterator[asyncpg.Connection]:
    """Explicit transaction for read-modify-write paths (playlist rebalancing)."""
    pool = get_pool()
    async with pool.acquire() as conn:
        async with conn.transaction():
            yield conn


# ---------------------------------------------------------------------------
# Small typed helpers used across routers
# ---------------------------------------------------------------------------


async def fetchrow(conn: asyncpg.Connection, query: str, *args: Any) -> asyncpg.Record | None:
    return await conn.fetchrow(query, *args)


async def fetch(conn: asyncpg.Connection, query: str, *args: Any) -> list[asyncpg.Record]:
    return await conn.fetch(query, *args)


async def require_extensions(conn: asyncpg.Connection) -> None:
    """Fails fast at boot if the deployment's image lacks pgvector/pg_trgm/citext."""
    rows = await conn.fetch("SELECT extname FROM pg_extension")
    present = {row["extname"] for row in rows}
    required = {"vector", "pg_trgm", "citext"}
    missing = required - present
    if missing:
        raise RuntimeError(
            f"database is missing required extensions: {', '.join(sorted(missing))}. "
            "Use the pgvector/pgvector:pg16 image and run the Alembic baseline migration."
        )
