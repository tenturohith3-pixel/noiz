package com.noiz.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.SingletonImageLoader
// The Kotlin API is the top-level function `OkHttpNetworkFetcherFactory`; the class
// `coil3.network.okhttp.OkHttpNetworkFetcher` only exists as a JVM file facade
// (`@file:JvmName`), so importing it fails to resolve in Kotlin.
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.noiz.data.CatalogRepository
import com.noiz.data.PlayHistoryLogger
import com.noiz.database.FavoriteEntity
import com.noiz.database.NoizDatabase
import com.noiz.database.PlaybackStateDao
import com.noiz.database.PlaybackStateEntity
import com.noiz.database.TrackEntity
import com.noiz.datastore.SessionStore
import com.noiz.datastore.SettingsStore
import com.noiz.download.NoizDownloadManagerProvider
import com.noiz.download.buildNoizDownloadManager
import com.noiz.network.NoizClientFactory
import com.noiz.network.ServerUrlProvider
import com.noiz.network.StreamManifestSource
import com.noiz.network.TokenSnapshot
import com.noiz.network.TokenStore
import com.noiz.playback.BrowseTreeProvider
import com.noiz.playback.DualPlayerEngine
import com.noiz.playback.ExoPlayerAssembler
import com.noiz.playback.FavoritesWriter
import com.noiz.playback.NoizLoadErrorHandlingPolicy
import com.noiz.playback.NoizMediaExtras
import com.noiz.playback.PlaybackComponents
import com.noiz.playback.PlaybackHistory
import com.noiz.playback.PlaybackSettings
import com.noiz.playback.PlaybackSettingsSnapshot
import com.noiz.playback.QueueRestoreStore
import com.noiz.playback.RestoredQueue
import com.noiz.playback.SignedStreamUrl
import com.noiz.playback.SignedUrlResolver
import com.noiz.playback.StreamManifestClient
import com.noiz.playback.StreamTokenStore
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide wiring (Hilt-free, Ph1).
 *
 * Installs the factories `PlaybackService` and `NoizDownloadService` pull from when the
 * framework instantiates them. Everything here is a lazy process singleton so a cold
 * start touches only what the first screen needs.
 */
object AppGraph {

    private const val ROOT_ID = "root"
    private const val LOCAL_USER = "local"
    private const val KIND_TRACK = "track"

    /** 2 GB streaming cache cap (§11 row 11). */
    private const val STREAM_CACHE_BYTES = 2L * 1024L * 1024L * 1024L

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredQueue(val mediaIds: List<String>, val renditionIds: List<String>)

