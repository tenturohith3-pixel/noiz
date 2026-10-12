"""Alembic environment (ADR 2).

The DSN comes from `noiz.core.settings.get_settings().database_url`, never from
`alembic.ini`: the app already owns connection configuration and a second copy in
`alembic.ini` is a second thing to forget during a password rotation.

Async because the application is async end to end — the migration engine drives the
same `postgresql+asyncpg` driver as `noiz.core.db`, so there is no sync driver to
install.
"""

from __future__ import annotations

import asyncio
from logging.config import fileConfig

from alembic import context
from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import async_engine_from_config

from noiz.core.settings import get_settings

# The Alembic Config object provides access to the values within the .ini file.
config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Autogenerate is unsupported: the schema is hand-maintained and there is no
# declarative metadata to diff against. ``target_metadata`` stays None so that
# `alembic revision --autogenerate` fails loudly instead of emitting an empty diff.
target_metadata = None


def database_url() -> str:
    """Returns the settings DSN rewritten for the async driver."""
    url = get_settings().database_url
    if url.startswith("postgresql+asyncpg://"):
        return url
    if url.startswith("postgresql://"):
        return "postgresql+asyncpg://" + url[len("postgresql://") :]
    if url.startswith("postgres://"):
        return "postgresql+asyncpg://" + url[len("postgres://") :]
    return url


def _configure(url: str) -> None:
    # `set_main_option` writes into a ConfigParser, so `%` is an interpolation marker
    # there even though it is an ordinary character in a DSN. A password containing
    # `%` would otherwise abort the migration with a parsing error.
    config.set_main_option("sqlalchemy.url", url.replace("%", "%%"))


def run_migrations_offline() -> None:
    """Emit SQL to stdout without a live connection (`alembic upgrade --sql`)."""
    _configure(database_url())
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        compare_type=True,
        compare_server_default=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def do_run_migrations(connection: Connection) -> None:
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        compare_type=True,
        compare_server_default=True,
    )
    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    _configure(database_url())
    connectable = async_engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )
    try:
        async with connectable.connect() as connection:
            await connection.run_sync(do_run_migrations)
    finally:
        await connectable.dispose()


def run_migrations_online() -> None:
    """Run migrations against a live database."""
    asyncio.run(run_async_migrations())


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()