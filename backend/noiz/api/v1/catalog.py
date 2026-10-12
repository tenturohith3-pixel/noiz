"""Catalog, search, favorites, playlist outbox and play-event routes (§10.1, §10.2).

Reads are index-backed and paginated; writes are idempotent. The client holds an
offline outbox of HLC-stamped operations and drains it against
`POST /api/v1/sync/outbox`, so every mutation here must be safe to replay: each
operation carries a client-generated `op_id` recorded in `outbox_ops` under a UNIQUE
constraint, and each write uses ON CONFLICT DO NOTHING.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Annotated, Any, Final
from uuid import UUID

from fastapi import APIRouter, HTTPException, Query, status
from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from noiz.api.deps import DbConn, PrincipalDep

router = APIRouter(tags=["catalog"])

LOSSLESS_CODECS: Final = frozenset({"flac", "alac", "wav", "aiff", "pcm", "dsd"})


def _lossless(codec: str | None) -> bool:
    return (codec or "").lower() in LOSSLESS_CODECS


# ---------------------------------------------------------------------------
# Pydantic bodies
# ---------------------------------------------------------------------------


class CamelModel(BaseModel):
    """Request body accepting both `trackId` and `track_id`.

    The wire format is camelCase (§10), but the outbox is drained by clients
    written against different generations of the contract, and a 422 that discards
    a listener's queued favorite is far worse than a permissive alias.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class OutboxOperation(CamelModel):
    op_id: str = Field(min_length=1, max_length=128)
    type: str = Field(min_length=1, max_length=64)
    payload: dict[str, Any] = Field(default_factory=dict)
    hlc: str | None = Field(default=None, max_length=128)


class OutboxRequest(CamelModel):
    ops: Annotated[list[OutboxOperation], Field(max_length=500)]


class OutboxResult(BaseModel):
    # Left snake_case on the wire: `by_alias` defaults to False for responses, and
    # the outbox ack shape is fixed as `{op_id, status}`.
    op_id: str
    status: str


class OutboxResponse(BaseModel):
    results: list[OutboxResult]


class PlayEvent(CamelModel):
    track_id: UUID | None = None
    device_id: str | None = Field(default=None, max_length=128)
    event_type: str = Field(min_length=1, max_length=64)
    position_ms: int | None = None
    played_ms: int | None = None
    ended_reason: str | None = Field(default=None, max_length=64)
    rendition: str | None = Field(default=None, max_length=64)
    offline: bool = False
    private: bool = False
    # Parsed by pydantic rather than passed through as a string: asyncpg refuses a
    # `str` for a timestamptz argument even under an explicit `::timestamptz` cast,
    # because the encoder dispatches on the Python type before the cast is applied.
    started_at: datetime | None = None
    idempotency_key: str | None = Field(default=None, max_length=200)

    @field_validator("started_at")
    @classmethod
    def _assume_utc(cls, value: datetime | None) -> datetime | None:
        """A client that sends no offset means UTC.

        The pool pins `SET TIME ZONE 'UTC'` per connection, so a naive datetime is
        otherwise resolved against the *server's* zone and lands hours away from
        the truth — exactly the drift that corrupts a listening timeline.
        """
        if value is not None and value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value


class PlayEventBatch(CamelModel):
    events: Annotated[list[PlayEvent], Field(max_length=1000)]


class AcceptedResponse(BaseModel):
    accepted: int


# ---------------------------------------------------------------------------
# Catalog reads
# ---------------------------------------------------------------------------


