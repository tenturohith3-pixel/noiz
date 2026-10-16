"""Inbox scanning + library indexing (§4.3, ADR 3).

Everything in here is deliberately idempotent so a crashed or duplicated scan is
safe to re-run:

* `tracks.id` is `uuid5(NAMESPACE, "track:<sha256 hex>")` — the *content* is the
  primary key, so the same bytes always resolve to the same row and the upsert
  updates in place instead of appending a duplicate.
* `artists.id` / `albums.id` are likewise derived from their normalised names.
* `renditions` is keyed `(track_id, id)` with `id = 'orig'`, so a re-scan
  refreshes size/path/metadata rather than inserting a second rendition.
* A file whose `sha256` is already present in `renditions` is skipped entirely,
  which is what makes the nightly `sweep_inbox` cheap.

Nothing follows a symlink that leaves the root it was given: `os.walk` runs with
`followlinks=False`, and every candidate file/dir is re-checked with `resolve()`
against the root before it is touched (§12).

Celery tasks are thin wrappers. The actual work is the coroutines
(`scan_inbox`, `index_directory`), so `noiz.ingest.seed` can run the identical
code path with no broker in the picture.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import uuid
from collections.abc import AsyncIterator, Callable, Coroutine, Sequence
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Final

import asyncpg
from celery import shared_task

from noiz.core.db import close_pool, get_pool, init_pool, require_extensions
from noiz.core.settings import Settings, get_settings

logger = logging.getLogger("noiz.ingest")

try:  # pragma: no cover - import guard keeps Celery task registration alive
    import mutagen
except ImportError:  # pragma: no cover
    mutagen = None  # type: ignore[assignment]


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

AUDIO_SUFFIXES: Final = frozenset(
    {".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".alac", ".aiff"}
)

# `renditions.id` is TEXT in the schema and `stream.py` matches on these literals.
ORIGINAL_RENDITION: Final = "orig"

# Fallback prefix for files that live outside `media_root` (an inbox drop). The
# streaming layer rejects any rendition path that does not resolve under
# `media_root`, so such a row is readable in the catalog but not yet playable —
# moving the file into the library is what makes it streamable.
_UNROOTED_PREFIX: Final = "inbox"

# Content-addressed id derivation. Fixed namespace => stable ids across runs,
# processes and hosts, which is what makes the upserts idempotent.
_NAMESPACE: Final = uuid.uuid5(uuid.NAMESPACE_DNS, "noiz.ingest.v1")

# suffix -> (codec, container). `codec` must land in catalog.LOSSLESS_CODECS for
# lossless files so the client shows the lossless badge.
_SUFFIX_FORMATS: Final[dict[str, tuple[str, str]]] = {
    ".flac": ("flac", "flac"),
    ".mp3": ("mp3", "mp3"),
    ".m4a": ("aac", "mp4"),
    ".aac": ("aac", "adts"),
    ".ogg": ("vorbis", "ogg"),
    ".opus": ("opus", "opus"),
    ".wav": ("pcm", "wav"),
    ".alac": ("alac", "m4a"),
    ".aiff": ("pcm", "aiff"),
}

_FFPROBE_TIMEOUT: Final = 30

_ARTICLE_RE: Final = re.compile(r"^(?:the|a|an)\s+", re.IGNORECASE)

# Tag keys differ per container: Vorbis comments are lowercase and already
# readable, ID3 uses frame ids. Normalise both onto the same names.
_TAG_ALIASES: Final[dict[str, str]] = {
    "title": "title",
    "tit2": "title",
    "inam": "title",
    "©nam": "title",
    "artist": "artist",
    "tpe1": "artist",
    "©art": "artist",
    "albumartist": "albumartist",
    "album artist": "albumartist",
    "tpe2": "albumartist",
    "aart": "albumartist",
    "album": "album",
    "talb": "album",
    "©alb": "album",
    "tracknumber": "tracknumber",
    "track": "tracknumber",
    "trck": "tracknumber",
    "tracktotal": "tracknumber",
    "discnumber": "discnumber",
    "disc": "discnumber",
    "tpos": "discnumber",
    "date": "date",
    "year": "date",
    "originaldate": "date",
    "tyer": "date",
    "tdrc": "date",
    "genre": "genre",
    "tcon": "genre",
}


class ProbeError(RuntimeError):
    """Neither mutagen nor ffprobe could describe the file."""


# ---------------------------------------------------------------------------
# Filesystem walk
# ---------------------------------------------------------------------------


def _within(candidate: Path, root: Path) -> bool:
    """True when `candidate` resolves to something at or under `root`."""
    try:
        candidate.resolve().relative_to(root)
    except (ValueError, OSError):
        return False
    return True


def list_audio_files(root: Path) -> list[Path]:
    """Every audio file under `root`, sorted, never following escaping symlinks.

    `os.walk(..., followlinks=False)` does not descend into symlinked directories
    at all. That is not sufficient on its own: a symlinked *file* inside the root
    is still listed, and a symlinked directory may be visited when it points at
    another in-root directory. Every entry is therefore re-resolved and dropped
    unless it stays under the root.
    """
    root = Path(root)
    if not root.is_dir():
        return []

    anchor = root.resolve()
    found: list[Path] = []

    for dirpath, dirnames, filenames in os.walk(anchor, followlinks=False, onerror=lambda _e: None):
        current = Path(dirpath)

        # Prune in place so os.walk does not descend into them.
        safe_dirs: list[str] = []
        for name in sorted(dirnames):
            child = current / name
            if child.is_symlink() and not _within(child, anchor):
                logger.warning("skipping symlinked directory outside the root: %s", child)
                continue
            safe_dirs.append(name)
        dirnames[:] = safe_dirs

        for name in sorted(filenames):
            if Path(name).suffix.lower() not in AUDIO_SUFFIXES:
                continue
            candidate = current / name
            if candidate.is_symlink() and not _within(candidate, anchor):
                logger.warning("skipping symlinked file outside the root: %s", candidate)
                continue
            if not candidate.is_file():
                continue
            found.append(candidate)

    return found


# ---------------------------------------------------------------------------
# Probing
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class Probe:
    """Everything the catalog rows need, gathered from one file."""

    duration_ms: int
    codec: str
    container: str
    size_bytes: int
    sha256: bytes
    sample_rate: int | None = None
    bit_depth: int | None = None
    channels: int | None = None
    bitrate_kbps: int | None = None
    title: str | None = None
    artist: str | None = None
    album_artist: str | None = None
    album: str | None = None
    track_number: int | None = None
    disc_number: int | None = None
    year: int | None = None
    genre: str | None = None

    @property
    def display_title(self) -> str:
        return self.title or "Unknown title"

    def metadata(self) -> dict[str, Any]:
        return {
            "ingest": {
                "title": self.title,
                "artist": self.artist,
                "albumArtist": self.album_artist,
                "album": self.album,
                "trackNumber": self.track_number,
                "discNumber": self.disc_number,
                "year": self.year,
                "genre": self.genre,
                "bitrateKbps": self.bitrate_kbps,
            }
        }


def sha256_file(path: Path, *, chunk_bytes: int = 1024 * 1024) -> bytes:
    """Raw 32-byte digest — `renditions.sha256` is `BYTEA`, not hex text."""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(chunk_bytes), b""):
            digest.update(block)
    return digest.digest()


def _tag_map(tags: Any) -> dict[str, str]:
    """Flatten a mutagen/ffprobe tag mapping onto canonical lowercase names."""
    out: dict[str, str] = {}
    if not tags:
        return out
    try:
        items = list(tags.items())
    except (AttributeError, TypeError):
        return out
    for raw_key, raw_value in items:
        canonical = _TAG_ALIASES.get(str(raw_key).strip().lower())
        if canonical is None or canonical in out:
            continue
        # Multi-valued frames (ID3 v2.4 separates on NUL) collapse to the first.
        text = str(raw_value).split("\x00")[0].strip()
        if text:
            out[canonical] = text
    return out


def _int_or_none(value: Any) -> int | None:
    """`"3/12"`, `"3"`, `3` and `" 3 "` all mean 3; anything else is None."""
    if value is None:
        return None
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if value > 0 else None
    if isinstance(value, float):
        return int(value) if value > 0 else None
    head = str(value).split("/")[0].strip()
    if not head.isdigit():
        return None
    number = int(head)
    return number if number > 0 else None


def _year_or_none(value: Any) -> int | None:
    if value is None:
        return None
    head = str(value).strip()[:4]
    if len(head) != 4 or not head.isdigit():
        return None
    year = int(head)
    return year if 1000 <= year <= 9999 else None


def _codec_for(suffix: str, mime: str | None) -> str:
    """Prefer the container's declared mime, fall back to the file suffix."""
    if mime:
        normalised = mime.split(";")[0].strip().lower()
        by_mime = {
            "audio/flac": "flac",
            "audio/x-flac": "flac",
            "audio/mpeg": "mp3",
            "audio/mp4": "aac",
            "audio/x-m4a": "aac",
            "audio/aac": "aac",
            "audio/ogg": "vorbis",
            "audio/opus": "opus",
            "audio/x-opus+ogg": "opus",
            "audio/wav": "pcm",
            "audio/x-wav": "pcm",
            "audio/wave": "pcm",
            "audio/x-aiff": "pcm",
            "audio/aiff": "pcm",
        }
        if normalised in by_mime:
            return by_mime[normalised]
    return _SUFFIX_FORMATS.get(suffix, ("pcm", ""))[0]


