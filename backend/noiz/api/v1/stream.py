"""Streaming endpoints (§9.2, §9.4).

Two serving paths share one URL shape:

  * production — the handler validates the HMAC signature, then hands the byte path to
    nginx with `X-Accel-Redirect`. nginx does sendfile, Range, ETag and keep-alive
    natively, so FastAPI never touches audio bytes (ADR 5).
  * development — when `NOIZ_X_ACCEL_ENABLED=false`, FastAPI streams the range itself.

Both paths must satisfy §9.4: `Accept-Ranges: bytes`, accurate `Content-Length`,
`ETag` = rendition SHA-256, `206` with a correct `Content-Range`, `416` for bad ranges,
and correct `Content-Type` per container.
"""

from __future__ import annotations

import hashlib
import mimetypes
import re
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Annotated, Any, Final

import asyncpg
from fastapi import APIRouter, Header, HTTPException, Path as PathParam, Query, Response, status
from fastapi.responses import StreamingResponse

from noiz.api.deps import DbConn, PrincipalDep, SettingsDep
from noiz.core import security
from noiz.core.settings import Settings

# Metadata routes are mounted under /api/v1; byte-serving routes are mounted at the
# root so a signed URL handed to nginx needs no rewrite hop (§9.2).
router = APIRouter(tags=["stream"])
media_router = APIRouter(tags=["stream"])

RANGE_PATTERN: Final = re.compile(r"bytes\s*=\s*(\d*)\s*-\s*(\d*)", re.IGNORECASE)

CONTENT_TYPE_BY_CODEC: Final[dict[str, str]] = {
    "flac": "audio/flac",
    "opus": "audio/ogg",
    "vorbis": "audio/ogg",
    "mp3": "audio/mpeg",
    "aac": "audio/mp4",
    "alac": "audio/mp4",
    "wav": "audio/wav",
    "aiff": "audio/aiff",
}


class RangeNotSatisfiable(Exception):
    def __init__(self, size: int) -> None:
        super().__init__("requested range is not satisfiable")
        self.size = size


# ---------------------------------------------------------------------------
# Range arithmetic
# ---------------------------------------------------------------------------


def parse_range_header(header: str, size: int) -> tuple[int, int] | None:
    """Returns an inclusive `(start, end)` pair, or None when the header is absent.

    Only a single range is honoured. Multi-range requests are legal in HTTP but no
    audio client issues them, and answering them requires multipart/byteranges which
    ExoPlayer's datasource does not accept.
    """
    match = RANGE_PATTERN.fullmatch(header.strip())
    if match is None:
        return None

    start_raw, end_raw = match.groups()
    if start_raw == "" and end_raw == "":
        return None

    if start_raw == "":
        # Suffix range: last N bytes.
        suffix_length = int(end_raw)
        if suffix_length <= 0:
            raise RangeNotSatisfiable(size)
        start = max(0, size - suffix_length)
        end = size - 1
    else:
        start = int(start_raw)
        end = int(end_raw) if end_raw else size - 1
        end = min(end, size - 1)

    if start >= size or start > end:
        raise RangeNotSatisfiable(size)
    return start, end


