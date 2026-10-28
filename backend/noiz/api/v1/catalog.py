"""Catalog, search, favorites, playlist outbox and play-event routes (§10.1, §10.2).

Reads are index-backed and paginated; writes are idempotent. The client holds an
offline outbox of HLC-stamped operations and drains it against
`POST /api/v1/sync/outbox`, so every mutation here must be safe to replay: each
operation carries a client-generated `op_id` recorded in `outbox_ops` under a UNIQUE
constraint, and each write uses ON CONFLICT DO NOTHING.
"""

from __future__ import annotations

import json
import re
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


def _epoch_ms(value: Any) -> int:
    """Serialize a timestamptz as epoch milliseconds.

    The Android models use `Long` timestamps; sending ISO-8601 strings made every
    `addedAt`/`updatedAt` default to zero.
    """
    if value is None:
        return 0
    if isinstance(value, datetime):
        return int(value.timestamp() * 1000)
    return int(value)


def _artwork(hash_value: str | None) -> dict[str, str] | None:
    if not hash_value:
        return None
    return {"hash": hash_value, "url": f"/artwork/{hash_value}"}


def _metadata_flag(metadata: Any, key: str) -> bool:
    if not isinstance(metadata, dict):
        return False
    value = metadata.get(key)
    return bool(value) if value is not None else False


def _track_json(row: Any, *, renditions: list[Any] | None = None) -> dict[str, Any]:
    metadata = row.get("metadata") or {}
    artist_id = row.get("artist_id")
    album_id = row.get("album_id")
    return {
        "id": str(row["id"]),
        "title": row["title"],
        "durationMs": int(row["duration_ms"] or 0),
        "discNumber": int(row["disc_number"] or 1),
        "trackNumber": int(row["track_number"] or 0),
        "explicit": _metadata_flag(metadata, "explicit"),
        "isrc": metadata.get("isrc") if isinstance(metadata, dict) else None,
        "gaplessNext": bool(row["gapless_next"]),
        "hasLyrics": _metadata_flag(metadata, "lyrics"),
        "hasCanvas": _metadata_flag(metadata, "canvas"),
        "loudness": {
            "trackLufs": row["loudness_lufs"],
            "trackPeakDb": row["true_peak_db"],
            "albumLufs": row["album_loudness_lufs"],
            "albumPeakDb": row["album_peak_db"],
        },
        "artists": (
            [{"id": str(artist_id), "name": row["artist_name"]}]
            if artist_id is not None
            else []
        ),
        "album": (
            {"id": str(album_id), "title": row["album_title"]}
            if album_id is not None
            else None
        ),
        "artwork": _artwork(row.get("artwork_hash")),
        "addedAt": _epoch_ms(row.get("created_at")),
        "updatedAt": _epoch_ms(row.get("updated_at")),
        "renditions": [
            {
                "id": rendition["id"],
                "codec": rendition["codec"],
                "container": rendition.get("container"),
                "bitrateKbps": rendition.get("bitrate_kbps"),
                "sampleRate": rendition.get("sample_rate"),
                "bitDepth": rendition.get("bit_depth"),
                "sizeBytes": int(rendition.get("size_bytes") or 0),
                "sha256": (
                    rendition["sha256"].hex()
                    if isinstance(rendition.get("sha256"), (bytes, bytearray))
                    else (rendition.get("sha256") or "")
                ),
            }
            for rendition in (renditions or [])
        ],
    }


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
        SELECT t.id, t.title, t.duration_ms, t.disc_number, t.track_number,
               t.gapless_next, t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db, t.metadata,
               t.created_at, t.updated_at,
               t.artist_id, ar.name AS artist_name,
               t.album_id, al.title AS album_title, al.artwork_hash
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
        "tracks": [_track_json(row) for row in rows],
        "total": int(total or 0),
    }


