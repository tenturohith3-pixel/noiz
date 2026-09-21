package com.noiz.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM track WHERE id = :id")
    fun observeById(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM track WHERE albumId = :albumId AND deleted = 0 ORDER BY discNo, trackNo")
    fun byAlbum(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE deleted = 0 ORDER BY addedAt DESC LIMIT :limit")
    fun recentlyAdded(limit: Int = 50): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE deleted = 0 ORDER BY title COLLATE NOCASE")
    fun pagingAlphabetical(): PagingSource<Int, TrackEntity>

    @Query("SELECT * FROM track WHERE id IN (:ids) AND deleted = 0")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    @Query("DELETE FROM track WHERE deleted = 1 AND updatedAt < :olderThan")
    suspend fun purgeTombstones(olderThan: Long)
}

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(albums: List<AlbumEntity>)

    @Query("SELECT * FROM album WHERE id = :id")
    fun observeById(id: String): Flow<AlbumEntity?>

    @Query("SELECT * FROM album WHERE deleted = 0 ORDER BY title COLLATE NOCASE")
    fun pagingAlphabetical(): PagingSource<Int, AlbumEntity>

    @Query("SELECT * FROM album WHERE deleted = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun recentlyUpdated(limit: Int = 40): Flow<List<AlbumEntity>>
}

@Dao
interface ArtistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artist WHERE deleted = 0 ORDER BY sortName COLLATE NOCASE")
    fun pagingAlphabetical(): PagingSource<Int, ArtistEntity>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun observeById(id: String): Flow<ArtistEntity?>
}

@Dao
interface PlaylistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlists: List<PlaylistEntity>)

    @Query("SELECT * FROM playlist WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    /**
     * Entries are ordered by the fractional [PlaylistEntryEntity.positionKey] using
     * BINARY collation — never by rowid, so concurrent inserts from two devices never
     * fight over a numeric index (§8.4).
     */
    @Query(
        """
        SELECT t.* FROM playlist_entry e
        JOIN track t ON t.id = e.trackId
        WHERE e.playlistId = :playlistId AND e.deleted = 0 AND t.deleted = 0
        ORDER BY e.positionKey COLLATE BINARY
        """,
    )
    fun tracksIn(playlistId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId AND deleted = 0 ORDER BY positionKey COLLATE BINARY")
    fun entriesIn(playlistId: String): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT positionKey FROM playlist_entry WHERE playlistId = :playlistId AND deleted = 0 ORDER BY positionKey COLLATE BINARY LIMIT 1")
    suspend fun firstPositionKey(playlistId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: PlaylistEntryEntity)

    @Query("UPDATE playlist_entry SET deleted = 1 WHERE playlistId = :playlistId AND entryId = :entryId")
    suspend fun tombstoneEntry(playlistId: String, entryId: String)

    @Query("UPDATE playlist_entry SET positionKey = :positionKey WHERE playlistId = :playlistId AND entryId = :entryId")
    suspend fun moveEntry(playlistId: String, entryId: String, positionKey: String)
}

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM download WHERE state = :state")
    fun observeByState(state: DownloadState): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE trackId = :trackId")
    suspend fun byTrack(trackId: String): DownloadEntity?

    @Query("SELECT * FROM download WHERE trackId = :trackId")
    fun observeTrack(trackId: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM download WHERE state = 'COMPLETED'")
    suspend fun completed(): List<DownloadEntity>

    @Query("UPDATE download SET state = :state, bytesDone = :bytesDone WHERE trackId = :trackId")
    suspend fun updateProgress(trackId: String, state: DownloadState, bytesDone: Long)

    /** LRU eviction of unpinned downloads when the storage cap is reached (§11 row 11). */
    @Query(
        """
        SELECT * FROM download
        WHERE state = 'COMPLETED' AND pinnedBy IS NULL
        ORDER BY completedAt ASC
        LIMIT :limit
        """,
    )
    suspend fun evictionCandidates(limit: Int): List<DownloadEntity>

    @Query("SELECT SUM(bytesTotal) FROM download WHERE state = 'COMPLETED'")
    suspend fun completedBytes(): Long?

    @Query("DELETE FROM download WHERE trackId = :trackId")
    suspend fun delete(trackId: String)
}

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT :limit")
    suspend fun nextBatch(limit: Int): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    suspend fun deleteByIds(opIds: List<String>)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE opId = :opId")
    suspend fun recordFailure(opId: String, error: String?)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE attempts >= :maxAttempts")
    suspend fun dropExhausted(maxAttempts: Int)
}