@router.get("/catalog/tracks")
async def list_tracks(
    conn: DbConn,
    principal: PrincipalDep,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT t.id, t.title, t.duration_ms, t.created_at,
               ar.name AS artist_name,
               al.title AS album_title,
               al.artwork_hash,
               (t.metadata ? 'lyrics') AS has_lyrics
        FROM tracks t
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.deleted_at IS NULL
        ORDER BY t.created_at DESC, t.title ASC
        LIMIT $1 OFFSET $2
        """,
        limit,
        offset,
    )
    total = await conn.fetchval(
        "SELECT count(*) FROM tracks WHERE deleted_at IS NULL",
    )
    return {
        "tracks": [
            {
                "id": str(row["id"]),
                "title": row["title"],
                "durationMs": row["duration_ms"],
                "artistName": row["artist_name"],
                "albumTitle": row["album_title"],
                "artworkHash": row["artwork_hash"],
                "hasLyrics": bool(row["has_lyrics"]),
                "addedAt": row["created_at"],
            }
            for row in rows
        ],
        "total": int(total or 0),
    }


@router.get("/catalog/albums")
async def list_albums(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT al.id, al.title, al.year, al.artwork_hash,
               ar.name AS artist_name,
               count(t.id) AS track_count
        FROM albums al
        LEFT JOIN artists ar ON ar.id = al.artist_id
        LEFT JOIN tracks t ON t.album_id = al.id AND t.deleted_at IS NULL
        GROUP BY al.id, ar.name
        ORDER BY al.sort_title ASC NULLS LAST, al.title ASC
        """,
    )
    return {
        "albums": [
            {
                "id": str(row["id"]),
                "title": row["title"],
                "artistName": row["artist_name"],
                "year": row["year"],
                "artworkHash": row["artwork_hash"],
                "trackCount": int(row["track_count"]),
            }
            for row in rows
        ]
    }


@router.get("/catalog/artists")
async def list_artists(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT ar.id, ar.name, count(DISTINCT al.id) AS album_count
        FROM artists ar
        LEFT JOIN albums al ON al.artist_id = ar.id
        GROUP BY ar.id, ar.name
        ORDER BY ar.sort_name ASC NULLS LAST, ar.name ASC
        """,
    )
    return {
        "artists": [
            {
                "id": str(row["id"]),
                "name": row["name"],
                "albumCount": int(row["album_count"]),
            }
            for row in rows
        ]
    }


@router.get("/catalog/playlists")
async def list_playlists(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT p.id, p.name, p.description, p.updated_at,
               count(pe.id) AS track_count
        FROM playlists p
        LEFT JOIN playlist_entries pe ON pe.playlist_id = p.id
        WHERE p.owner_id = $1
        GROUP BY p.id
        ORDER BY p.updated_at DESC
        """,
        principal.user_id,
    )
    return {
        "playlists": [
            {
                "id": str(row["id"]),
                "name": row["name"],
                "description": row["description"],
                "trackCount": int(row["track_count"]),
                "updatedAt": row["updated_at"],
            }
            for row in rows
        ]
    }