@router.get("/catalog/albums")
async def list_albums(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT al.id, al.title, al.year, al.artwork_hash, al.updated_at,
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
                "artistName": row["artist_name"] or "",
                "albumType": None,
                "releaseYear": row["year"],
                "trackCount": int(row["track_count"]),
                "artwork": _artwork(row["artwork_hash"]),
                "dominantHex": None,
                "vibrantHex": None,
                "mutedHex": None,
                "tracks": [],
                "updatedAt": _epoch_ms(row["updated_at"]),
            }
            for row in rows
        ]
    }


@router.get("/catalog/artists")
async def list_artists(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    rows = await conn.fetch(
        """
        SELECT ar.id, ar.name, ar.sort_name, ar.updated_at,
               count(DISTINCT al.id) AS album_count
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
                "sortName": row["sort_name"] or row["name"],
                "imageHash": None,
                "albumCount": int(row["album_count"]),
                "updatedAt": _epoch_ms(row["updated_at"]),
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
                "ownerId": str(principal.user_id),
                "name": row["name"],
                "description": row["description"],
                "isCollaborative": False,
                "artwork": None,
                "trackCount": int(row["track_count"]),
                "updatedAt": _epoch_ms(row["updated_at"]),
            }
            for row in rows
        ]
    }


@router.get("/albums/{album_id}")
async def get_album(conn: DbConn, principal: PrincipalDep, album_id: UUID) -> dict[str, Any]:
    album = await conn.fetchrow(
        """
        SELECT al.id, al.title, al.year, al.artwork_hash, al.updated_at,
               ar.name AS artist_name
        FROM albums al
        LEFT JOIN artists ar ON ar.id = al.artist_id
        WHERE al.id = $1
        """,
        album_id,
    )
    if album is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown album")

    tracks = await conn.fetch(
        """
        SELECT t.id, t.title, t.duration_ms, t.disc_number, t.track_number,
               t.gapless_next, t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db, t.metadata,
               t.created_at, t.updated_at,
               t.artist_id, ar.name AS artist_name,
               t.album_id, al.title AS album_title, al.artwork_hash
        FROM tracks t
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.album_id = $1 AND t.deleted_at IS NULL
        ORDER BY t.disc_number ASC, t.track_number ASC NULLS LAST, t.title ASC
        """,
        album_id,
    )
    return {
        "id": str(album["id"]),
        "title": album["title"],
        "artistName": album["artist_name"] or "",
        "albumType": None,
        "releaseYear": album["year"],
        "trackCount": len(tracks),
        "artwork": _artwork(album["artwork_hash"]),
        "dominantHex": None,
        "vibrantHex": None,
        "mutedHex": None,
        "tracks": [_track_json(row) for row in tracks],
        "updatedAt": _epoch_ms(album["updated_at"]),
    }


@router.get("/artists/{artist_id}")
async def get_artist(conn: DbConn, principal: PrincipalDep, artist_id: UUID) -> dict[str, Any]:
    artist = await conn.fetchrow(
        "SELECT id, name, sort_name, updated_at FROM artists WHERE id = $1",
        artist_id,
    )
    if artist is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown artist")
    return {
        "id": str(artist["id"]),
        "name": artist["name"],
        "sortName": artist["sort_name"] or artist["name"],
        "imageHash": None,
        "updatedAt": _epoch_ms(artist["updated_at"]),
    }


@router.get("/artists/{artist_id}/tracks")
async def get_artist_tracks(
    conn: DbConn,
    principal: PrincipalDep,
    artist_id: UUID,
) -> list[dict[str, Any]]:
    rows = await conn.fetch(
        """
        SELECT t.id, t.title, t.duration_ms, t.disc_number, t.track_number,
               t.gapless_next, t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db, t.metadata,
               t.created_at, t.updated_at,
               t.artist_id, ar.name AS artist_name,
               t.album_id, al.title AS album_title, al.artwork_hash
        FROM tracks t
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.artist_id = $1 AND t.deleted_at IS NULL
        ORDER BY t.created_at DESC, t.title ASC
        """,
        artist_id,
    )
    return [_track_json(row) for row in rows]


@router.get("/favorites")
async def list_favorites(conn: DbConn, principal: PrincipalDep) -> list[str]:
    rows = await conn.fetch(
        "SELECT track_id FROM favorites WHERE user_id = $1 ORDER BY created_at DESC",
        principal.user_id,
    )
    return [str(row["track_id"]) for row in rows]


@router.get("/playlists/{playlist_id}")
async def get_playlist(
    conn: DbConn,
    principal: PrincipalDep,
    playlist_id: UUID,
) -> dict[str, Any]:
    row = await conn.fetchrow(
        """
        SELECT p.id, p.owner_id, p.name, p.description, p.updated_at,
               count(pe.id) AS track_count
        FROM playlists p
        LEFT JOIN playlist_entries pe ON pe.playlist_id = p.id
        WHERE p.id = $1 AND p.owner_id = $2
        GROUP BY p.id
        """,
        playlist_id,
        principal.user_id,
    )
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown playlist")
    return {
        "id": str(row["id"]),
        "ownerId": str(row["owner_id"]),
        "name": row["name"],
        "description": row["description"],
        "isCollaborative": False,
        "artwork": None,
        "trackCount": int(row["track_count"]),
        "updatedAt": _epoch_ms(row["updated_at"]),
    }


@router.get("/playlists/{playlist_id}/tracks")
async def get_playlist_tracks(
    conn: DbConn,
    principal: PrincipalDep,
    playlist_id: UUID,
) -> list[dict[str, Any]]:
    rows = await conn.fetch(
        """
        SELECT t.id, t.title, t.duration_ms, t.disc_number, t.track_number,
               t.gapless_next, t.loudness_lufs, t.true_peak_db,
               t.album_loudness_lufs, t.album_peak_db, t.metadata,
               t.created_at, t.updated_at,
               t.artist_id, ar.name AS artist_name,
               t.album_id, al.title AS album_title, al.artwork_hash
        FROM playlist_entries pe
        JOIN playlists p ON p.id = pe.playlist_id
        JOIN tracks t ON t.id = pe.track_id
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE pe.playlist_id = $1 AND p.owner_id = $2 AND t.deleted_at IS NULL
        ORDER BY pe.position_key ASC
        """,
        playlist_id,
        principal.user_id,
    )
    return [_track_json(row) for row in rows]


@router.post("/playlists")
async def create_playlist(
    conn: DbConn,
    principal: PrincipalDep,
    body: dict[str, Any],
) -> dict[str, Any]:
    name = str(body.get("name") or "").strip()
    if not name:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="name is required")
    row = await conn.fetchrow(
        """
        INSERT INTO playlists (id, owner_id, name, description)
        VALUES (gen_random_uuid(), $1, $2, $3)
        RETURNING id, owner_id, name, description, updated_at
        """,
        principal.user_id,
        name,
        body.get("description"),
    )
    return {
        "id": str(row["id"]),
        "ownerId": str(row["owner_id"]),
        "name": row["name"],
        "description": row["description"],
        "isCollaborative": False,
        "artwork": None,
        "trackCount": 0,
        "updatedAt": _epoch_ms(row["updated_at"]),
    }


@router.post("/playlists/{playlist_id}/tracks")
async def add_playlist_track(
    conn: DbConn,
    principal: PrincipalDep,
    playlist_id: UUID,
    body: dict[str, Any],
) -> dict[str, str]:
    raw_track = body.get("trackId") or body.get("track_id")
    try:
        track_id = UUID(str(raw_track))
    except (ValueError, TypeError):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="trackId is required") from None
    position_key = body.get("positionKey") or body.get("position_key") or f"{int(datetime.now(timezone.utc).timestamp() * 1000):013d}"
    owned = await conn.fetchval(
        "SELECT 1 FROM playlists WHERE id = $1 AND owner_id = $2",
        playlist_id,
        principal.user_id,
    )
    if owned is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown playlist")
    await conn.execute(
        """
        INSERT INTO playlist_entries (id, playlist_id, position_key, track_id, added_by)
        VALUES (gen_random_uuid(), $1, $2, $3, $4)
        """,
        playlist_id,
        position_key,
        track_id,
        principal.user_id,
    )
    return {"status": "ok"}


@router.delete("/playlists/{playlist_id}/tracks/{track_id}")
async def remove_playlist_track(
    conn: DbConn,
    principal: PrincipalDep,
    playlist_id: UUID,
    track_id: UUID,
) -> dict[str, str]:
    owned = await conn.fetchval(
        "SELECT 1 FROM playlists WHERE id = $1 AND owner_id = $2",
        playlist_id,
        principal.user_id,
    )
    if owned is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown playlist")
    await conn.execute(
        "DELETE FROM playlist_entries WHERE playlist_id = $1 AND track_id = $2",
        playlist_id,
        track_id,
    )
    return {"status": "ok"}


@router.get("/catalog/sync")
async def catalog_sync(
    conn: DbConn,
    principal: PrincipalDep,
    cursor: str | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 200,
) -> dict[str, Any]:
    """One page of the catalog for the offline replica.

    The Android client stores the cursor and resumes from it after a process death.
    `nextCursor` is an opaque string here (an offset) so the transport does not leak
    the pagination strategy into the client.
    """
    try:
        offset = int(cursor) if cursor else 0
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="cursor must be an integer offset",
        ) from None
    if offset < 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid cursor")

    tracks = await list_tracks(conn, principal, limit=limit, offset=offset)
    albums = await list_albums(conn, principal)
    artists = await list_artists(conn, principal)
    playlists = await list_playlists(conn, principal)
    next_cursor = str(offset + limit) if len(tracks["tracks"]) == limit else None
    return {
        "tracks": tracks["tracks"],
        "albums": albums["albums"],
        "artists": artists["artists"],
        "playlists": playlists["playlists"],
        "nextCursor": next_cursor,
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
               t.gapless_next, t.availability, t.metadata,
               t.created_at, t.updated_at,
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
        SELECT r.id, r.codec, r.container, r.sample_rate, r.bit_depth,
               r.size_bytes, r.bitrate_kbps, r.sha256
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

    payload = _track_json(track, renditions=list(renditions))
    payload.update(
        {
            "codec": track["codec"],
            "sampleRate": track["sample_rate"],
            "bitDepth": track["bit_depth"],
            "channels": track["channels"],
            "availability": track["availability"],
        }
    )
    return payload


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
# Home + lyrics
# ---------------------------------------------------------------------------


def _home_card(
    *,
    card_id: str,
    title: str,
    subtitle: str | None,
    artwork_hash: str | None,
    target_type: str,
    target_id: str,
) -> dict[str, Any]:
    return {
        "id": card_id,
        "title": title,
        "subtitle": subtitle,
        "artworkHash": artwork_hash,
        "targetType": target_type,
        "targetId": target_id,
    }


@router.get("/recommendations/home")
async def home_sections(conn: DbConn, principal: PrincipalDep) -> dict[str, Any]:
    """Shelf layout for the Home tab (§6.2).

    The catalog is small enough today that every shelf is a simple indexed query;
    nothing here is personalised beyond favourites and play history, which is what
    the client can actually render. Empty shelves are omitted so the UI never shows
    a heading with no cards.
    """
    shelves: list[dict[str, Any]] = []

    recent = await conn.fetch(
        """
        SELECT t.id, t.title, ar.name AS artist_name, al.artwork_hash
        FROM tracks t
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE t.deleted_at IS NULL
        ORDER BY t.created_at DESC
        LIMIT 20
        """,
    )
    if recent:
        shelves.append(
            {
                "id": "recently-added",
                "title": "Recently added",
                "reason": None,
                "cards": [
                    _home_card(
                        card_id=str(row["id"]),
                        title=row["title"],
                        subtitle=row["artist_name"],
                        artwork_hash=row["artwork_hash"],
                        target_type="track",
                        target_id=str(row["id"]),
                    )
                    for row in recent
                ],
            },
        )

    favorites = await conn.fetch(
        """
        SELECT t.id, t.title, ar.name AS artist_name, al.artwork_hash
        FROM favorites f
        JOIN tracks t ON t.id = f.track_id AND t.deleted_at IS NULL
        LEFT JOIN artists ar ON ar.id = t.artist_id
        LEFT JOIN albums al ON al.id = t.album_id
        WHERE f.user_id = $1
        ORDER BY f.created_at DESC
        LIMIT 20
        """,
        principal.user_id,
    )
    if favorites:
        shelves.append(
            {
                "id": "favorites",
                "title": "Your favorites",
                "reason": None,
                "cards": [
                    _home_card(
                        card_id=str(row["id"]),
                        title=row["title"],
                        subtitle=row["artist_name"],
                        artwork_hash=row["artwork_hash"],
                        target_type="track",
                        target_id=str(row["id"]),
                    )
                    for row in favorites
                ],
            },
        )

    playlists = await conn.fetch(
        """
        SELECT p.id, p.name, count(pe.id) AS track_count
        FROM playlists p
        LEFT JOIN playlist_entries pe ON pe.playlist_id = p.id
        WHERE p.owner_id = $1
        GROUP BY p.id
        ORDER BY p.updated_at DESC
        LIMIT 20
        """,
        principal.user_id,
    )
    if playlists:
        shelves.append(
            {
                "id": "playlists",
                "title": "Your playlists",
                "reason": None,
                "cards": [
                    _home_card(
                        card_id=str(row["id"]),
                        title=row["name"],
                        subtitle=f"{int(row['track_count'])} tracks",
                        artwork_hash=None,
                        target_type="playlist",
                        target_id=str(row["id"]),
                    )
                    for row in playlists
                ],
            },
        )

    return {"shelves": shelves}


# Matches LRC timestamps such as `[01:23.45]` or `[01:23]`.
_LRC_TIMESTAMP: Final = re.compile(r"^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]\s?")


def _lyric_lines(value: Any) -> tuple[list[dict[str, Any]], bool]:
    """Normalises stored lyrics into the client's line list.

    Accepted shapes: a plain multi-line string (unsynced), an LRC string (synced),
    or an already-structured list from a future ingest pipeline.
    """
    if isinstance(value, list):
        structured = [
            item
            for item in value
            if isinstance(item, dict) and item.get("text") is not None
        ]
        lines = [
            {
                "timeMs": int(item["timeMs"]) if item.get("timeMs") is not None else None,
                "text": str(item["text"]),
            }
            for item in structured
        ]
        return lines, any(item.get("timeMs") is not None for item in structured)

    if not isinstance(value, str) or not value.strip():
        return [], False

    lines: list[dict[str, Any]] = []
    synced = False
    for raw in value.splitlines():
        match = _LRC_TIMESTAMP.match(raw)
        if match:
            minutes, seconds, fraction = match.groups()
            millis = int(fraction.ljust(3, "0")) if fraction else 0
            lines.append(
                {
                    "timeMs": (int(minutes) * 60 + int(seconds)) * 1000 + millis,
                    "text": raw[match.end():],
                },
            )
            synced = True
        else:
            lines.append({"timeMs": None, "text": raw})
    return lines, synced


@router.get("/lyrics/{track_id}")
async def get_lyrics(conn: DbConn, principal: PrincipalDep, track_id: UUID) -> dict[str, Any]:
    """Lyrics for one track (§6.4).

    Ingest stores sidecar lyrics under `metadata.lyrics`; there is no separate table
    yet. A track without lyrics is a 200 with an empty line list rather than a 404:
    the client treats "no lyrics" as a normal state and a 404 would surface as an
    error banner.
    """
    metadata = await conn.fetchval(
        "SELECT metadata FROM tracks WHERE id = $1 AND deleted_at IS NULL",
        track_id,
    )
    if metadata is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown track")

    stored = metadata.get("lyrics") if isinstance(metadata, dict) else None
    lines, synced = _lyric_lines(stored)
    return {
        "trackId": str(track_id),
        "lines": lines,
        "synced": synced,
        "provider": "embedded" if lines else None,
        "attribution": None,
    }


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
@router.post("/history/events", response_model=AcceptedResponse)
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