@Dao
interface PlayEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PlayEventEntity)

    @Query("SELECT * FROM play_event WHERE synced = 0 ORDER BY startedAt ASC LIMIT :limit")
    suspend fun unsynced(limit: Int = 200): List<PlayEventEntity>

    @Query("UPDATE play_event SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM play_event WHERE private = 0 ORDER BY startedAt DESC LIMIT :limit")
    fun history(limit: Int = 500): Flow<List<PlayEventEntity>>

    /** Private session history is never uploaded; drop un-synced rows on toggle-off. */
    @Query("DELETE FROM play_event WHERE private = 1 AND synced = 0")
    suspend fun discardPrivateUnsynced()
}

@Dao
interface PlaybackStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE slot = 0")
    suspend fun loadBlocking(): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE slot = 0")
    fun observe(): Flow<PlaybackStateEntity?>
}

@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorite WHERE userId = :userId AND kind = :kind AND deleted = 0")
    fun observe(userId: String, kind: String): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE userId = :userId AND kind = :kind AND targetId = :targetId AND deleted = 0)")
    fun observeIsFavorite(userId: String, kind: String, targetId: String): Flow<Boolean>
}

@Dao
interface PaletteCacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(palette: PaletteCacheEntity)

    @Query("SELECT * FROM palette_cache WHERE artworkHash = :hash")
    suspend fun get(hash: String): PaletteCacheEntity?
}

@Dao
interface SearchDao {

    /**
     * Offline search over the FTS4 index. `query` is a raw FTS expression built by the
     * repository, e.g. `title:beat*` for as-you-type prefix matching.
     */
    @Query(
        """
        SELECT * FROM search_index
        WHERE search_index MATCH :query
        ORDER BY rowid
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 50): List<SearchIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRows(rows: List<SearchIndexEntity>)

    @Query("DELETE FROM search_index WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteRow(entityType: String, entityId: String)

    @Query("DELETE FROM search_index")
    suspend fun clear()

    /**
     * Rebuilds the whole index from the catalog tables. Called after a sync batch and
     * on first launch; Room has no cross-entity trigger that could index four tables.
     */
    @Transaction
    suspend fun rebuild(rows: List<SearchIndexEntity>) {
        clear()
        upsertRows(rows)
    }
}

/**
 * Composite writes that must be atomic: an optimistic Room change plus its outbox op
 * land together, or neither does (§3.3, §8.4).
 */
@Dao
abstract class MutationDao {

    @Transaction
    open suspend fun likeWithOutbox(favorite: FavoriteEntity, op: OutboxEntity) {
        upsertFavoriteInternal(favorite)
        insertOutboxInternal(op)
    }

    @Transaction
    open suspend fun insertEntryWithOutbox(entry: PlaylistEntryEntity, op: OutboxEntity) {
        upsertEntryInternal(entry)
        insertOutboxInternal(op)
    }

    @Transaction
    open suspend fun moveEntryWithOutbox(
        playlistId: String,
        entryId: String,
        positionKey: String,
        op: OutboxEntity,
    ) {
        moveEntryInternal(playlistId, entryId, positionKey)
        insertOutboxInternal(op)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFavoriteInternal(favorite: FavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertEntryInternal(entry: PlaylistEntryEntity)

    @Update
    abstract suspend fun updateEntryInternal(entry: PlaylistEntryEntity)

    @Query("UPDATE playlist_entry SET positionKey = :positionKey WHERE playlistId = :playlistId AND entryId = :entryId")
    abstract suspend fun moveEntryInternal(playlistId: String, entryId: String, positionKey: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertOutboxInternal(op: OutboxEntity)
}
