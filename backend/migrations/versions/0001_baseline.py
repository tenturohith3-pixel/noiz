"""baseline schema

Creates every table the API, the streaming layer and the Celery pipeline already
query. Until this revision lands the container has no schema at all: `auth.py`
fails on `users`, `stream.py` fails on `renditions`, and `require_extensions`
passes only because the extensions exist in the image.

Revision ID: 0001_baseline
Revises:
Create Date: 2026-01-01 00:00:00.000000
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa  # noqa: F401 - kept so autogenerate imports stay stable
from alembic import op

revision: str = "0001_baseline"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# ---------------------------------------------------------------------------
# Extensions
# ---------------------------------------------------------------------------


def upgrade() -> None:
    # pgvector: taste/recommendation embeddings. pg_trgm: ILIKE catalog search.
    # citext: case-insensitive identifiers.
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
    op.execute("CREATE EXTENSION IF NOT EXISTS citext")

    # Shared `updated_at` trigger. Every writer in the codebase that sets
    # `updated_at = now()` by hand will be replaced by this over time; until then
    # it guarantees a row can never carry a stale timestamp.
    op.execute(
        """
        CREATE OR REPLACE FUNCTION noiz_set_updated_at() RETURNS TRIGGER AS $$
        BEGIN
            NEW.updated_at = now();
            RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
    )

    # ------------------------------------------------------------------------
    # Identity
    # ------------------------------------------------------------------------
    op.execute(
        """
        CREATE TABLE users (
            id UUID PRIMARY KEY,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            display_name TEXT NOT NULL DEFAULT '',
            role TEXT NOT NULL DEFAULT 'listener'
                CHECK (role IN ('admin', 'listener')),
            can_upload BOOLEAN NOT NULL DEFAULT false,
            can_download BOOLEAN NOT NULL DEFAULT true,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER users_set_updated_at
            BEFORE UPDATE ON users
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )

    # `devices.id` is client-supplied and is the primary key: `auth.py` issues it
    # straight back inside the refresh token's `did` claim, so a surrogate id would
    # break token-to-row binding.
    op.execute(
        """
        CREATE TABLE devices (
            id UUID PRIMARY KEY,
            user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            platform TEXT NOT NULL,
            app_version TEXT,
            last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            refresh_token_hash TEXT,
            refresh_expires_at TIMESTAMPTZ,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute("CREATE INDEX devices_user_id_idx ON devices (user_id)")
    # Partial: only live rows carry a hash, and the lookup is always by digest.
    op.execute(
        """
        CREATE INDEX devices_refresh_token_hash_idx
            ON devices (refresh_token_hash)
            WHERE refresh_token_hash IS NOT NULL
        """
    )

    # ------------------------------------------------------------------------
    # Catalog
    # ------------------------------------------------------------------------
    op.execute(
        """
        CREATE TABLE artists (
            id UUID PRIMARY KEY,
            name TEXT NOT NULL,
            sort_name TEXT NOT NULL DEFAULT '',
            normalized_name TEXT NOT NULL DEFAULT '',
            musicbrainz_id TEXT,
            metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER artists_set_updated_at
            BEFORE UPDATE ON artists
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )
    op.execute("CREATE INDEX artists_normalized_name_idx ON artists (normalized_name)")
    op.execute(
        "CREATE UNIQUE INDEX artists_musicbrainz_id_idx "
        "ON artists (musicbrainz_id) WHERE musicbrainz_id IS NOT NULL"
    )

    op.execute(
        """
        CREATE TABLE albums (
            id UUID PRIMARY KEY,
            artist_id UUID REFERENCES artists (id) ON DELETE SET NULL,
            title TEXT NOT NULL,
            sort_title TEXT NOT NULL DEFAULT '',
            year SMALLINT,
            artwork_hash TEXT,
            metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER albums_set_updated_at
            BEFORE UPDATE ON albums
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )
    op.execute("CREATE INDEX albums_artist_id_idx ON albums (artist_id)")

    op.execute(
        """
        CREATE TABLE tracks (
            id UUID PRIMARY KEY,
            album_id UUID REFERENCES albums (id) ON DELETE SET NULL,
            artist_id UUID REFERENCES artists (id) ON DELETE SET NULL,
            title TEXT NOT NULL,
            disc_number SMALLINT NOT NULL DEFAULT 1,
            track_number SMALLINT,
            duration_ms BIGINT NOT NULL,
            codec TEXT NOT NULL,
            sample_rate INTEGER,
            bit_depth SMALLINT,
            channels SMALLINT,
            loudness_lufs REAL,
            true_peak_db REAL,
            album_loudness_lufs REAL,
            album_peak_db REAL,
            fingerprint TEXT,
            gapless_next BOOLEAN NOT NULL DEFAULT false,
            metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
            availability TEXT NOT NULL DEFAULT 'published',
            deleted_at TIMESTAMPTZ,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER tracks_set_updated_at
            BEFORE UPDATE ON tracks
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )
    op.execute("CREATE INDEX tracks_album_id_idx ON tracks (album_id)")
    op.execute("CREATE INDEX tracks_artist_id_idx ON tracks (artist_id)")
    # AcoustID fingerprint: the ingest dedupe key. Not unique — a partial match can
    # legitimately produce more than one row until a human resolves it.
    op.execute("CREATE INDEX tracks_fingerprint_idx ON tracks (fingerprint)")
    op.execute(
        """
        CREATE INDEX tracks_title_trgm_idx
            ON tracks USING GIN (title gin_trgm_ops)
        """
    )
    # Every catalog query filters `deleted_at IS NULL`; a partial index keeps the
    # tombstoned rows out of the index entirely.
    op.execute(
        """
        CREATE INDEX tracks_live_idx
            ON tracks (created_at DESC)
            WHERE deleted_at IS NULL
        """
    )

    # `renditions.id` is TEXT, not a UUID: the ids are the fixed profile names
    # `orig`, `flac16`, `opus256`, `opus128`, `opus64`, and `stream.py` matches on
    # those literals. The key is (track_id, id), not id alone.
    op.execute(
        """
        CREATE TABLE renditions (
            id TEXT NOT NULL,
            track_id UUID NOT NULL REFERENCES tracks (id) ON DELETE CASCADE,
            codec TEXT NOT NULL,
            container TEXT NOT NULL,
            bitrate_kbps INTEGER,
            sample_rate INTEGER,
            bit_depth SMALLINT,
            size_bytes BIGINT NOT NULL,
            sha256 BYTEA NOT NULL,
            path TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (track_id, id)
        )
        """
    )
    op.execute("CREATE INDEX renditions_track_id_idx ON renditions (track_id)")
    op.execute("CREATE INDEX renditions_sha256_idx ON renditions (sha256)")

    # ------------------------------------------------------------------------
    # User data
    # ------------------------------------------------------------------------
    op.execute(
        """
        CREATE TABLE playlists (
            id UUID PRIMARY KEY,
            owner_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            description TEXT,
            revision BIGINT NOT NULL DEFAULT 0,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER playlists_set_updated_at
            BEFORE UPDATE ON playlists
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )
    op.execute("CREATE INDEX playlists_owner_id_idx ON playlists (owner_id)")

    # `position_key` is a LexoRank fractional-index string, not a dense integer:
    # an insert between two rows must not renumber the playlist.
    op.execute(
        """
        CREATE TABLE playlist_entries (
            id UUID PRIMARY KEY,
            playlist_id UUID NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
            position_key TEXT NOT NULL,
            track_id UUID NOT NULL REFERENCES tracks (id) ON DELETE CASCADE,
            added_by UUID REFERENCES users (id) ON DELETE SET NULL,
            added_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        "CREATE INDEX playlist_entries_playlist_id_idx ON playlist_entries (playlist_id, position_key)"
    )
    op.execute("CREATE INDEX playlist_entries_track_id_idx ON playlist_entries (track_id)")

    op.execute(
        """
        CREATE TABLE favorites (
            user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
            track_id UUID NOT NULL REFERENCES tracks (id) ON DELETE CASCADE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (user_id, track_id)
        )
        """
    )
    op.execute("CREATE INDEX favorites_user_id_track_id_idx ON favorites (user_id, track_id)")
    op.execute("CREATE INDEX favorites_track_id_idx ON favorites (track_id)")

    op.execute(
        """
        CREATE TABLE play_events (
            id UUID PRIMARY KEY,
            user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
            track_id UUID REFERENCES tracks (id) ON DELETE SET NULL,
            device_id TEXT,
            event_type TEXT NOT NULL,
            position_ms BIGINT,
            played_ms INTEGER,
            ended_reason TEXT,
            rendition TEXT,
            offline BOOLEAN NOT NULL DEFAULT false,
            private BOOLEAN NOT NULL DEFAULT false,
            started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            idempotency_key TEXT UNIQUE
        )
        """
    )
    op.execute("CREATE INDEX play_events_user_id_started_at_idx ON play_events (user_id, started_at DESC)")
    op.execute("CREATE INDEX play_events_track_id_idx ON play_events (track_id)")
    op.execute("CREATE INDEX play_events_started_at_idx ON play_events (started_at DESC)")

    # Offline outbox drain log. `op_id` is client-generated and globally unique, so
    # replaying the same outbox cannot double-apply an operation.
    op.execute(
        """
        CREATE TABLE outbox_ops (
            id UUID PRIMARY KEY,
            user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
            op_id TEXT NOT NULL UNIQUE,
            type TEXT NOT NULL,
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute("CREATE INDEX outbox_ops_user_id_created_at_idx ON outbox_ops (user_id, created_at DESC)")

    op.execute(
        """
        CREATE TABLE jobs (
            id UUID PRIMARY KEY,
            kind TEXT NOT NULL,
            state TEXT NOT NULL DEFAULT 'queued',
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            progress INTEGER NOT NULL DEFAULT 0,
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TRIGGER jobs_set_updated_at
            BEFORE UPDATE ON jobs
            FOR EACH ROW EXECUTE FUNCTION noiz_set_updated_at()
        """
    )
    op.execute("CREATE INDEX jobs_state_idx ON jobs (state, created_at DESC)")
    op.execute("CREATE INDEX jobs_kind_idx ON jobs (kind)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS jobs")
    op.execute("DROP TABLE IF EXISTS outbox_ops")
    op.execute("DROP TABLE IF EXISTS play_events")
    op.execute("DROP TABLE IF EXISTS favorites")
    op.execute("DROP TABLE IF EXISTS playlist_entries")
    op.execute("DROP TABLE IF EXISTS playlists")
    op.execute("DROP TABLE IF EXISTS renditions")
    op.execute("DROP TABLE IF EXISTS tracks")
    op.execute("DROP TABLE IF EXISTS albums")
    op.execute("DROP TABLE IF EXISTS artists")
    op.execute("DROP TABLE IF EXISTS devices")
    op.execute("DROP TABLE IF EXISTS users")
    # Extensions are left in place: `require_extensions` fails the boot if they
    # are missing, and dropping them would also delete objects other tooling may
    # have added to the same database.
    op.execute("DROP FUNCTION IF EXISTS noiz_set_updated_at()")