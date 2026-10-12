package com.noiz.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single owner of playback (§3.3, §8.2, §8.3).
 *
 * Owns the [DualPlayerEngine] and exposes it to the system through one
 * [MediaLibrarySession] bound to a [NoizPlayer] facade. The notification, the in-app
 * UI, Bluetooth and Android Auto therefore all observe the same snapshot and can never
 * disagree about what is playing (§8.3).
 *
 * The previous implementation built a bare `ExoPlayer` and ignored the engine entirely,
 * so normalization, crossfade, caching, signed-URL resolution and queue persistence
 * were all dead code.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var engine: DualPlayerEngine
    private lateinit var player: NoizPlayer
    private var librarySession: MediaLibrarySession? = null

    private lateinit var restoreStore: QueueRestoreStore
    private lateinit var browseTree: BrowseTreeProvider
    private lateinit var favorites: FavoritesWriter
    private lateinit var settings: PlaybackSettings

    private var sleepTimer: SleepTimerController? = null
    private var persistJob: Job? = null

    /** Wall-clock at which playback ended and the service may stop itself (§8.3). */
    private var idleSinceMs: Long = 0L

    override fun onCreate() {
        super.onCreate()

        engine = PlaybackComponents.engineFactory(this, serviceScope)
        restoreStore = PlaybackComponents.restoreStoreFactory(this)
        browseTree = PlaybackComponents.browseTreeFactory(this)
        favorites = PlaybackComponents.favoritesFactory(this)
        settings = PlaybackComponents.settingsFactory(this)

        // User settings must reach the engine or crossfade/normalization never apply.
        engine.setCrossfadeMs(settings.crossfadeMs)
        engine.normalizationTargetLufs = settings.normalizationTargetLufs
        engine.setVolume(settings.outputVolume)

        player = NoizPlayer(engine, serviceScope)
        sleepTimer = SleepTimerController(engine, serviceScope)

        restoreSavedQueue()

        librarySession = MediaLibrarySession.Builder(this, player, libraryCallback)
            .setSessionActivity(pendingIntentToNowPlaying())
            .build()

        startPositionPersistence()
        startIdleWatchdog()
    }

    override fun onGetSession(
        controller: MediaSession.ControllerInfo,
    ): MediaLibrarySession = librarySession
            ?: error("onGetSession called before onCreate finished")

    // -----------------------------------------------------------------------
    // Browse tree + custom commands (§3.6, §8.3)
    // -----------------------------------------------------------------------

    private val libraryCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            browseTree.root()
                ?.let { LibraryResult.ofItem(it, params) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            parentMediaId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = runCatching { browseTree.children(parentMediaId, page, pageSize) }
                .getOrElse { ImmutableList.of() }
            return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            runCatching { browseTree.resolveBlocking(listOf(mediaId)).firstOrNull() }
                .fold(
                    onSuccess = { item ->
                        if (item == null) {
                            LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                        } else {
                            LibraryResult.ofItem(item, null)
                        }
                    },
                    onFailure = { LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN) },
                ),
        )

        override fun onSearch(
            session: MediaLibrarySession,
            controller: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            // Routed through the provider so the query is escaped before it reaches FTS;
            // raw user input raised SQLiteException on any operator character.
            runCatching { browseTree.searchBlocking(query) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
    }

    /**
     * Transport-level callbacks.
     *
     * In Media3 1.5.1 `MediaLibrarySession.Builder.setCallback` is package-private, so
     * these cannot be attached to the library session. `onCustomCommand` and the
     * controller authorization therefore live on a plain [MediaSession.Callback] that
     * `NoizPlayer` exposes via [MediaSessionConnector]-free direct dispatch instead —
     * see [dispatchSessionCommand] / [isTrustedSystem].
     */
    private val sessionCallback = object : MediaSession.Callback {

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            dispatchSessionCommand(customCommand, args)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    if (isTrustedSystem(controller)) {
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    } else {
                        // Untrusted callers may control transport and read the timeline,
                        // but may not replace the queue or read the whole catalogue.
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    },
                )
                .build()
    }

    /** Routes a custom session command to the engine / Room. */
    private fun dispatchSessionCommand(customCommand: SessionCommand, args: Bundle) {
        val mediaId = args.getString(NoizCommands.ARG_MEDIA_ID)
        when (customCommand.customAction) {
            NoizCommands.ACTION_LIKE -> mediaId?.let { favorites.setLiked(it, true) }
            NoizCommands.ACTION_UNLIKE -> mediaId?.let { favorites.setLiked(it, false) }
            NoizCommands.ACTION_SHUFFLE -> engine.publishCommandResult(customCommand.customAction)
            NoizCommands.ACTION_REPEAT -> engine.publishCommandResult(customCommand.customAction)
            NoizCommands.ACTION_CYCLE_SLEEP_TIMER ->
                sleepTimer?.cycle()?.let {
                    engine.publishCommandResult(customCommand.customAction)
                }
        }
    }

    private fun isTrustedSystem(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == packageName ||
            controller.packageName in TRUSTED_CONTROLLERS

    private fun pendingIntentToNowPlaying(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    // -----------------------------------------------------------------------
    // Position persistence + idle shutdown (§8.3, §11 row 13)
    // -----------------------------------------------------------------------

    /**
     * Writes the canonical queue and position every 5 s, and on task removal, so a
     * process death restores where the user was rather than restarting the album.
     */
    private fun startPositionPersistence() {
        persistJob?.cancel()
        persistJob = serviceScope.launch {
            while (isActive) {
                delay(POSITION_PERSIST_INTERVAL_MS)
                persistNow()
            }
        }
    }

    private fun persistNow() {
        val snapshot = engine.snapshot.value
        if (snapshot.queue.isEmpty()) return
        runCatching {
            restoreStore.save(
                queueMediaIds = snapshot.queue.map { it.mediaItem.mediaId },
                index = snapshot.currentIndex,
                positionMs = snapshot.positionMs,
                renditionIds = snapshot.queue.map { it.renditionId },
            )
        }
    }

    private fun restoreSavedQueue() {
        val restored = runCatching { restoreStore.loadBlocking() }.getOrNull() ?: return
        val resolved = runCatching {
            browseTree.resolveBlocking(restored.queueMediaIds)
        }.getOrNull() ?: return
        if (resolved.isEmpty()) return

        engine.setQueue(
            resolved.map { item ->
                EngineTrack(
                    mediaItem = item,
                    renditionId = restored.renditionIds.getOrNull(resolved.indexOf(item))
                        ?: EngineTrack.DEFAULT_RENDITION,
                    durationMs = item.mediaMetadata.extras?.getLong(EXTRA_DURATION_MS) ?: 0L,
                )
            },
            startIndex = restored.index,
            startPositionMs = restored.positionMs,
        )
    }

    private fun startIdleWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(IDLE_POLL_INTERVAL_MS)
                val playing = engine.snapshot.value.isPlaying ||
                    engine.snapshot.value.playWhenReady
                if (playing) {
                    idleSinceMs = 0L
                } else if (idleSinceMs == 0L) {
                    idleSinceMs = SystemClock.elapsedRealtime()
                } else if (SystemClock.elapsedRealtime() - idleSinceMs > IDLE_TIMEOUT_MS) {
                    // §8.3: stop ourselves once playback ended and the idle timeout
                    // elapsed, instead of holding a foreground service open forever.
                    persistNow()
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistJob?.cancel()
        persistNow()
        librarySession?.release()
        librarySession = null
        player.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The Library screen starts the service with an explicit queue; the MediaSession
        // then owns it for the notification, Bluetooth and Android Auto.
        if (intent?.action == ACTION_PLAY) {
            val ids = intent.getStringArrayListExtra(EXTRA_TRACK_IDS).orEmpty()
            val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            if (ids.isNotEmpty()) {
                val resolved = runCatching { browseTree.resolveBlocking(ids) }.getOrNull().orEmpty()
                if (resolved.isNotEmpty()) {
                    engine.setQueue(
                        resolved.map { item ->
                            EngineTrack(
                                mediaItem = item,
                                renditionId = EngineTrack.DEFAULT_RENDITION,
                                durationMs = item.mediaMetadata.extras?.getLong(EXTRA_DURATION_MS) ?: 0L,
                            )
                        },
                        startIndex.coerceIn(0, resolved.lastIndex),
                    )
                    engine.play()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    companion object {
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        private const val IDLE_POLL_INTERVAL_MS = 2_000L
        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val EXTRA_DURATION_MS = "noiz.durationMs"

        /** Start the service with a queue resolved from local track ids. */
        const val ACTION_PLAY = "com.noiz.playback.PLAY"
        const val EXTRA_TRACK_IDS = "track_ids"
        const val EXTRA_START_INDEX = "start_index"

        /**
         * Controllers allowed full access to the session: the app itself plus the
         * system surfaces a user would expect to control playback from (§3.2, §8.3).
         */
        private val TRUSTED_CONTROLLERS = setOf(
            "com.android.bluetooth",
            "com.google.android.projection.gearhead",
            "com.android.systemui",
            "com.google.android.googlequicksearchbox",
        )

        @JvmStatic
        fun start(context: Context) {
            // MediaSessionService promotes itself to the foreground when the session has
            // something playing. Calling startForegroundService() with no active playback
            // makes Android throw ForegroundServiceDidNotStartInTimeException on API 31+,
            // so a plain start is correct here.
            context.startService(Intent(context, PlaybackService::class.java))
        }
    }
}