async def _iter_file_range(path: Path, start: int, end: int, chunk_bytes: int) -> AsyncIterator[bytes]:
    """Reads `[start, end]` inclusive without loading the file into memory."""
    remaining = end - start + 1
    with path.open("rb") as handle:
        handle.seek(start)
        while remaining > 0:
            chunk = handle.read(min(chunk_bytes, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk


def _content_type_for(codec: str, path: Path) -> str:
    if codec in CONTENT_TYPE_BY_CODEC:
        return CONTENT_TYPE_BY_CODEC[codec]
    guessed, _ = mimetypes.guess_type(path.name)
    return guessed or "application/octet-stream"


def _etag(sha256_hex: str) -> str:
    return f'"{sha256_hex}"'


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------


@router.get("/tracks/{track_id}/stream-manifest")
async def stream_manifest(
    track_id: str,
    conn: DbConn,
    settings: SettingsDep,
    principal: PrincipalDep,
) -> dict:
    """Renditions, loudness metadata and freshly signed URLs for one track."""
    track = await conn.fetchrow(
        """
        SELECT t.id, t.duration_ms, t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db, t.gapless_next
        FROM tracks t
        WHERE t.id = $1 AND t.deleted_at IS NULL
        """,
        track_id,
    )
    if track is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown track")

    renditions = await conn.fetch(
        """
        SELECT id, codec, container, bitrate_kbps, sample_rate, bit_depth, size_bytes, sha256
        FROM renditions
        WHERE track_id = $1
        ORDER BY
            CASE id
                WHEN 'orig'    THEN 0
                WHEN 'flac16'  THEN 1
                WHEN 'opus256' THEN 2
                WHEN 'opus128' THEN 3
                WHEN 'opus64'  THEN 4
                ELSE 5
            END
        """,
        track_id,
    )
    if not renditions:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="No renditions are available yet; ingest may still be running",
        )

    expires_at = 0
    payload_renditions = []
    for rendition in renditions:
        signature, expires_at = security.sign_stream_url(
            settings,
            user_id=principal.user_id,
            track_id=track_id,
            rendition=rendition["id"],
        )
        payload_renditions.append(
            {
                "id": rendition["id"],
                "codec": rendition["codec"],
                "container": rendition["container"],
                "bitrate_kbps": rendition["bitrate_kbps"],
                "sample_rate": rendition["sample_rate"],
                "bit_depth": rendition["bit_depth"],
                "size_bytes": rendition["size_bytes"],
                "sha256": rendition["sha256"].hex() if isinstance(rendition["sha256"], bytes) else rendition["sha256"],
                "url": (
                    f"/stream/{track_id}/{rendition['id']}"
                    f"?exp={expires_at}&sig={signature}"
                ),
            },
        )

    return {
        "track_id": track["id"],
        "renditions": payload_renditions,
        "loudness": {
            "track_lufs": track["loudness_lufs"],
            "track_peak_db": track["true_peak_db"],
            "album_lufs": track["album_loudness_lufs"],
            "album_peak_db": track["album_peak_db"],
        },
        "gapless_next": track["gapless_next"],
        "expires_at": expires_at,
    }


# ---------------------------------------------------------------------------
# Byte serving
# ---------------------------------------------------------------------------


async def _lookup_rendition(
    conn: asyncpg.Connection,
    settings: Settings,
    track_id: str,
    rendition_id: str,
) -> dict[str, Any]:
    row = await conn.fetchrow(
        """
        SELECT r.path, r.codec, r.container, r.size_bytes, r.sha256
        FROM renditions r
        JOIN tracks t ON t.id = r.track_id
        WHERE r.track_id = $1 AND r.id = $2 AND t.deleted_at IS NULL
        """,
        track_id,
        rendition_id,
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown rendition")

    # Paths are stored relative to the media root and re-rooted here, so a compromised
    # ingest job cannot escape the media tree through the database (§12).
    resolved = (settings.media_root / row["path"]).resolve()
    if not resolved.is_relative_to(settings.media_root.resolve()):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid media path")
    if not resolved.is_file():
        raise HTTPException(
            status_code=status.HTTP_410_GONE,
            detail="Rendition file is missing on disk",
        )

    # Record is a tuple subclass, so build a plain dict rather than merging into it.
    enriched = dict(row)
    enriched["resolved_path"] = resolved
    return enriched


@media_router.get("/stream/{track_id}/{rendition_id}")
async def stream_media(
    conn: DbConn,
    settings: SettingsDep,
    track_id: Annotated[str, PathParam()],
    rendition_id: Annotated[str, PathParam(pattern="^(orig|flac16|opus256|opus128|opus64)$")],
    exp: Annotated[int, Query()],
    sig: Annotated[str, Query()],
    user: Annotated[str, Query(description="User id bound into the signature")],
    range_header: Annotated[str | None, Header(alias="Range")] = None,
    if_none_match: Annotated[str | None, Header(alias="If-None-Match")] = None,
) -> Response:
    """Signed, range-addressable media bytes.

    The signature binds `user|track|rendition|exp`, so a URL cannot be replayed for a
    different user, track or rendition, and it expires 6 h after minting.
    """
    if not security.verify_stream_signature(
        settings,
        user_id=user,
        track_id=track_id,
        rendition=rendition_id,
        exp=exp,
        signature=sig,
    ):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid or expired signature")

    rendition = await _lookup_rendition(conn, settings, track_id, rendition_id)
    size: int = rendition["size_bytes"]
    sha256_hex = (
        rendition["sha256"].hex() if isinstance(rendition["sha256"], bytes) else str(rendition["sha256"])
    )
    etag = _etag(sha256_hex)
    content_type = _content_type_for(rendition["codec"], rendition["resolved_path"])

    # Immutable rendition: a matching ETag means nothing changed, and Range is pointless.
    if if_none_match == etag and range_header is None:
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers={"ETag": etag})

    try:
        byte_range = parse_range_header(range_header, size) if range_header else None
    except RangeNotSatisfiable:
        return Response(
            status_code=status.HTTP_416_REQUESTED_RANGE_NOT_SATISFIABLE,
            headers={
                "Content-Range": f"bytes */{size}",
                "Accept-Ranges": "bytes",
                "ETag": etag,
            },
        )

    common_headers = {
        "Accept-Ranges": "bytes",
        "ETag": etag,
        "Cache-Control": settings.media_cache_control,
        "Content-Type": content_type,
        # Renditions are immutable per (track, id); no need for Vary or revalidation
        # beyond the ETag the client already holds.
        "X-Content-Type-Options": "nosniff",
    }

    start, end = byte_range if byte_range is not None else (0, size - 1)
    status_code = (
        status.HTTP_206_PARTIAL_CONTENT if byte_range is not None else status.HTTP_200_OK
    )

    if settings.x_accel_enabled:
        # nginx reads the file itself; Content-Length/Range are computed by nginx.
        headers = {
            **common_headers,
            "X-Accel-Redirect": f"{settings.x_accel_internal_prefix}/{rendition['path']}",
        }
        if byte_range is not None:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        return Response(status_code=status_code, headers=headers)

    headers = {**common_headers, "Content-Length": str(end - start + 1)}
    if byte_range is not None:
        headers["Content-Range"] = f"bytes {start}-{end}/{size}"

    return StreamingResponse(
        _iter_file_range(rendition["resolved_path"], start, end, settings.stream_chunk_bytes),
        status_code=status_code,
        headers=headers,
    )


@media_router.get("/stream/auth")
async def stream_auth_probe(
    settings: SettingsDep,
    track_id: Annotated[str, Query()],
    rendition_id: Annotated[str, Query()],
    exp: Annotated[int, Query()],
    sig: Annotated[str, Query()],
    user: Annotated[str, Query()],
) -> Response:
    """nginx `auth_request` target (§9.4).

    Returns 204 with the internal path in a header; nginx then serves the file with
    sendfile. Deliberately does no database work — nginx already knows the path from
    the X-Accel-Redirect it forwarded — which is what keeps this at ~2 ms.
    """
    if not security.verify_stream_signature(
        settings,
        user_id=user,
        track_id=track_id,
        rendition=rendition_id,
        exp=exp,
        signature=sig,
    ):
        return Response(status_code=status.HTTP_403_FORBIDDEN)

    digest = hashlib.sha256(f"{track_id}/{rendition_id}".encode()).hexdigest()
    return Response(
        status_code=status.HTTP_204_NO_CONTENT,
        headers={"X-Noiz-Rendition-Key": digest},
    )


# ---------------------------------------------------------------------------
# Artwork (content-addressed, immutable)
# ---------------------------------------------------------------------------

ALLOWED_ARTWORK_WIDTHS: Final = {128, 512, 1400}


@media_router.get("/artwork/{artwork_hash}")
async def artwork(
    settings: SettingsDep,
    artwork_hash: Annotated[str, PathParam(pattern="^[0-9a-f]{64}$")],
    width: Annotated[int, Query(alias="w")] = 512,
    fmt: Annotated[str, Query(pattern="^(webp|png|jpg)$")] = "webp",
) -> Response:
    if width not in ALLOWED_ARTWORK_WIDTHS:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Unsupported artwork width")

    relative = Path("artwork") / artwork_hash[:2] / f"{artwork_hash}-{width}.{fmt}"
    resolved = (settings.media_root / relative).resolve()
    if not resolved.is_relative_to(settings.media_root.resolve()) or not resolved.is_file():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown artwork")

    headers = {
        "Cache-Control": settings.artwork_cache_control,
        "ETag": _etag(artwork_hash),
        "Accept-Ranges": "bytes",
        "Content-Type": f"image/{fmt}",
    }
    if settings.x_accel_enabled:
        headers["X-Accel-Redirect"] = f"{settings.x_accel_internal_prefix}/{relative.as_posix()}"
        return Response(status_code=status.HTTP_200_OK, headers=headers)

    return Response(content=resolved.read_bytes(), headers=headers)