    fun install(
        app: Application,
        catalog: CatalogRepository,
        historyLogger: PlayHistoryLogger,
    ) {
        val db = NoizDatabase.get(app)
        val sessionStore = SessionStore(app)
        val settingsStore = SettingsStore(app)

        // Room writes triggered from the main-thread service observers hop to IO here.
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val serverUrl = ServerUrlProvider { runBlocking { sessionStore.serverUrl() } }
        val tokenStore = DatastoreTokenStore(sessionStore)
        val clientFactory = NoizClientFactory(tokenStore, serverUrl)
        val manifestSource: StreamManifestSource = clientFactory.streamManifests

        // Album art lives behind the same bearer auth as the API. Coil's default
        // loader has its own OkHttp client with no interceptor, so every artwork
        // request was a 401 and every cover fell back to a placeholder.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(
                        // Lambda form: `() -> Call.Factory`, re-read per request so a
                        // token rotation inside apiClient is picked up immediately.
                        OkHttpNetworkFetcherFactory(callFactory = { clientFactory.apiClient }),
                    )
                }
                .build()
        }

        // Streaming cache: LRU-bounded, safe to evict at any time (§11 row 11).
        // A previous process killed mid-write can leave the Media3 cache database
        // corrupt; that used to crash every subsequent launch until the folder was
        // cleared by hand. Detect the failure, wipe the folder, retry once.
        val streamCache = buildCache(
            folder = File(app.cacheDir, "noiz_stream"),
            evictor = LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            app = app,
        )

        // Downloads get their OWN cache with a no-op evictor. Sharing the streaming
        // LRU cache meant that once 2 GB of playback had cycled through, eviction
        // would happily delete a user's completed offline downloads — Media3's own
        // DownloadManager docs require "a CacheEvictor that will not evict downloaded
        // content". It also lets us bound the cache independently.
        val downloadCache = buildCache(
            folder = File(app.filesDir, "noiz_downloads_cache"),
            evictor = NoOpCacheEvictor(),
            app = app,
        )

        val streamTokens = InMemoryTokenStore()

        val resolver = SignedUrlResolver(
            streamTokens,
            object : StreamManifestClient {
                override fun streamUrlBlocking(trackId: String, rendition: String): SignedStreamUrl? =
                    manifestSource.manifestBlocking(trackId, rendition)
                        ?.let { SignedStreamUrl(it.url, it.expiresAtEpochMs) }
            },
        )

        val assembler = ExoPlayerAssembler(
            context = app,
            okHttpClient = clientFactory.streamClient,
            streamCache = streamCache,
            resolver = resolver,
            errorPolicy = NoizLoadErrorHandlingPolicy(
                isValidatedNetwork = { isValidatedNetwork(app) },
                // A 401/403 drops the cached signature so the next resolve re-mints it
                // instead of retrying the same dead URL twenty times.
                tokenStore = streamTokens,
            ),
        )

        // --- PlaybackService factories -------------------------------------

        PlaybackComponents.engineFactory = { _, scope ->
            DualPlayerEngine(scope, assembler)
        }

        PlaybackComponents.restoreStoreFactory = { RoomQueueRestoreStore(db.playbackStateDao(), ioScope) }

        PlaybackComponents.browseTreeFactory = { RoomBrowseTreeProvider(db) }

        PlaybackComponents.favoritesFactory = {
            RoomFavoritesWriter(db, ioScope, sessionStore, catalog)
        }

        PlaybackComponents.historyFactory = { RoomPlaybackHistory(historyLogger) }

        PlaybackComponents.settingsFactory = { DatastorePlaybackSettings(settingsStore) }

        // --- DownloadService factory ----------------------------------------

        NoizDownloadManagerProvider.install { ctx ->
            buildNoizDownloadManager(
                context = ctx,
                okHttpClient = clientFactory.streamClient,
                // The dedicated, non-evicting download cache — not the streaming one.
                streamCache = downloadCache,
                resolver = { trackId, rendition ->
                    manifestSource.manifestBlocking(trackId, rendition)?.url.orEmpty()
                },
            )
        }
    }

    private fun isValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // The constants are NET_CAPABILITY_* (not NETWORK_CAPABILITY_*).
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Builds a [SimpleCache] over [folder], wiping it once and retrying if the
     * Media3 metadata database inside is corrupt (the classic post-crash loop).
     */
    private fun buildCache(folder: File, evictor: androidx.media3.datasource.cache.CacheEvictor, app: Application): SimpleCache =
        runCatching { SimpleCache(folder, evictor, StandaloneDatabaseProvider(app)) }
            .getOrElse { failure ->
                android.util.Log.e("AppGraph", "cache at ${folder.path} corrupt; wiping", failure)
                folder.deleteRecursively()
                SimpleCache(folder, evictor, StandaloneDatabaseProvider(app))
            }

    // -----------------------------------------------------------------------
    // Small adapters
    // -----------------------------------------------------------------------

    private class DatastoreTokenStore(private val sessionStore: SessionStore) : TokenStore {
        override fun snapshot(): TokenSnapshot? = runBlocking {
            sessionStore.session.first()?.let {
                TokenSnapshot(it.accessToken, it.refreshToken, it.expiresAtEpochMs)
            }
        }

        override fun update(access: String, refresh: String, expiresAtEpochMs: Long) {
            runBlocking { sessionStore.updateTokens(access, refresh, expiresAtEpochMs) }
        }
    }

    private class InMemoryTokenStore : StreamTokenStore {
        private val cache = ConcurrentHashMap<String, SignedStreamUrl>()

        override fun signatureFor(trackId: String, rendition: String): SignedStreamUrl? =
            cache["$trackId/$rendition"]

        override fun put(trackId: String, rendition: String, url: SignedStreamUrl) {
            cache["$trackId/$rendition"] = url
        }

        override fun invalidate(trackId: String, rendition: String) {
            cache.remove("$trackId/$rendition")
        }

        override fun invalidateAll() {
            cache.clear()
        }
    }

    private class DatastorePlaybackSettings(private val store: SettingsStore) : PlaybackSettings {
        private val initial by lazy { runBlocking { store.current() } }

        override val crossfadeMs: Long get() = initial.crossfadeMs
        override val normalizationTargetLufs: Float?
            get() = if (initial.pureMode || !initial.normalizationEnabled) null else initial.targetLufs
        override val pureMode: Boolean get() = initial.pureMode
        override val outputVolume: Float get() = initial.outputVolume
        override val privateHistory: Boolean get() = initial.privateHistory

        override val changes: kotlinx.coroutines.flow.Flow<PlaybackSettings> =
            store.settings.map { settings ->
                PlaybackSettingsSnapshot(
                    crossfadeMs = settings.crossfadeMs,
                    normalizationTargetLufs = if (settings.pureMode || !settings.normalizationEnabled) {
                        null
                    } else {
                        settings.targetLufs
                    },
                    pureMode = settings.pureMode,
                    outputVolume = settings.outputVolume,
                    privateHistory = settings.privateHistory,
                )
            }
    }

    private class RoomPlaybackHistory(
        private val logger: PlayHistoryLogger,
    ) : PlaybackHistory {

        override fun start(trackId: String, rendition: String?, isPrivate: Boolean): String =
            logger.recordStart(
                trackId = trackId,
                rendition = rendition,
                offline = false,
                isPrivate = isPrivate,
            )

        override fun progress(eventId: String, playedMs: Int) =
            logger.recordProgress(eventId, playedMs)

        override fun end(eventId: String, reason: String, playedMs: Int) =
            logger.recordEnd(eventId, reason, playedMs)
    }

    /** Persists the canonical queue into Room's `playback_state` slot 0. */
    private class RoomQueueRestoreStore(
        private val dao: PlaybackStateDao,
        private val ioScope: CoroutineScope,
    ) : QueueRestoreStore {

        override fun save(
            queueMediaIds: List<String>,
            index: Int,
            positionMs: Long,
            renditionIds: List<String>,
        ) {
            val payload = json.encodeToString(
                StoredQueue.serializer(),
                StoredQueue(queueMediaIds, renditionIds),
            )
            ioScope.launch {
                dao.save(
                    PlaybackStateEntity(
                        slot = 0,
                        queueJson = payload,
                        index = index,
                        positionMs = positionMs,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }

        /** Called by Media3 off the main thread, so a blocking read is fine here. */
        override fun loadBlocking(): RestoredQueue? = runBlocking {
            val row = dao.loadBlocking() ?: return@runBlocking null
            val stored = runCatching {
                json.decodeFromString(StoredQueue.serializer(), row.queueJson)
            }.getOrNull() ?: return@runBlocking null
            RestoredQueue(stored.mediaIds, stored.renditionIds, row.index, row.positionMs)
        }
    }

    /** Minimal browse tree: one playable list of tracks under the root node. */
    private class RoomBrowseTreeProvider(private val db: NoizDatabase) : BrowseTreeProvider {

        override fun root(): MediaItem = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Library")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

        override fun children(parentId: String, page: Int, pageSize: Int): ImmutableList<MediaItem> {
            if (parentId != ROOT_ID) return ImmutableList.of()
            val tracks = runCatching {
                runBlocking { db.trackExtraDao().allFlow().first() }
            }.getOrDefault(emptyList())
            val window = if (pageSize <= 0) tracks else tracks.drop(page * pageSize).take(pageSize)
            return ImmutableList.copyOf(window.map { it.toPlayableMediaItem() })
        }

        override fun searchBlocking(query: String): List<MediaItem> {
            if (query.isBlank()) return emptyList()
            val rows = runCatching {
                runBlocking { db.searchDao().search("$query*", 50) }
            }.getOrDefault(emptyList())
            return rows.asSequence()
                .filter { it.entityType == KIND_TRACK }
                .mapNotNull { runBlocking { db.trackDao().byId(it.entityId) } }
                .map { it.toPlayableMediaItem() }
                .toList()
        }

        override fun resolveBlocking(mediaIds: List<String>): List<MediaItem> =
            if (mediaIds.isEmpty()) {
                emptyList()
            } else {
                // SQL `IN` has no defined order; re-map through the caller's list so an
                // album queue does not come back shuffled.
                val byId = runBlocking { db.trackDao().byIds(mediaIds) }.associateBy { it.id }
                mediaIds.mapNotNull { byId[it] }.map { it.toPlayableMediaItem() }
            }
    }

    private fun TrackEntity.toPlayableMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        // Must match the shape SignedUrlResolver parses: noiz://track/{id}/{rendition}.
        // The old "noiz://$id/orig" put the id in the host, which the resolver read as
        // the literal string "track" after it was switched to path segments.
        .setUri("noiz://track/$id/orig")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistName)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .setRequestMetadata(
            // RequestMetadata.Builder takes a whole Bundle; there is no putExtra.
            // NoizPlayer reads these back off `requestMetadata.extras` to rebuild the
            // EngineTrack (duration, loudness, gapless flag) after process death.
            MediaItem.RequestMetadata.Builder()
                .setExtras(
                    android.os.Bundle().apply {
                        putLong(NoizMediaExtras.DURATION_MS, durationMs)
                        putString(NoizMediaExtras.RENDITION, "orig")
                        loudnessLufs?.let { putFloat(NoizMediaExtras.LOUDNESS_LUFS, it) }
                        truePeakDb?.let { putFloat(NoizMediaExtras.TRUE_PEAK_DB, it) }
                        if (gaplessNext) putBoolean(NoizMediaExtras.GAPLESS_NEXT, true)
                    },
                )
                .build(),
        )
        .build()

    private class RoomFavoritesWriter(
        private val db: NoizDatabase,
        private val ioScope: CoroutineScope,
        private val sessionStore: SessionStore,
        private val catalog: CatalogRepository,
    ) : FavoritesWriter {
        override fun setLiked(mediaId: String, liked: Boolean) {
            ioScope.launch {
                // The repository writes the Room row and its outbox op together, so a
                // like from the notification/Android Auto actually reaches the server.
                val userId = sessionStore.session.first()?.user?.id ?: LOCAL_USER
                runCatching { catalog.setFavorite(userId, mediaId, liked) }
            }
        }
    }
}