@router.get("/tracks/{track_id}")
async def get_track(
    conn: DbConn,
    principal: PrincipalDep,
    track_id: UUID,
) -> dict[str, Any]:
    track = await conn.fetchrow(
        """
        SELECT t.id, t.title, t.duration_ms, t.disc_number, t.track_number,
               t.codec, t.sample_rate, t.bit_depth, t.channels,
               t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db,
               t.gapless_next, t.availability, t.created_at,
               t.album_id, al.title AS album_title,
               t.artist_id, ar.name AS artist_name,
               al.artwork_hash
        FROM tracks t
        LEFT JOIN albums al ON al.id = t.album_id
        LEFT JOIN artists ar ON ar.id = t.artist_id
        WHERE t.id = $1 AND t.deleted_at IS NULL
        """,
        track_id,
    )
    if track is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown track")

    renditions = await conn.fetch(
        """
        SELECT r.id, r.codec, r.sample_rate, r.bit_depth, r.size_bytes, r.bitrate_kbps
        FROM renditions r
        WHERE r.track_id = $1
        ORDER BY
            CASE r.id
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

    artists: list[dict[str, str]] = []
    if track["artist_id"] is not None:
        artists.append({"id": str(track["artist_id"]), "name": track["artist_name"]})

    artwork_hash = track["artwork_hash"]
    return {
        "id": str(track["id"]),
        "title": track["title"],
        "durationMs": track["duration_ms"],
        "discNumber": track["disc_number"],
        "trackNumber": track["track_number"],
        "codec": track["codec"],
        "sampleRate": track["sample_rate"],
        "bitDepth": track["bit_depth"],
        "channels": track["channels"],
        "gaplessNext": track["gapless_next"],
        "availability": track["availability"],
        "addedAt": track["created_at"],
        "artists": artists,
        "album": (
            {"id": str(track["album_id"]), "title": track["album_title"]}
            if track["album_id"] is not None
            else None
        ),
        "artwork": (
            {"hash": artwork_hash, "url": f"/artwork/{artwork_hash}"}
            if artwork_hash
            else None
        ),
        "loudness": {
            "trackLufs": track["loudness_lufs"],
            "trackPeakDb": track["true_peak_db"],
            "albumLufs": track["album_loudness_lufs"],
            "albumPeakDb": track["album_peak_db"],
        },
        "renditions": [
            {
                "id": row["id"],
                "codec": row["codec"],
                "sampleRate": row["sample_rate"],
                "bitDepth": row["bit_depth"],
                "sizeBytes": row["size_bytes"],
                "bitrateKbps": row["bitrate_kbps"],
                "lossless": _lossless(row["codec"]),
            }
            for row in renditions
        ],
    }


@router.get("/search")
async def search(
    conn: DbConn,
    principal: PrincipalDep,
    q: Annotated[str, Query(min_length=1, max_length=256)],
    type: Annotated[str, Query(pattern="^(all|track|album|artist|playlist)$")] = "all",
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
) -> dict[str, Any]:
    # ILIKE with an escaped pattern: a user typing "100%" must not turn into a
    # wildcard that matches everything.
    pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    tracks = await conn.fetch(
        """
        SELECT t.id, t.title, ar.name AS subtitle
        FROM tracks t
        LEFT JOIN artists ar ON ar.id = t.artist_id
        WHERE t.deleted_at IS NULL AND t.title ILIKE $1 ESCAPE '\\'
        ORDER BY t.title ASC
        LIMIT $2
        """,
        pattern,
        limit,
    )
    albums = await conn.fetch(
        """
        SELECT al.id, al.title, ar.name AS subtitle
        FROM albums al
        LEFT JOIN artists ar ON ar.id = al.artist_id
        WHERE al.title ILIKE $1 ESCAPE '\\'
        ORDER BY al.title ASC
        LIMIT $2
        """,
        pattern,
        limit,
    )
    artists = await conn.fetch(
        """
        SELECT ar.id, ar.name AS title
        FROM artists ar
        WHERE ar.name ILIKE $1 ESCAPE '\\'
        ORDER BY ar.sort_name ASC NULLS LAST, ar.name ASC
        LIMIT $2
        """,
        pattern,
        limit,
    )
    playlists = await conn.fetch(
        """
        SELECT p.id, p.name AS title
        FROM playlists p
        WHERE p.owner_id = $1 AND p.name ILIKE $2 ESCAPE '\\'
        ORDER BY p.name ASC
        LIMIT $3
        """,
        principal.user_id,
        pattern,
        limit,
    )

    results: list[dict[str, Any]] = []
    if type in ("all", "track"):
        results.extend(
            {"kind": "track", "id": str(r["id"]), "title": r["title"], "subtitle": r["subtitle"]}
            for r in tracks
        )
    if type in ("all", "album"):
        results.extend(
            {"kind": "album", "id": str(r["id"]), "title": r["title"], "subtitle": r["subtitle"]}
            for r in albums
        )
    if type in ("all", "artist"):
        results.extend(
            {"kind": "artist", "id": str(r["id"]), "title": r["title"], "subtitle": ""}
            for r in artists
        )
    if type in ("all", "playlist"):
        results.extend(
            {"kind": "playlist", "id": str(r["id"]), "title": r["title"], "subtitle": ""}
            for r in playlists
        )
    return {"results": results[:limit]}


# ---------------------------------------------------------------------------
# Outbox drain
# ---------------------------------------------------------------------------


async def _apply_favorite_add(conn: DbConn, user_id: Any, payload: dict[str, Any]) -> str:
    track_id = _require_uuid(payload, "trackId")
    await conn.execute(
        """
        INSERT INTO favorites (user_id, track_id, created_at)
        VALUES ($1, $2, now())
        ON CONFLICT (user_id, track_id) DO NOTHING
        """,
        user_id,
        track_id,
    )
    return "ok"


async def _apply_favorite_remove(conn: DbConn, user_id: Any, payload: dict[str, Any]) -> str:
    track_id = _require_uuid(payload, "trackId")
    await conn.execute(
        "DELETE FROM favorites WHERE user_id = $1 AND track_id = $2",
        user_id,
        track_id,
    )
    return "ok"


async def _apply_playlist_add(conn: DbConn, user_id: Any, payload: dict[str, Any]) -> str:
    playlist_id = _require_uuid(payload, "playlistId")
    track_id = _require_uuid(payload, "trackId")
    position_key = payload.get("positionKey")
    if not isinstance(position_key, str) or not position_key:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="positionKey is required")

    owned = await conn.fetchval(
        "SELECT 1 FROM playlists WHERE id = $1 AND owner_id = $2",
        playlist_id,
        user_id,
    )
    if owned is None:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Playlist is not yours")

    await conn.execute(
        """
        INSERT INTO playlist_entries (id, playlist_id, position_key, track_id, added_by, added_at)
        VALUES (gen_random_uuid(), $1, $2, $3, $4, now())
        ON CONFLICT DO NOTHING
        """,
        playlist_id,
        position_key,
        track_id,
        user_id,
    )
    await _bump_revision(conn, playlist_id)
    return "ok"


async def _apply_playlist_remove(conn: DbConn, user_id: Any, payload: dict[str, Any]) -> str:
    playlist_id = _require_uuid(payload, "playlistId")
    track_id = _require_uuid(payload, "trackId")
    await conn.execute(
        """
        DELETE FROM playlist_entries
        WHERE playlist_id = $1 AND track_id = $2
          AND playlist_id IN (SELECT id FROM playlists WHERE owner_id = $3)
        """,
        playlist_id,
        track_id,
        user_id,
    )
    await _bump_revision(conn, playlist_id)
    return "ok"


async def _apply_playlist_move(conn: DbConn, user_id: Any, payload: dict[str, Any]) -> str:
    playlist_id = _require_uuid(payload, "playlistId")
    track_id = _require_uuid(payload, "trackId")
    position_key = payload.get("positionKey")
    if not isinstance(position_key, str) or not position_key:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="positionKey is required")

    await conn.execute(
        """
        UPDATE playlist_entries pe
        SET position_key = $3
        FROM playlists p
        WHERE pe.playlist_id = $1
          AND pe.track_id = $2
          AND p.id = pe.playlist_id
          AND p.owner_id = $4
        """,
        playlist_id,
        track_id,
        position_key,
        user_id,
    )
    await _bump_revision(conn, playlist_id)
    return "ok"


OUTBOX_HANDLERS: Final = {
    "favorite.add": _apply_favorite_add,
    "favorite.remove": _apply_favorite_remove,
    "playlist.add": _apply_playlist_add,
    "playlist.remove": _apply_playlist_remove,
    "playlist.move": _apply_playlist_move,
}


def _require_uuid(payload: dict[str, Any], key: str) -> UUID:
    raw = payload.get(key)
    if not isinstance(raw, str) or not raw:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{key} is required")
    try:
        return UUID(raw)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"{key} is not a UUID") from None


async def _bump_revision(conn: DbConn, playlist_id: UUID) -> None:
    await conn.execute(
        "UPDATE playlists SET revision = revision + 1 WHERE id = $1",
        playlist_id,
    )


@router.post("/sync/outbox", response_model=OutboxResponse)
async def sync_outbox(
    body: OutboxRequest,
    conn: DbConn,
    principal: PrincipalDep,
) -> OutboxResponse:
    results: list[OutboxResult] = []
    for op in body.ops:
        handler = OUTBOX_HANDLERS.get(op.type)
        if handler is None:
            results.append(OutboxResult(op_id=op.op_id, status="invalid"))
            continue

        # Each op gets its own transaction. The mutation and the `outbox_ops` record
        # commit together, so a crash between them can never leave an op applied but
        # unrecorded — the replay would then apply it a second time.
        try:
            async with conn.transaction():
                already = await conn.fetchval(
                    "SELECT 1 FROM outbox_ops WHERE op_id = $1",
                    op.op_id,
                )
                if already is not None:
                    # Already applied by an earlier drain. `ok`, not `conflict`: the
                    # client's intent has been honoured, it just happened earlier.
                    op_status = "ok"
                else:
                    await handler(conn, principal.user_id, op.payload)
                    await conn.execute(
                        """
                        INSERT INTO outbox_ops (id, user_id, op_id, type, payload, created_at)
                        VALUES (gen_random_uuid(), $1, $2, $3, $4, now())
                        ON CONFLICT (op_id) DO NOTHING
                        """,
                        principal.user_id,
                        op.op_id,
                        op.type,
                        json.dumps(op.payload),
                    )
                    op_status = "ok"
        except HTTPException as exc:
            # A malformed payload is the client's bug (invalid); someone else's
            # playlist is a genuine conflict the client must surface, not retry.
            op_status = (
                "conflict" if exc.status_code == status.HTTP_403_FORBIDDEN else "invalid"
            )
        except Exception:  # noqa: BLE001 - one bad op must not abort the whole drain
            op_status = "conflict"

        results.append(OutboxResult(op_id=op.op_id, status=op_status))

    return OutboxResponse(results=results)


# ---------------------------------------------------------------------------
# Play events
# ---------------------------------------------------------------------------


@router.post("/me/events", response_model=AcceptedResponse)
async def submit_events(
    body: PlayEventBatch,
    conn: DbConn,
    principal: PrincipalDep,
) -> AcceptedResponse:
    accepted = 0
    for event in body.events:
        # `RETURNING id` is the dedupe signal: ON CONFLICT DO NOTHING yields no row
        # for an already-seen `idempotency_key`, which is a success the client should
        # not retry. A NULL idempotency_key never collides (UNIQUE ignores NULLs), so
        # an unkeyed event is always stored — losing history is worse than duplicating.
        inserted = await conn.fetchval(
            """
            INSERT INTO play_events (
                id, user_id, track_id, device_id, event_type, position_ms, played_ms,
                ended_reason, rendition, offline, private, started_at, idempotency_key
            )
            VALUES (
                gen_random_uuid(), $1, $2, $3, $4, $5, $6,
                $7, $8, $9, $10, COALESCE($11::timestamptz, now()), $12
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """,
            principal.user_id,
            event.track_id,
            event.device_id or principal.device_id,
            event.event_type,
            event.position_ms,
            event.played_ms,
            event.ended_reason,
            event.rendition,
            event.offline,
            event.private,
            event.started_at,
            event.idempotency_key,
        )
        if inserted is not None:
            accepted += 1
    return AcceptedResponse(accepted=accepted)


# ---------------------------------------------------------------------------
# Favorites
# ---------------------------------------------------------------------------


@router.put("/favorites/{track_id}")
async def add_favorite(conn: DbConn, principal: PrincipalDep, track_id: UUID) -> dict[str, Any]:
    await conn.execute(
        """
        INSERT INTO favorites (user_id, track_id, created_at)
        VALUES ($1, $2, now())
        ON CONFLICT (user_id, track_id) DO NOTHING
        """,
        principal.user_id,
        track_id,
    )
    return {"trackId": str(track_id), "favorited": True}


@router.delete("/favorites/{track_id}")
async def remove_favorite(conn: DbConn, principal: PrincipalDep, track_id: UUID) -> dict[str, Any]:
    await conn.execute(
        "DELETE FROM favorites WHERE user_id = $1 AND track_id = $2",
        principal.user_id,
        track_id,
    )
    return {"trackId": str(track_id), "favorited": False}