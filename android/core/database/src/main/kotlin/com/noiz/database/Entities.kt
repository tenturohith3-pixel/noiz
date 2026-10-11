package com.noiz.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * noiz Room schema (§8.3).
 *
 * Room is the single source of truth on device: repositories expose Flows from these
 * DAOs and the network only ever writes into Room. Every table carries the fields the
 * *client* needs; the server remains authoritative for the full catalog.
 */

@Entity(
    tableName = "track",
    indices = [Index("albumId"), Index("addedAt"), Index("title")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val albumId: String?,
    val artistName: String,
    val discNo: Int,
    val trackNo: Int,
    val durationMs: Long,
    val explicit: Boolean,
    val isrc: String?,
    val loudnessLufs: Float?,
    val truePeakDb: Float?,
    val gaplessNext: Boolean,
    val hasLyrics: Boolean,
    val hasCanvas: Boolean,
    val artworkHash: String?,
    val addedAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "album",
    indices = [Index("title"), Index("releaseYear")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val albumType: String?,
    val releaseYear: Int?,
    val trackCount: Int,
    val artworkHash: String?,
    val dominantHex: String?,
    val vibrantHex: String?,
    val mutedHex: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "artist", indices = [Index("sortName")])
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortName: String,
    val imageHash: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "playlist", indices = [Index("updatedAt")])
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val name: String,
    val description: String?,
    val isCollaborative: Boolean,
    val artworkHash: String?,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "rendition",
    primaryKeys = ["trackId", "id"],
)
data class RenditionEntity(
    val trackId: String,
    val id: String,
    val codec: String,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val sizeBytes: Long,
    val sha256: String,
)

@Entity(
    tableName = "playlist_entry",
    primaryKeys = ["playlistId", "entryId"],
    indices = [Index("playlistId", "positionKey")],
)
data class PlaylistEntryEntity(
    val playlistId: String,
    val entryId: String,
    val trackId: String,
    /** Fractional index (LexoRank-style base-62). Sorted with BINARY collation. */
    val positionKey: String,
    val addedBy: String?,
    val addedAt: Long,
    val deleted: Boolean = false,
)

/** Offline download state machine (§6.7). */
enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED, STALE }

@Entity(tableName = "download", indices = [Index("state"), Index("pinnedBy")])
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val renditionId: String,
    val state: DownloadState,
    val bytesDone: Long,
    val bytesTotal: Long,
    val pinnedBy: String?,
    val completedAt: Long?,
    val sha256: String,
    val failureReason: String? = null,
)

/**
 * Local mutations awaiting server acknowledgement (§8.4). Written in the *same*
 * transaction as the optimistic Room change, so the UI never shows state that is not
 * durable.
 *
 * `opId` is the idempotency key the server dedupes on.
 */
@Entity(tableName = "outbox", indices = [Index("hlc"), Index("createdAt"), Index("attempts")])
data class OutboxEntity(
    @PrimaryKey val opId: String,
    val type: String,
    val payloadJson: String,
    /** Hybrid Logical Clock: last-writer-wins for scalar fields. */
    val hlc: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "play_event", indices = [Index("synced"), Index("startedAt")])
data class PlayEventEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val startedAt: Long,
    val playedMs: Int,
    val endedReason: String,
    val sourceType: String?,
    val sourceId: String?,
    val rendition: String?,
    val offline: Boolean,
    val private: Boolean,
    val synced: Boolean = false,
)

@Entity(tableName = "playback_state", primaryKeys = ["slot"])
data class PlaybackStateEntity(
    val slot: Int = 0,
    val queueJson: String,
    val index: Int,
    val positionMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "favorite", primaryKeys = ["userId", "kind", "targetId"])
data class FavoriteEntity(
    val userId: String,
    val kind: String,
    val targetId: String,
    val createdAt: Long,
    val deleted: Boolean = false,
)

/** Palette results cached by artwork hash so a track change never re-extracts (§7.3). */
@Entity(tableName = "palette_cache")
data class PaletteCacheEntity(
    @PrimaryKey val artworkHash: String,
    val dominant: String,
    val vibrant: String,
    val muted: String,
    val extractedAt: Long,
)

/**
 * Offline search index (§8.3, §5.7).
 *
 * Standalone FTS4 (not external-content) because it indexes tracks, albums, artists and
 * playlists in one table. `remove_diacritics=2` matches the server's unicode-aware
 * behaviour so offline and online results agree; prefix queries are `title:beat*`.
 */
@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=2"],
    notIndexed = ["entityType", "entityId"],
)
@Entity(tableName = "search_index")
data class SearchIndexEntity(
    val entityType: String,
    val entityId: String,
    val title: String,
    val subtitle: String,
)