def _container_for(suffix: str) -> str:
    return _SUFFIX_FORMATS.get(suffix, ("pcm", ""))[1] or suffix.lstrip(".")


def _probe_mutagen(path: Path) -> Probe:
    if mutagen is None:
        raise ProbeError("mutagen is not installed")

    audio = mutagen.File(path)
    if audio is None or getattr(audio, "info", None) is None:
        raise ProbeError(f"mutagen could not open {path.name}")

    info = audio.info
    tags = _tag_map(getattr(audio, "tags", None))

    suffix = path.suffix.lower()
    mimes = getattr(audio, "mime", None) or []
    mime = mimes[0] if mimes else None

    bitrate = getattr(info, "bitrate", None)
    channels = getattr(info, "channels", None)

    return Probe(
        duration_ms=max(0, int(round(float(getattr(info, "length", 0.0) or 0.0) * 1000))),
        codec=_codec_for(suffix, mime),
        container=_container_for(suffix),
        size_bytes=path.stat().st_size,
        sha256=b"",
        sample_rate=int(getattr(info, "sample_rate", 0) or 0) or None,
        bit_depth=int(getattr(info, "bits_per_sample", 0) or 0) or None,
        channels=int(channels) if channels else None,
        bitrate_kbps=int(bitrate / 1000) if bitrate else None,
        title=tags.get("title"),
        artist=tags.get("artist"),
        album_artist=tags.get("albumartist") or tags.get("artist"),
        album=tags.get("album"),
        track_number=_int_or_none(tags.get("tracknumber")),
        disc_number=_int_or_none(tags.get("discnumber")),
        year=_year_or_none(tags.get("date")),
        genre=tags.get("genre"),
    )


