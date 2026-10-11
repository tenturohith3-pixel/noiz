package com.noiz.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Queries the Phase 1 DAOs did not need: full-table reads for the search-index rebuild,
 * rendition lookup, and a simple Flow wrapper over the alphabetical paged queries so
 * non-paging screens (Library tabs, Home shelves) can observe Room directly.
 */

@Dao
interface TrackExtraDao {

    @Query("SELECT * FROM track WHERE deleted = 0 ORDER BY addedAt DESC")
    fun allFlow(): Flow<List<TrackEntity>>

    @Query("SELECT id, title, artistName FROM track WHERE deleted = 0")
    suspend fun allForIndex(): List<IndexRow>

    @Query("SELECT * FROM rendition WHERE trackId = :trackId")
    fun renditions(trackId: String): Flow<List<RenditionEntity>>

    @Query("SELECT * FROM rendition WHERE trackId = :trackId")
    suspend fun renditionsSnapshot(trackId: String): List<RenditionEntity>
}

data class IndexRow(val id: String, val title: String, val artistName: String)

@Dao
interface AlbumExtraDao {
    @Query("SELECT * FROM album WHERE deleted = 0 ORDER BY title COLLATE NOCASE")
    fun allFlow(): Flow<List<AlbumEntity>>

    @Query("SELECT id, title, artistName FROM album WHERE deleted = 0")
    suspend fun allForIndex(): List<AlbumIndexRow>
}

data class AlbumIndexRow(val id: String, val title: String, val artistName: String)

@Dao
interface ArtistExtraDao {
    @Query("SELECT * FROM artist WHERE deleted = 0 ORDER BY sortName COLLATE NOCASE")
    fun allFlow(): Flow<List<ArtistEntity>>

    @Query("SELECT id, name, '' AS sortName FROM artist WHERE deleted = 0")
    suspend fun allForIndex(): List<ArtistIndexRow>
}

data class ArtistIndexRow(val id: String, val name: String, val sortName: String)

@Dao
interface PlaylistExtraDao {
    @Query("SELECT id, name, IFNULL(description, '') AS description FROM playlist WHERE deleted = 0")
    suspend fun allForIndex(): List<PlaylistIndexRow>

    @Query("SELECT COUNT(*) FROM playlist_entry WHERE playlistId = :playlistId AND deleted = 0")
    fun trackCount(playlistId: String): Flow<Int>
}

data class PlaylistIndexRow(val id: String, val name: String, val description: String)

@Dao
interface RenditionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(renditions: List<RenditionEntity>)

    @Query("SELECT * FROM rendition WHERE trackId = :trackId")
    fun forTrack(trackId: String): Flow<List<RenditionEntity>>
}
