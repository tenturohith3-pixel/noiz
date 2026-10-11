"""noiz API server (§2.2, §2.6, §9).

Run with:
    uvicorn noiz.main:app --host 0.0.0.0 --port 8000 --workers 2

This process is stateless: it owns no playback state and writes no media bytes in the
production path (nginx does, via X-Accel-Redirect). Scale it by adding replicas.
"""

from __future__ import annotations

import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Final

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from starlette.exceptions import HTTPException as StarletteHTTPException

from noiz.api.v1 import auth, stream
from noiz.core.db import close_pool, get_pool, init_pool, require_extensions
from noiz.core.settings import Settings, get_settings

logger = logging.getLogger("noiz")
PROBLEM_CONTENT_TYPE: Final = "application/problem+json"

# Keep this in sync with the deployed version; clients compare it to gate updates.
API_VERSION: Final = "1.0.0"


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings: Settings = app.state.settings

    logging.basicConfig(
        level=logging.DEBUG if settings.debug else logging.INFO,
        format='{"ts":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","msg":"%(message)s"}',
    )

    pool = await init_pool(settings)
    async with pool.acquire() as conn:
        await require_extensions(conn)

    logger.info("noiz api %s ready (env=%s)", API_VERSION, settings.environment)

    try:
        yield
    finally:
        await close_pool()


# ---------------------------------------------------------------------------
# Problem+json error handling (RFC 7807, §9.1)
# ---------------------------------------------------------------------------


def _problem(
    *,
    status_code: int,
    title: str,
    detail: str,
    instance: str,
    extra: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    body: dict[str, Any] = {
        "type": f"https://noiz.example/problems/{title.lower().replace(' ', '-')}",
        "title": title,
        "status": status_code,
        "detail": detail,
        "instance": instance,
    }
    if extra:
        body.update(extra)
    return JSONResponse(status_code=status_code, content=body, media_type=PROBLEM_CONTENT_TYPE, headers=headers)


def install_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        return _problem(
            status_code=exc.status_code,
            title=_title_for(exc.status_code),
            detail=str(exc.detail),
            instance=str(request.url.path),
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        # `exc.errors()` entries carry the submitted `input`, which on /auth/login is
        # the plaintext password, and `ctx` may hold exception objects that json.dumps
        # cannot encode (raising a second 500 from inside this handler). Report the
        # location and type only.
        return _problem(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            title="Validation Failed",
            detail="One or more request fields are invalid.",
            instance=str(request.url.path),
            extra={
                "errors": [
                    {"loc": list(e.get("loc", ())), "msg": str(e.get("msg", "")), "type": str(e.get("type", ""))}
                    for e in exc.errors()
                ],
            },
        )

    @app.exception_handler(Exception)
    async def unhandled_handler(request: Request, exc: Exception) -> JSONResponse:
        # Never leak a stack trace to a client; the request id ties it to the log line.
        request_id = getattr(request.state, "request_id", "unknown")
        logger.exception("unhandled error request_id=%s path=%s", request_id, request.url.path)
        # ServerErrorMiddleware sits *outside* user middleware, so request_context's
        # header injection never runs for this path. Add the headers here so every
        # response still carries a request id (§10).
        return _problem(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            title="Internal Server Error",
            detail="The server failed to handle this request.",
            instance=str(request.url.path),
            extra={"request_id": request_id},
            headers={"X-Request-Id": request_id} if request_id != "unknown" else None,
        )


def _title_for(status_code: int) -> str:
    return {
        400: "Bad Request",
        401: "Unauthorized",
        403: "Forbidden",
        404: "Not Found",
        409: "Conflict",
        410: "Gone",
        416: "Range Not Satisfiable",
        422: "Validation Failed",
        429: "Too Many Requests",
        503: "Service Unavailable",
    }.get(status_code, "Error")


# ---------------------------------------------------------------------------
# Middleware
# ---------------------------------------------------------------------------


async def request_context(request: Request, call_next):  # type: ignore[no-untyped-def]
    """Adds a request id and a monotonic timer used for the p95 latency budget."""
    # Never trust a client-supplied id: Starlette's MutableHeaders encodes with
    # latin-1 and does not filter CR/LF, so an arbitrary header value could inject
    # extra headers in front of a non-normalising proxy — and it lands in the logs.
    request_id = _client_request_id(request.headers.get("X-Request-Id")) or uuid.uuid4().hex
    request.state.request_id = request_id
    started = time.perf_counter()

    response: Response = await call_next(request)

    response.headers["X-Request-Id"] = request_id
    response.headers["X-Process-Time-Ms"] = f"{(time.perf_counter() - started) * 1000:.1f}"
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


def _client_request_id(raw: str | None) -> str | None:
    """Returns the client's id only when it is a well-formed, log-safe token."""
    if not raw:
        return None
    candidate = raw.strip()
    if len(candidate) > 64:
        return None
    if not all(ch.isalnum() or ch in "-_." for ch in candidate):
        return None
    return candidate


# ---------------------------------------------------------------------------
# App factory
# ---------------------------------------------------------------------------


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or get_settings()

    app = FastAPI(
        title="noiz",
        version=API_VERSION,
        description="Self-hosted lossless music streaming backend.",
        lifespan=lifespan,
        docs_url="/docs" if resolved.debug else None,
        redoc_url=None,
        # Exposing the schema in production hands out the complete route inventory.
        openapi_url="/openapi.json" if resolved.debug else None,
    )
    app.state.settings = resolved

    install_exception_handlers(app)
    app.middleware("http")(request_context)

    if resolved.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=resolved.cors_origins,
            allow_credentials=False,
            allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-Request-Id"],
        )

    # Versioned API surface.
    app.include_router(auth.router, prefix="/api/v1")
    app.include_router(stream.router, prefix="/api/v1")

    # Streaming and artwork live at the root, so a signed URL can be handed straight to
    # nginx without an extra rewrite hop (§9.2).
    app.include_router(stream.media_router)

    @app.get("/healthz", include_in_schema=False)
    async def healthz() -> dict[str, str]:
        """No auth, no DB round-trip: the client's endpoint prober hits this in parallel
        on every network change with a 250 ms timeout (§2.4)."""
        return {"status": "ok", "version": API_VERSION}

    @app.get("/readyz", include_in_schema=False)
    async def readyz() -> Response:
        try:
            pool = get_pool()
            async with pool.acquire() as conn:
                await conn.fetchval("SELECT 1")
        except Exception:  # noqa: BLE001 - readiness must never raise
            return Response(status_code=status.HTTP_503_SERVICE_UNAVAILABLE)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    return app


app = create_app()