_FFPROBE_CODECS: Final[dict[str, str]] = {
    "flac": "flac",
    "mp3": "mp3",
    "mp3float": "mp3",
    "aac": "aac",
    "alac": "alac",
    "opus": "opus",
    "vorbis": "vorbis",
    "pcm_s16le": "pcm",
    "pcm_s24le": "pcm",
    "pcm_s32le": "pcm",
    "pcm_f32le": "pcm",
    "pcm_s16be": "pcm",
    "pcm_s24be": "pcm",
}


def _probe_ffprobe(path: Path) -> Probe:
    """Fallback for files mutagen refuses (raw streams, exotic containers)."""
    if shutil.which("ffprobe") is None:
        raise ProbeError("ffprobe is not installed")

    completed = subprocess.run(
        [
            "ffprobe",
            "-v", "error",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            str(path),
        ],
        capture_output=True,
        timeout=_FFPROBE_TIMEOUT,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", "replace").strip()[:400]
        raise ProbeError(f"ffprobe failed for {path.name}: {detail or 'no output'}")

    try:
        payload = json.loads(completed.stdout.decode("utf-8", "replace") or "{}")
    except json.JSONDecodeError as exc:
        raise ProbeError(f"ffprobe returned invalid JSON for {path.name}") from exc

    streams = payload.get("streams") or []
    audio_stream = next((s for s in streams if s.get("codec_type") == "audio"), None)
    fmt = payload.get("format") or {}
    if audio_stream is None and not fmt:
        raise ProbeError(f"no audio stream in {path.name}")

    tags = _tag_map(fmt.get("tags")) or {}
    if audio_stream is not None:
        for key, value in _tag_map(audio_stream.get("tags")).items():
            tags.setdefault(key, value)

    duration = audio_stream.get("duration") if audio_stream else None
    if duration is None:
        duration = fmt.get("duration")
    try:
        duration_ms = max(0, int(round(float(duration or 0.0) * 1000)))
    except (TypeError, ValueError):
        duration_ms = 0

    bitrate = fmt.get("bit_rate") or (audio_stream or {}).get("bit_rate")
    try:
        bitrate_kbps = int(bitrate) // 1000 if bitrate else None
    except (TypeError, ValueError):
        bitrate_kbps = None

    bit_depth = None
    if audio_stream is not None:
        for key in ("bits_per_sample", "bits_per_raw_sample"):
            raw = audio_stream.get(key)
            if raw:
                try:
                    bit_depth = int(raw)
                except (TypeError, ValueError):
                    bit_depth = None
                break

    codec_name = ((audio_stream or {}).get("codec_name") or "").lower()
    suffix = path.suffix.lower()

    return Probe(
        duration_ms=duration_ms,
        codec=_FFPROBE_CODECS.get(codec_name) or _codec_for(suffix, None),
        container=_container_for(suffix),
        size_bytes=path.stat().st_size,
        sha256=b"",
        sample_rate=int(audio_stream["sample_rate"]) if audio_stream and audio_stream.get("sample_rate") else None,
        bit_depth=bit_depth,
        channels=int(audio_stream["channels"]) if audio_stream and audio_stream.get("channels") else None,
        bitrate_kbps=bitrate_kbps,
        title=tags.get("title"),
        artist=tags.get("artist"),
        album_artist=tags.get("albumartist") or tags.get("artist"),
        album=tags.get("album"),
        track_number=_int_or_none(tags.get("tracknumber")),
        disc_number=_int_or_none(tags.get("discnumber")),
        year=_year_or_none(tags.get("date")),
        genre=tags.get("genre"),
    )


def probe_file(path: Path) -> Probe:
    """Probe with mutagen, falling back to ffprobe, then attaching the digest."""
    try:
        probe = _probe_mutagen(path)
    except Exception as exc:  # noqa: BLE001 - mutagen raises many unrelated types
        logger.info("mutagen could not read %s (%s); trying ffprobe", path.name, exc)
        try:
            probe = _probe_ffprobe(path)
        except Exception as fallback_exc:  # noqa: BLE001
            raise ProbeError(f"{path}: {fallback_exc}") from exc

    probe.sha256 = sha256_file(path)
    probe.size_bytes = path.stat().st_size
    if probe.duration_ms <= 0:
        logger.warning("no duration reported for %s; storing 0 ms", path.name)
    return probe


# ---------------------------------------------------------------------------
# Ids
# ---------------------------------------------------------------------------


def _normalise(value: str | None) -> str:
    return " ".join((value or "").split()).casefold()


def _sort_name(value: str | None) -> str:
    cleaned = " ".join((value or "").split())
    stripped = _ARTICLE_RE.sub("", cleaned).strip()
    return stripped or cleaned


def track_id_for(sha256: bytes) -> uuid.UUID:
    return uuid.uuid5(_NAMESPACE, f"track:{sha256.hex()}")


def artist_id_for(name: str) -> uuid.UUID:
    return uuid.uuid5(_NAMESPACE, f"artist:{_normalise(name)}")


def album_id_for(artist_id: uuid.UUID | None, title: str) -> uuid.UUID:
    artist_key = str(artist_id) if artist_id else ""
    return uuid.uuid5(_NAMESPACE, f"album:{artist_key}:{_normalise(title)}")


# ---------------------------------------------------------------------------
# Database writes
# ---------------------------------------------------------------------------


UPSERT_ARTIST: Final = """
    INSERT INTO artists (id, name, sort_name, normalized_name, metadata)
    VALUES ($1, $2, $3, $4, $5::jsonb)
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        sort_name = EXCLUDED.sort_name,
        normalized_name = EXCLUDED.normalized_name
    RETURNING id
"""

UPSERT_ALBUM: Final = """
    INSERT INTO albums (id, artist_id, title, sort_title, year, metadata)
    VALUES ($1, $2, $3, $4, $5, $6::jsonb)
    ON CONFLICT (id) DO UPDATE SET
        artist_id = EXCLUDED.artist_id,
        title = EXCLUDED.title,
        sort_title = EXCLUDED.sort_title,
        year = COALESCE(EXCLUDED.year, albums.year)
    RETURNING id
"""

UPSERT_TRACK: Final = """
    INSERT INTO tracks (
        id, album_id, artist_id, title, disc_number, track_number, duration_ms,
        codec, sample_rate, bit_depth, channels, metadata, deleted_at
    )
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12::jsonb, NULL)
    ON CONFLICT (id) DO UPDATE SET
        album_id = EXCLUDED.album_id,
        artist_id = EXCLUDED.artist_id,
        title = EXCLUDED.title,
        disc_number = EXCLUDED.disc_number,
        track_number = EXCLUDED.track_number,
        duration_ms = EXCLUDED.duration_ms,
        codec = EXCLUDED.codec,
        sample_rate = EXCLUDED.sample_rate,
        bit_depth = EXCLUDED.bit_depth,
        channels = EXCLUDED.channels,
        -- `deleted_at` is deliberately NOT reset. Ingest must not resurrect a
        -- track an operator tombstoned; the nightly sweep would otherwise undo
        -- every delete within five minutes.
        metadata = tracks.metadata || EXCLUDED.metadata
    RETURNING id
"""

UPSERT_RENDITION: Final = """
    INSERT INTO renditions (
        id, track_id, codec, container, bitrate_kbps, sample_rate, bit_depth,
        size_bytes, sha256, path
    )
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
    ON CONFLICT (track_id, id) DO UPDATE SET
        codec = EXCLUDED.codec,
        container = EXCLUDED.container,
        bitrate_kbps = EXCLUDED.bitrate_kbps,
        sample_rate = EXCLUDED.sample_rate,
        bit_depth = EXCLUDED.bit_depth,
        size_bytes = EXCLUDED.size_bytes,
        sha256 = EXCLUDED.sha256,
        path = EXCLUDED.path
    RETURNING track_id
"""


def _relative_media_path(path: Path, settings: Settings) -> tuple[str, bool]:
    """`(path relative to media_root, is_inside_media_root)`.

    `renditions.path` is re-rooted against `settings.media_root` by `stream.py`,
    so the stored value must be relative to it. Inbox drops live on a different
    volume and get an `inbox/...` path that the streaming layer will (correctly)
    reject until the file is moved into the library.
    """
    media_root = settings.media_root.resolve()
    resolved = path.resolve()
    try:
        return resolved.relative_to(media_root).as_posix(), True
    except ValueError:
        pass

    inbox_root = settings.inbox_root.resolve()
    try:
        return f"{_UNROOTED_PREFIX}/{resolved.relative_to(inbox_root).as_posix()}", False
    except ValueError:
        return f"{_UNROOTED_PREFIX}/{resolved.name}", False


async def _index_probe(
    conn: asyncpg.Connection,
    settings: Settings,
    path: Path,
    probe: Probe,
) -> tuple[uuid.UUID, str, bool]:
    """Upsert one probed file. Returns `(track_id, stored_path, in_media_root)`."""
    artist_name = probe.album_artist or probe.artist

    artist_id: uuid.UUID | None = None
    if artist_name:
        artist_id = await conn.fetchval(
            UPSERT_ARTIST,
            artist_id_for(artist_name),
            " ".join(artist_name.split()),
            _sort_name(artist_name),
            _normalise(artist_name),
            json.dumps({"ingest": {"artist": probe.artist, "albumArtist": probe.album_artist}}),
        )

    album_id: uuid.UUID | None = None
    if probe.album:
        album_id = await conn.fetchval(
            UPSERT_ALBUM,
            album_id_for(artist_id, probe.album),
            artist_id,
            " ".join(probe.album.split()),
            _sort_name(probe.album),
            probe.year,
            json.dumps({"ingest": {"genre": probe.genre, "year": probe.year}}),
        )

    track_uuid = track_id_for(probe.sha256)
    written = await conn.fetchval(
        UPSERT_TRACK,
        track_uuid,
        album_id,
        artist_id,
        probe.display_title,
        probe.disc_number or 1,
        probe.track_number,
        probe.duration_ms,
        probe.codec,
        probe.sample_rate,
        probe.bit_depth,
        probe.channels,
        json.dumps(probe.metadata()),
    )
    track_uuid = written or track_uuid

    stored_path, in_media_root = _relative_media_path(path, settings)
    await conn.execute(
        UPSERT_RENDITION,
        ORIGINAL_RENDITION,
        track_uuid,
        probe.codec,
        probe.container,
        probe.bitrate_kbps,
        probe.sample_rate,
        probe.bit_depth,
        probe.size_bytes,
        probe.sha256,
        stored_path,
    )
    return track_uuid, stored_path, in_media_root


# ---------------------------------------------------------------------------
# Scan
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class ScanReport:
    """Outcome of one pass. `indexed` is the number the tasks return."""

    root: str = ""
    discovered: int = 0
    indexed: int = 0
    duplicates: int = 0
    failed: int = 0
    unrooted: int = 0
    track_ids: list[uuid.UUID] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "root": self.root,
            "discovered": self.discovered,
            "indexed": self.indexed,
            "duplicates": self.duplicates,
            "failed": self.failed,
            "unrooted": self.unrooted,
        }

    def __str__(self) -> str:
        return (
            f"discovered={self.discovered} indexed={self.indexed} "
            f"duplicates={self.duplicates} failed={self.failed} unrooted={self.unrooted}"
        )


