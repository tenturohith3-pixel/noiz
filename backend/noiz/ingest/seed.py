"""One-shot bootstrap: default admin + initial library import.

    python -m noiz.ingest.seed

Runs with no broker and no worker: it calls the ingest coroutines directly rather
than going through `.delay()`. Use it to take a fresh deployment from "empty
library" to "playable" in one step:

  1. create the default admin (`noiz` / `noiz`, Argon2id) if it does not exist;
  2. copy every audio file under the source tree (default `/home/rohith/Music`)
     into `settings.media_root/library/`, preserving the directory layout and the
     file extension;
  3. run the same indexing logic as `noiz.ingest.tasks.scan_inbox` over the
     library, so `tracks` and `renditions` actually have rows;
  4. print a summary.

Idempotent: re-running skips files that are already copied and files whose
sha256 is already in `renditions`.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

import asyncpg

from noiz.core.security import hash_password
from noiz.core.settings import Settings, get_settings
from noiz.ingest.tasks import (
    ScanReport,
    index_directory,
    ingest_session,
    list_audio_files,
)

logger = logging.getLogger("noiz.seed")

DEFAULT_SOURCE = Path("/home/rohith/Music")
DEFAULT_USERNAME = "noiz"
DEFAULT_PASSWORD = "noiz"
LIBRARY_DIRNAME = "library"


@dataclass(slots=True)
class CopyReport:
    found: int = 0
    copied: int = 0
    present: int = 0
    failed: int = 0


# ---------------------------------------------------------------------------
# Admin user
# ---------------------------------------------------------------------------


async def ensure_admin_user(
    conn: asyncpg.Connection,
    *,
    username: str = DEFAULT_USERNAME,
    password: str = DEFAULT_PASSWORD,
    display_name: str = "noiz admin",
) -> tuple[str, bool]:
    """Insert the bootstrap admin if absent. Returns `(username, created)`."""
    created = await conn.fetchval(
        """
        INSERT INTO users (
            id, username, password_hash, display_name, role, can_upload, can_download
        )
        VALUES (gen_random_uuid(), $1, $2, $3, 'admin', true, true)
        ON CONFLICT (username) DO NOTHING
        RETURNING id
        """,
        username,
        hash_password(password),
        display_name,
    )
    return username, created is not None


# ---------------------------------------------------------------------------
# Copy
# ---------------------------------------------------------------------------


def copy_into_library(source: Path, library_root: Path, files: Sequence[Path]) -> CopyReport:
    """Copy `files` into `library_root`, mirroring their layout under `source`."""
    report = CopyReport(found=len(files))
    library_root.mkdir(parents=True, exist_ok=True)
    anchor = library_root.resolve()

    for path in files:
        try:
            relative = path.resolve().relative_to(source.resolve())
        except ValueError:
            # Outside the declared source: fall back to a flat, name-based layout
            # rather than silently dropping the file.
            relative = Path("unsorted") / path.name

        destination = (library_root / relative).resolve()
        if not destination.is_relative_to(anchor):
            logger.warning("refusing to copy outside the library root: %s", destination)
            report.failed += 1
            continue

        destination.parent.mkdir(parents=True, exist_ok=True)

        if destination.is_file() and destination.stat().st_size == path.stat().st_size:
            report.present += 1
            continue

        # copy2 preserves mtime, which is what makes the size check above a
        # reasonable "already imported" test on a second run.
        shutil.copy2(path, destination)
        report.copied += 1

    return report


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------


async def seed(
    settings: Settings,
    *,
    source: Path,
    username: str,
    password: str,
) -> tuple[CopyReport, ScanReport, bool]:
    library_root = (settings.media_root / LIBRARY_DIRNAME).resolve()
    library_root.mkdir(parents=True, exist_ok=True)

    files = await asyncio.to_thread(list_audio_files, source)
    copies = await asyncio.to_thread(copy_into_library, source.resolve(), library_root, files)

    async with ingest_session(settings) as pool:
        async with pool.acquire() as conn:
            _username, admin_created = await ensure_admin_user(
                conn, username=username, password=password
            )
        scan = await index_directory(settings, library_root, pool=pool)

    return copies, scan, admin_created


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="python -m noiz.ingest.seed",
        description="Create the default admin user and import a local music folder.",
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_SOURCE,
        help=f"directory to import from (default: {DEFAULT_SOURCE})",
    )
    parser.add_argument(
        "--username",
        default=DEFAULT_USERNAME,
        help=f"admin username to create if absent (default: {DEFAULT_USERNAME})",
    )
    parser.add_argument(
        "--password",
        default=DEFAULT_PASSWORD,
        help="admin password to set when the user is created",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    logging.basicConfig(
        level=logging.INFO,
        format='{"ts":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","msg":"%(message)s"}',
    )

    settings = get_settings()
    source = args.source.expanduser().resolve()

    if not source.is_dir():
        print(f"source directory does not exist: {source}", file=sys.stderr)
        print(
            "nothing to do — start noiz first, then drop files into "
            f"{settings.inbox_root} or re-run with --source",
            file=sys.stderr,
        )
        return 1

    copies, scan, admin_created = asyncio.run(
        seed(settings, source=source, username=args.username, password=args.password)
    )

    state = "created" if admin_created else "already present"
    rows = [
        ("source", str(source)),
        ("library root", str((settings.media_root / LIBRARY_DIRNAME).resolve())),
        ("audio files found", copies.found),
        ("copied", copies.copied),
        ("already copied", copies.present),
        ("copy failures", copies.failed),
        ("tracks indexed", scan.indexed),
        ("skipped (duplicate sha256)", scan.duplicates),
        ("index failures", scan.failed),
        ("outside media root", scan.unrooted),
        ("admin", f"{args.username} / {args.password} ({state})"),
    ]
    width = max(len(label) for label, _ in rows)
    print("\nnoiz seed complete")
    print("-" * (width + 12))
    for label, value in rows:
        print(f"  {label.ljust(width)} : {value}")
    print("-" * (width + 12))

    if scan.indexed == 0 and copies.found:
        print(
            "\nnothing was indexed. Check the worker's log for 'failed to index', "
            "or run with --source pointing at the folder holding your audio.",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
