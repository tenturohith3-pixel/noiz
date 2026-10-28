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
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
                // The server reads camelCase payload keys (§10); the snake_case form
                // made every favorite op come back "invalid" and be dropped.
                payload = """{"trackId":"$trackId"}""",
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
                payload = outboxJson("playlistId" to playlistId, "trackId" to trackId, "positionKey" to entry.positionKey),
            ),
        )
        scheduleFlush()
    }

    private fun tailKeyAfter(lastKey: String?): String =
        if (lastKey == null) LexoRank.first() else LexoRank.between(lastKey, null)

    suspend fun removeFromPlaylist(playlistId: String, entryId: String, trackId: String) {
        // Tombstone + outbox op must land in ONE transaction: a crash between them would
        // either lose the removal or enqueue an edit the local DB never applied (§8.4).
        db.mutationDao().removeEntryWithOutbox(
            playlistId = playlistId,
            entryId = entryId,
            op = outboxOp(
                type = "playlist.remove",
                payload = outboxJson("playlistId" to playlistId, "trackId" to trackId),
            ),
        )
        scheduleFlush()
    }

    suspend fun moveInPlaylist(
        playlistId: String,
        entryId: String,
        trackId: String,
        positionKey: String,
    ) {
        db.mutationDao().moveEntryWithOutbox(
            playlistId = playlistId,
            entryId = entryId,
            positionKey = positionKey,
            op = outboxOp(
                type = "playlist.move",
                payload = outboxJson(
                    "playlistId" to playlistId,
                    "trackId" to trackId,
                    "positionKey" to positionKey,
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
     * Pulls one page of catalog changes into Room. Cursor-agnostic by design: [syncAll]
     * keeps the cursor in SharedPreferences so a killed process resumes rather than
     * re-downloading from zero (§10.1 `/catalog/sync`).
     */
    suspend fun pullPage(cursor: String?): SyncPage = withContext(Dispatchers.IO) {
        val page = api.catalogSync(cursor)

        db.trackDao().upsertAll(page.tracks.map { it.toEntity() })
        db.albumDao().upsertAll(page.albums.map { it.toEntity() })
        db.artistDao().upsertAll(page.artists.map { it.toEntity() })
        db.playlistDao().upsert(page.playlists.map { it.toEntity() })
        upsertRenditions(page.tracks)

        SyncPage(nextCursor = page.nextCursor, hasMore = page.nextCursor != null)
    }

    /**
     * Drains `/catalog/sync` until the server says there is no next page.
     *
     * The cursor survives process death and WorkManager retries, so a library larger
     * than one page (or one worker run) eventually converges instead of starting over.
     * A 404 means the server predates `/catalog/sync`; the older flat
     * `/catalog/tracks` endpoint is still enough to populate Room for playback.
     *
     * @return true when the replica is fully synced, false when the page budget ran out.
     */
    suspend fun syncAll(): Boolean = withContext(Dispatchers.IO) {
        var cursor = syncPrefs().getString(KEY_SYNC_CURSOR, null)
        var pages = 0

        while (pages < MAX_SYNC_PAGES) {
            val page = try {
                pullPage(cursor)
            } catch (missing: retrofit2.HttpException) {
                if (missing.code() == 404) {
                    // Forget a cursor minted by a different/newer server before falling
                    // back, otherwise every future run starts from a page it cannot use.
                    syncPrefs().edit().remove(KEY_SYNC_CURSOR).apply()
                    return@withContext pullLegacyCatalog()
                }
                throw missing
            }

            pages++
            cursor = page.nextCursor
            syncPrefs().edit().apply {
                if (cursor == null) remove(KEY_SYNC_CURSOR) else putString(KEY_SYNC_CURSOR, cursor)
            }.apply()

            if (!page.hasMore) {
                // The FTS index is only populated here, so offline search stayed empty
                // until a sync happened to run (§6.3).
                rebuildSearchIndex()
                return@withContext true
            }
        }

        // Ran out of budget with pages still pending — the next sync resumes from the
        // stored cursor rather than silently leaving the replica half-populated.
        rebuildSearchIndex()
        false
    }

    /** Compatibility path for servers without `/catalog/sync`; tracks-only by design. */
    private suspend fun pullLegacyCatalog(): Boolean {
        var offset = 0
        repeat(MAX_SYNC_PAGES) {
            val page = api.catalogTracks(limit = LEGACY_PAGE_SIZE, offset = offset)
            if (page.tracks.isEmpty()) {
                rebuildSearchIndex()
                return true
            }
            db.trackDao().upsertAll(page.tracks.map { it.toEntity() })
            upsertRenditions(page.tracks)
            offset += page.tracks.size
            if (page.tracks.size < LEGACY_PAGE_SIZE || offset >= page.total) {
                rebuildSearchIndex()
                return true
            }
        }
        rebuildSearchIndex()
        return false
    }

    private suspend fun upsertRenditions(tracks: List<com.noiz.network.TrackDto>) {
        val renditions = tracks.flatMap { track ->
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
        }
        if (renditions.isNotEmpty()) db.renditionDao().upsertAll(renditions)
    }

    private fun syncPrefs() = appContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)

    suspend fun hydrateRenditions(trackId: String) = withContext(Dispatchers.IO) {
        runCatching { api.track(trackId) }.onSuccess { dto -> upsertRenditions(listOf(dto)) }
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

        private const val SYNC_PREFS = "noiz_catalog_sync"
        private const val KEY_SYNC_CURSOR = "cursor"
        private const val MAX_SYNC_PAGES = 50
        private const val LEGACY_PAGE_SIZE = 200

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
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: CatalogRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        // The repository owns the persisted cursor; the worker only decides whether a
        // partial sync should be retried. `syncAll` also rebuilds the FTS index and
        // falls back to `/catalog/tracks` on servers without `/catalog/sync`.
        if (repository.syncAll()) Result.success() else Result.retry()
    } catch (io: java.io.IOException) {
        Result.retry()
    } catch (auth: retrofit2.HttpException) {
        if (auth.code() == 401) Result.failure() else Result.retry()
    } catch (t: Throwable) {
        // A malformed payload must not crash the worker; retry with backoff.
        Result.retry()
    }
}