async def _existing_digests(pool: asyncpg.Pool) -> set[bytes]:
    """Pre-load known content hashes so a re-scan costs one query, not one per file."""
    async with pool.acquire() as conn:
        rows = await conn.fetch("SELECT sha256 FROM renditions")
    return {bytes(row["sha256"]) for row in rows if row["sha256"]}


async def index_files(
    settings: Settings,
    files: Sequence[Path],
    *,
    pool: asyncpg.Pool | None = None,
    report: ScanReport | None = None,
) -> ScanReport:
    """Index an explicit list of files. The shared core of every entrypoint."""
    summary = report if report is not None else ScanReport()
    if not files:
        return summary

    if pool is None:
        # `get_pool()` raises when uninitialised, so the pool has to be created
        # before it is read back.
        await init_pool(settings)
        active_pool = get_pool()
        owned = True
    else:
        active_pool = pool
        owned = False

    known = await _existing_digests(active_pool)

    try:
        async with active_pool.acquire() as conn:
            for path in files:
                summary.discovered += 1
                try:
                    probe = await asyncio.to_thread(probe_file, path)
                except Exception as exc:  # noqa: BLE001 - one bad file must not stop the sweep
                    summary.failed += 1
                    logger.warning("skipping %s: %s", path, exc)
                    continue

                if probe.sha256 in known:
                    summary.duplicates += 1
                    continue

                try:
                    async with conn.transaction():
                        track_uuid, _stored, in_media_root = await _index_probe(
                            conn, settings, path, probe
                        )
                except Exception as exc:  # noqa: BLE001
                    summary.failed += 1
                    logger.exception("failed to index %s", path)
                    logger.debug("index error detail", exc_info=exc)
                    continue

                known.add(probe.sha256)
                summary.indexed += 1
                summary.track_ids.append(track_uuid)
                if not in_media_root:
                    summary.unrooted += 1
    finally:
        if owned:
            await close_pool()

    return summary


