package com.noiz.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground playback service (§3.4, §3.5).
 *
 * Declared with `foregroundServiceType="mediaPlayback"`; Media3 promotes it to the
 * foreground when playback starts. It hosts the [MediaLibrarySession] that backs the
 * notification, lock screen, Bluetooth AVRCP and the Android Auto browse tree.
 *
 * Audio focus is owned by the primary ExoPlayer (built with `handleAudioFocus = true`),
 * so transient loss during calls/navigation is handled by the platform focus protocol
 * without duplicating it here. [ResumeFader] adds the 300 ms fade-in on focus regain.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private lateinit var engine: DualPlayerEngine
    private lateinit var player: NoizPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var restoreStore: QueueRestoreStore
    private var browseTree: BrowseTreeProvider? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resumeFader = ResumeFader(scope)
    private var positionPersistJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        engine = PlaybackComponents.engine(this, scope)
        player = NoizPlayer(engine)
        restoreStore = PlaybackComponents.restoreStore(this)
        browseTree = PlaybackComponents.browseTree(this)

        engine.setCrossfadeMs(PlaybackComponents.settings(this).crossfadeMs)
        engine.setShuffleModeEnabled(false)
        engine.setRepeatMode(Player.REPEAT_MODE_OFF)

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(launchIntent())
            .build()

        var wasPlaying = false
        engine.snapshot
            .onEach { snapshot ->
                // 300 ms equal-power fade-in whenever playback resumes (§11 row 4).
                if (snapshot.isPlaying && !wasPlaying) {
                    resumeFader.fadeIn(
                        setVolume = { engine.setVolume(it) },
                        target = engine.volume,
                    )
                }
                wasPlaying = snapshot.isPlaying
                // 5 s autosave (§11 row 13) plus an explicit save on every pause/change.
                if (!snapshot.isPlaying) persist(snapshot)
            }
            .launchIn(scope)

        startPositionPersistLoop()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    private fun launchIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent().setPackage(packageName)
                .setAction(ACTION_OPEN_NOW_PLAYING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // -----------------------------------------------------------------------
    // Position persistence
    // -----------------------------------------------------------------------

    private fun startPositionPersistLoop() {
        positionPersistJob?.cancel()
        positionPersistJob = scope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                persist(engine.snapshot.value)
            }
        }
    }

    private fun persist(snapshot: EngineSnapshot) {
        if (snapshot.queue.isEmpty()) return
        restoreStore.save(
            queueMediaIds = snapshot.queue.map { it.mediaItem.mediaId },
            index = snapshot.currentIndex,
            positionMs = snapshot.positionMs,
            renditionIds = snapshot.queue.map { it.renditionId },
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persist(engine.snapshot.value)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persist(engine.snapshot.value)
        positionPersistJob?.cancel()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Session callback
    // -----------------------------------------------------------------------

    private inner class SessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ConnectionResult {
            // Custom transport actions differ between the notification (compact) and
            // Android Auto (up to four), so the button list is scoped per controller.
            val isAuto = controller.isAutomotive
            val commands = buildSet {
                addAll(NoizCommands.all)
                if (!isAuto) addAll(NoizCommands.notificationExtras)
            }
            return ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .addAllSessionCommands(commands)
                        .build(),
                )
                .build()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Voice: "play something by Radiohead" arrives here as a search query.
            val query = mediaItems.firstOrNull()
                ?.requestMetadata
                ?.searchQuery
            if (!query.isNullOrBlank()) {
                val resolved = browseTree?.searchBlocking(query).orEmpty()
                if (resolved.isNotEmpty()) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(mediaItems + resolved, 0, 0L),
                    )
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
            )
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Bluetooth "play" after process death (§3.5). Media3 calls this from a
            // background thread, so the store read is blocking on purpose.
            val restored = restoreStore.loadBlocking()
                ?: return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L),
                )
            val items = browseTree?.resolveBlocking(restored.queueMediaIds).orEmpty()
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(items, restored.index, restored.positionMs),
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                NoizCommands.ACTION_LIKE -> {
                    PlaybackComponents.favorites(this@PlaybackService)
                        .setLiked(args.getString(NoizCommands.ARG_MEDIA_ID).orEmpty(), true)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                NoizCommands.ACTION_UNLIKE -> {
                    PlaybackComponents.favorites(this@PlaybackService)
                        .setLiked(args.getString(NoizCommands.ARG_MEDIA_ID).orEmpty(), false)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                NoizCommands.ACTION_CYCLE_SLEEP_TIMER -> {
                    SleepTimerController(engine, scope).cycle()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
        }

        // -------------------------------------------------------------------
        // Android Auto browse tree (§3.6) — must answer < 2 s from cache.
        // -------------------------------------------------------------------

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browseTree?.root()
            return if (root == null) {
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO))
            } else {
                Futures.immediateFuture(LibraryResult.ofItem(root, params))
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = browseTree?.children(parentId, page, pageSize).orEmpty()
            return Futures.immediateFuture(
                LibraryResult.ofItemList(children, params),
            )
        }
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "com.noiz.action.OPEN_NOW_PLAYING"
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}

/**
 * 300 ms equal-power fade-in after play resumes (§11 row 4). ExoPlayer handles the
 * focus negotiation; this only shapes the ramp so a call ending does not snap back to
 * full level.
 */
private class ResumeFader(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun fadeIn(setVolume: (Float) -> Unit, target: Float = 1f) {
        job?.cancel()
        job = scope.launch {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val t = ((SystemClock.elapsedRealtime() - start) / 300f).coerceIn(0f, 1f)
                setVolume(t * target)
                if (t >= 1f) break
                delay(16)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}

/** Hilt-free service locator so this module stays independent of :core:data. */
object PlaybackComponents {
    @Volatile
    var engineFactory: ((PlaybackService, CoroutineScope) -> DualPlayerEngine)? = null
    @Volatile
    var restoreStoreFactory: ((PlaybackService) -> QueueRestoreStore)? = null
    @Volatile
    var browseTreeFactory: ((PlaybackService) -> BrowseTreeProvider)? = null
    @Volatile
    var favoritesFactory: ((PlaybackService) -> FavoritesWriter)? = null
    @Volatile
    var settingsFactory: ((PlaybackService) -> PlaybackSettings)? = null

    fun engine(service: PlaybackService, scope: CoroutineScope): DualPlayerEngine =
        requireNotNull(engineFactory) { "PlaybackComponents.engineFactory not installed" }(service, scope)

    fun restoreStore(service: PlaybackService): QueueRestoreStore =
        requireNotNull(restoreStoreFactory) { "restoreStoreFactory not installed" }(service)

    fun browseTree(service: PlaybackService): BrowseTreeProvider =
        requireNotNull(browseTreeFactory) { "browseTreeFactory not installed" }(service)

    fun favorites(service: PlaybackService): FavoritesWriter =
        requireNotNull(favoritesFactory) { "favoritesFactory not installed" }(service)

    fun settings(service: PlaybackService): PlaybackSettings =
        requireNotNull(settingsFactory) { "settingsFactory not installed" }(service)
}
