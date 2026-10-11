"""Environment-driven configuration (§2.3, §12)."""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# `alg` is env-configurable, so it needs an allowlist: an operator setting
# NOIZ_JWT_ALGORITHM=none would otherwise disable signature verification entirely.
ALLOWED_JWT_ALGORITHMS = frozenset({"HS256", "HS384", "HS512"})


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="NOIZ_",
        extra="ignore",
        case_sensitive=False,
    )

    # --- Core -------------------------------------------------------------
    app_name: str = "noiz"
    environment: str = "production"
    debug: bool = False

    # --- PostgreSQL (ADR 2) ----------------------------------------------
    # asyncpg DSN. pgvector + pg_trgm + citext are required extensions.
    database_url: str = Field(
        default="postgresql://noiz:noiz@postgres:5432/noiz",
        description="asyncpg DSN",
    )
    db_pool_min_size: int = 2
    db_pool_max_size: int = 10
    db_command_timeout: float = 30.0

    # --- Redis ------------------------------------------------------------
    redis_url: str = "redis://redis:6379/0"

    # --- Auth (ADR 14) ----------------------------------------------------
    jwt_secret: str = Field(default="change-me-in-.env", min_length=8)
    jwt_algorithm: str = "HS256"
    access_token_ttl_seconds: int = 15 * 60
    refresh_token_ttl_seconds: int = 30 * 24 * 3600

    # --- Media signing (§9.4) --------------------------------------------
    stream_hmac_secret: str = Field(default="change-me-too", min_length=8)
    stream_url_ttl_seconds: int = 6 * 3600

    # --- Media storage (ADR 4) -------------------------------------------
    media_root: Path = Path("/media")
    inbox_root: Path = Path("/inbox")

    # Production path: nginx serves bytes via X-Accel-Redirect (ADR 5). Set to false
    # only for development or single-process deployments, where FastAPI itself does
    # the byte-range sendfile.
    x_accel_enabled: bool = True
    x_accel_internal_prefix: str = "/_media"

    # Streaming (§9.4)
    stream_chunk_bytes: int = 256 * 1024
    # Byte-range responses are private + revalidated: the client's SimpleCache is ours.
    media_cache_control: str = "private, max-age=0, must-revalidate"
    # Content-addressed artwork is immutable.
    artwork_cache_control: str = "public, max-age=31536000, immutable"

    # --- Uploads (FR-UP-3) ------------------------------------------------
    tus_chunk_bytes: int = 4 * 1024 * 1024
    max_upload_bytes: int = 4 * 1024 * 1024 * 1024

    # --- CORS -------------------------------------------------------------
    # The Android client is not a browser origin; CORS exists only for local tooling.
    cors_origins: list[str] = Field(default_factory=list)

    @field_validator("media_root", "inbox_root")
    @classmethod
    def _absolute(cls, value: Path) -> Path:
        if not value.is_absolute():
            raise ValueError("media paths must be absolute")
        return value

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _split_origins(cls, value: object) -> object:
        if isinstance(value, str):
            return [origin.strip() for origin in value.split(",") if origin.strip()]
        return value

    @model_validator(mode="after")
    def _reject_placeholder_secrets(self) -> "Settings":
        """Fail at boot rather than run with a publicly-known signing key.

        `.env.example` ships `change-me` / `change-me-too`, and docker-compose only
        checks that the variables are *non-empty*, so a verbatim `cp .env.example .env`
        produced a working deployment that anyone could forge stream signatures for.
        """
        if self.environment != "production":
            return self

        weak = {"change-me-in-.env", "change-me-too", "change-me", "", "secret", "noiz"}
        for name in ("jwt_secret", "stream_hmac_secret"):
            value = getattr(self, name)
            if value.strip().lower() in weak or len(value) < 32:
                raise ValueError(
                    f"{name} is a placeholder or too short ({len(value)} chars). "
                    f"Generate one with: openssl rand -hex 32"
                )

        if self.jwt_algorithm not in ALLOWED_JWT_ALGORITHMS:
            raise ValueError(
                f"jwt_algorithm must be one of {sorted(ALLOWED_JWT_ALGORITHMS)}, "
                f"got {self.jwt_algorithm!r}"
            )

        return self


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