async def index_directory(
    settings: Settings,
    root: Path,
    *,
    pool: asyncpg.Pool | None = None,
) -> ScanReport:
    """Walk `root` and index everything under it."""
    root = Path(root)
    logger.info("scanning %s for audio files", root)
    files = await asyncio.to_thread(list_audio_files, root)
    report = ScanReport(root=str(root))
    if not files:
        logger.info("no audio files under %s", root)
        return report
    result = await index_files(settings, files, pool=pool, report=report)
    logger.info("scan of %s finished: %s", root, result)
    return result


async def scan_inbox(settings: Settings, *, pool: asyncpg.Pool | None = None) -> ScanReport:
    """Index `settings.inbox_root`. This is what the Celery task wraps."""
    return await index_directory(settings, settings.inbox_root, pool=pool)


# ---------------------------------------------------------------------------
# Session helper
# ---------------------------------------------------------------------------


async def _discard_stale_pool() -> None:
    """Drop a pool that is bound to an event loop which no longer exists.

    `worker._bootstrap_pool` creates the pool inside `anyio.run(...)`, and that
    loop closes as soon as the signal handler returns. Every later use of the
    cached pool on a fresh loop raises "Event loop is closed", so each ingest
    task starts from a clean pool instead.
    """
    from noiz.core import db as db_module

    if getattr(db_module, "_pool", None) is None:
        return
    try:
        await close_pool()
    except Exception:  # noqa: BLE001 - closing a dead-loop pool may itself raise
        logger.warning("discarding an unusable asyncpg pool", exc_info=True)
        db_module._pool = None  # noqa: SLF001 - close_pool() could not reset it


