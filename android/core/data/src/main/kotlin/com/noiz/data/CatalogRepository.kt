package com.noiz.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noiz.common.HlcClock
import com.noiz.common.LexoRank
import com.noiz.database.AlbumEntity
import com.noiz.database.ArtistEntity
import com.noiz.database.DownloadEntity
import com.noiz.database.DownloadState
import com.noiz.database.FavoriteEntity
import com.noiz.database.NoizDatabase
import com.noiz.database.OutboxEntity
import com.noiz.database.PlaylistEntity
import com.noiz.database.PlaylistEntryEntity
import com.noiz.database.SearchIndexEntity
import com.noiz.database.TrackEntity
import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.Playlist
import com.noiz.model.QualityProfile
import com.noiz.model.SearchResult
import com.noiz.model.Track
import com.noiz.network.NoizApi
import com.noiz.network.SearchResultDto
import dagger.assisted.Assisted
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.work.workDataOf

/**
 * Catalog read/write model (§8.4, §12.3).
 *
 * Room is the source of truth for every screen: the network only ever *writes* into
 * Room, and screens only ever *read* from Room. That is what makes the app useful when
 * the server is unreachable — an offline device serves its replica and queues mutations
 * in the outbox instead of failing the UI.
 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: NoizDatabase,
    private val api: NoizApi,
    private val hlc: HlcClock,
) {

    /** Triggers the debounced outbox flush. */
    private fun scheduleFlush() = OutboxWorker.enqueue(appContext)

    // ---------------------------------------------------------------- reads --

    fun tracks(): Flow<List<TrackEntity>> = db.trackExtraDao().allFlow()

    fun recentlyAdded(limit: Int = 50): Flow<List<TrackEntity>> = db.trackDao().recentlyAdded(limit)

    fun track(id: String): Flow<TrackEntity?> = db.trackDao().observeById(id)

    fun albumTracks(albumId: String): Flow<List<TrackEntity>> = db.trackDao().byAlbum(albumId)

    fun albums(): Flow<List<AlbumEntity>> = db.albumExtraDao().allFlow()

    fun artists(): Flow<List<ArtistEntity>> = db.artistExtraDao().allFlow()

    fun playlists(): Flow<List<PlaylistEntity>> = db.playlistDao().observeAll()

    fun playlistTracks(playlistId: String): Flow<List<TrackEntity>> =
        db.playlistDao().tracksIn(playlistId)

    suspend fun trackSnapshot(id: String): TrackEntity? = db.trackDao().byId(id)

    suspend fun tracksByIds(ids: List<String>): List<TrackEntity> =
        if (ids.isEmpty()) emptyList() else db.trackDao().byIds(ids)

    // ----------------------------------------------------------- favorites --

    fun favoriteTrackIds(userId: String): Flow<Set<String>> =
        db.favoriteDao().observe(userId, "track").map { rows -> rows.map { it.targetId }.toSet() }

    fun isFavorite(userId: String, trackId: String): Flow<Boolean> =
        db.favoriteDao().observeIsFavorite(userId, "track", trackId)

    /**
     * Optimistic like: the Room row and the outbox op land in one transaction, so the
     * heart never fills without a durable mutation behind it (§3.3, §8.4).
     */
    suspend fun setFavorite(userId: String, trackId: String, liked: Boolean) {
        db.mutationDao().likeWithOutbox(
            favorite = FavoriteEntity(
                userId = userId,
                kind = "track",
                targetId = trackId,
                createdAt = System.currentTimeMillis(),
                deleted = !liked,
            ),
            op = outboxOp(
                type = if (liked) "favorite.add" else "favorite.remove",
                payload = """{"track_id":"$trackId"}""",
            ),
        )
        scheduleFlush()
    }

    // -------------------------------------------------------------- search --

    /**
     * Offline-first search: FTS4 answers instantly and the server is asked only when
     * the query is long enough to be worth a round-trip (§5.7, §10.1).
     *
     * `remove_diacritics=2` in the index means "bjork" and "Björk" match offline the
     * same way the server matches them online.
     */
    suspend fun searchOffline(query: String, limit: Int = 50): List<SearchResult> {
        val expression = buildFtsQuery(query) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            db.searchDao().search(expression, limit).map { row ->
                SearchResult(
                    kind = row.entityType,
                    id = row.entityId,
                    title = row.title,
                    subtitle = row.subtitle,
                )
            }
        }
    }

    suspend fun searchRemote(query: String, limit: Int = 50): List<SearchResult> = runCatching {
        api.search(query, limit = limit).results.map { it.toModel() }
    }.getOrDefault(emptyList())

    /**
     * Prefix query for as-you-type. Every token gets a `*` suffix — including the last
     * one, otherwise typing "bea" for "Beatles" matches nothing until the whole word is
     * finished, which defeats the point of an index.
     *
     * Both `title` and `subtitle` are matched so an artist name finds their tracks.
     * Both columns are quoted and escaped, so FTS operators in user input are inert.
     */
    fun buildFtsQuery(raw: String): String? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "(title : \"$escaped\"* OR subtitle : \"$escaped\"*)"
        }
    }

    /**
     * Rebuilds the FTS index from the four catalog tables after a sync batch. Room has
     * no cross-entity trigger, so this is a transaction by design (§8.3).
     */
    suspend fun rebuildSearchIndex() = withContext(Dispatchers.IO) {
        val rows = buildList {
            db.trackExtraDao().allForIndex().forEach { track ->
                add(
                    SearchIndexEntity(
                        entityType = "track",
                        entityId = track.id,
                        title = track.title,
                        subtitle = track.artistName,
                    ),
                )
            }
            db.albumExtraDao().allForIndex().forEach { album ->
                add(
                    SearchIndexEntity(
                        entityType = "album",
                        entityId = album.id,
                        title = album.title,
                        subtitle = album.artistName,
                    ),
                )
            }
            db.artistExtraDao().allForIndex().forEach { artist ->
                add(
                    SearchIndexEntity(
                        entityType = "artist",
                        entityId = artist.id,
                        title = artist.name,
                        subtitle = "",
                    ),
                )
            }
            db.playlistExtraDao().allForIndex().forEach { playlist ->
                add(
                    SearchIndexEntity(
                        entityType = "playlist",
                        entityId = playlist.id,
                        title = playlist.name,
                        subtitle = playlist.description,
                    ),
                )
            }
        }
        db.searchDao().rebuild(rows)
    }

    // ----------------------------------------------------------- playlists --

    /**
     * Appends with a fractional key so two devices inserting at the same time produce
     * two keys that both sort correctly — no renumbering, no conflict (§8.4).
     */
    suspend fun addToPlaylist(playlistId: String, trackId: String, userId: String) {
        val last = db.playlistDao().lastPositionKey(playlistId)
        val entry = PlaylistEntryEntity(
            playlistId = playlistId,
            entryId = "$playlistId:$trackId",
            trackId = trackId,
            // Append at the tail: sort after every existing key.
            positionKey = tailKeyAfter(last),
            addedBy = userId,
            addedAt = System.currentTimeMillis(),
        )
        db.mutationDao().insertEntryWithOutbox(
            entry = entry,
            op = outboxOp(
                type = "playlist.add",
                payload = outboxJson("playlist_id" to playlistId, "track_id" to trackId, "position_key" to entry.positionKey),
            ),
        )
        scheduleFlush()
    }

    private fun tailKeyAfter(lastKey: String?): String =
        if (lastKey == null) LexoRank.first() else LexoRank.between(lastKey, null)

    suspend fun removeFromPlaylist(playlistId: String, entryId: String) {
        // Tombstone + outbox op must land in ONE transaction: a crash between them would
        // either lose the removal or enqueue an edit the local DB never applied (§8.4).
        db.mutationDao().removeEntryWithOutbox(
            playlistId = playlistId,
            entryId = entryId,
            op = outboxOp(
                type = "playlist.remove",
                payload = outboxJson("playlist_id" to playlistId, "entry_id" to entryId),
            ),
        )
        scheduleFlush()
    }

    suspend fun moveInPlaylist(playlistId: String, entryId: String, positionKey: String) {
        db.mutationDao().moveEntryWithOutbox(
            playlistId = playlistId,
            entryId = entryId,
            positionKey = positionKey,
            op = outboxOp(
                type = "playlist.move",
                payload = outboxJson(
                    "playlist_id" to playlistId,
                    "entry_id" to entryId,
                    "position_key" to positionKey,
                ),
            ),
        )
        scheduleFlush()
    }

    suspend fun createPlaylist(name: String, description: String?, ownerId: String): String {
        val remoteId = runCatching {
            api.createPlaylist(com.noiz.network.PlaylistCreateRequestDto(name, description)).id
        }.getOrDefault("local:${System.currentTimeMillis()}")

        db.playlistDao().upsert(
            listOf(
                PlaylistEntity(
                    id = remoteId,
                    ownerId = ownerId,
                    name = name,
                    description = description,
                    isCollaborative = false,
                    artworkHash = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        return remoteId
    }

    // --------------------------------------------------------------- sync --

    /**
     * Pulls one page of catalog changes into Room. Cursor-agnostic by design: the
     * worker keeps the cursor in `DataStore` so a killed process resumes rather than
     * re-downloading from zero (§10.1 `/catalog/sync`).
     */
    suspend fun pullPage(cursor: String?): SyncPage = withContext(Dispatchers.IO) {        val page = api.catalogSync(cursor)

        db.trackDao().upsertAll(page.tracks.map { it.toEntity() })
        db.albumDao().upsertAll(page.albums.map { it.toEntity() })
        db.artistDao().upsertAll(page.artists.map { it.toEntity() })
        db.playlistDao().upsert(page.playlists.map { it.toEntity() })
        db.renditionDao().upsertAll(page.tracks.map { track ->
            track.renditions.map { rendition ->
                com.noiz.database.RenditionEntity(
                    trackId = track.id,
                    id = rendition.id,
                    codec = rendition.codec,
                    bitrateKbps = rendition.bitrateKbps,
                    sampleRate = rendition.sampleRate,
                    bitDepth = rendition.bitDepth,
                    sizeBytes = rendition.sizeBytes,
                    sha256 = rendition.sha256,
                )
            }
        }.flatten())

        SyncPage(nextCursor = page.nextCursor, hasMore = page.nextCursor != null)
    }

    suspend fun hydrateRenditions(trackId: String) = withContext(Dispatchers.IO) {
        runCatching { api.track(trackId) }.onSuccess { dto ->
            val renditions = dto.renditions.map {
                com.noiz.database.RenditionEntity(
                    trackId = trackId,
                    id = it.id,
                    codec = it.codec,
                    bitrateKbps = it.bitrateKbps,
                    sampleRate = it.sampleRate,
                    bitDepth = it.bitDepth,
                    sizeBytes = it.sizeBytes,
                    sha256 = it.sha256,
                )
            }
            db.renditionDao().upsertAll(renditions)
        }
    }

    /** Builds an outbox op stamped with the current HLC so ordering survives restarts. */
    fun outboxOp(type: String, payload: String): OutboxEntity = OutboxEntity(
        opId = "op-${System.currentTimeMillis()}-${System.nanoTime()}",
        type = type,
        payloadJson = payload,
        hlc = hlc.next(),
        createdAt = System.currentTimeMillis(),
    )

    /**
     * Builds an outbox payload as valid JSON. Hand-built string interpolation breaks on
     * any id containing `"` or `\`, and the failure surfaces as a SerializationException
     * inside the worker — a crash instead of a retry.
     */
    private fun outboxJson(vararg pairs: Pair<String, String>): String = buildString {
        append('{')
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append('"').append(escapeJson(key)).append("\":\"").append(escapeJson(value)).append('"')
        }
        append('}')
    }

    private fun escapeJson(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    companion object {
        const val SYNC_WORK = "noiz-catalog-sync"

        fun enqueueSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

data class SyncPage(val nextCursor: String?, val hasMore: Boolean)

/** Background pull; kept separate so the repository stays free of WorkManager types. */
@androidx.hilt.work.HiltWorker
class CatalogSyncWorker @javax.inject.Inject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: CatalogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        // Resume from the persisted cursor instead of page 0: without this every run
        // re-downloads the whole catalog and any catalog larger than MAX_PAGES pages
        // can never converge (§10.1, §12.7).
        var cursor = inputData.getString(KEY_CURSOR)
        repeat(MAX_PAGES) {
            val page = repository.pullPage(cursor)
            cursor = page.nextCursor
            if (page.nextCursor != null) {
                setProgress(workDataOf(KEY_CURSOR to page.nextCursor))
            }
            if (!page.hasMore) {
                // The FTS index is only populated here, so offline search stayed empty
                // until a sync happened to run (§6.3).
                repository.rebuildSearchIndex()
                return Result.success()
            }
        }
        // Ran out of budget with pages still pending — retry so the next run resumes
        // from the cursor rather than silently leaving the replica half-populated.
        repository.rebuildSearchIndex()
        Result.retry()
    } catch (io: java.io.IOException) {
        Result.retry()
    } catch (auth: retrofit2.HttpException) {
        if (auth.code() == 401) Result.failure() else Result.retry()
    } catch (t: Throwable) {
        // A malformed payload must not crash the worker; retry with backoff.
        Result.retry()
    }

    private companion object {
        const val MAX_PAGES = 20
        const val KEY_CURSOR = "noiz.sync.cursor"
    }
}