@asynccontextmanager
async def ingest_session(settings: Settings | None = None) -> AsyncIterator[asyncpg.Pool]:
    """Own an asyncpg pool for the duration of a synchronous Celery task or CLI run."""
    resolved = settings or get_settings()
    await _discard_stale_pool()
    pool = await init_pool(resolved)
    async with pool.acquire() as conn:
        await require_extensions(conn)
    try:
        yield pool
    finally:
        await close_pool()


def _run(builder: Callable[[Settings], Coroutine[Any, Any, Any]]) -> Any:
    """Run a coroutine factory inside a private loop and a private pool."""

    async def _main() -> Any:
        async with ingest_session() as pool:
            settings = get_settings()
            return await builder(settings)

    return asyncio.run(_main())


# ---------------------------------------------------------------------------
# Celery tasks
# ---------------------------------------------------------------------------


@shared_task(
    name="noiz.ingest.tasks.scan_inbox",
    queue="ingest",
    max_retries=3,
    default_retry_delay=30,
)
def scan_inbox_task() -> int:
    """Walk the inbox and upsert every new file. Returns the indexed count."""
    report = _run(lambda settings: scan_inbox(settings))
    return report.indexed


# Beat references `noiz.ingest.tasks.sweep_inbox`, so that is the exported name.
@shared_task(
    name="noiz.ingest.tasks.sweep_inbox",
    queue="ingest",
    max_retries=3,
    default_retry_delay=30,
)
def sweep_inbox() -> int:
    """Periodic settle-and-ingest for drops the inotify handler missed (§4.3)."""
    report = _run(lambda settings: scan_inbox(settings))
    return report.indexed


def _recompute_all_taste_impl() -> int:
    """Placeholder until the reco stage ships; keeps the beat entry resolvable."""
    logger.info("recompute_all_taste is a no-op stub (reco stage not implemented yet)")
    return 0


recompute_all_taste = shared_task(
    name="noiz.ingest.tasks.recompute_all_taste",
    queue="ingest",
)(_recompute_all_taste_impl)

# `worker.py`'s beat schedule points at `noiz.reco.tasks.recompute_all_taste`,
# and `noiz/reco/` does not exist yet. Register the same stub under that name so
# beat does not log an unresolvable task every night.
recompute_all_taste_beat_alias = shared_task(
    name="noiz.reco.tasks.recompute_all_taste",
    queue="ingest",
)(_recompute_all_taste_impl)


__all__ = [
    "AUDIO_SUFFIXES",
    "Probe",
    "ProbeError",
    "ScanReport",
    "index_directory",
    "index_files",
    "ingest_session",
    "list_audio_files",
    "probe_file",
    "recompute_all_taste",
    "scan_inbox",
    "scan_inbox_task",
    "sha256_file",
    "sweep_inbox",
]
