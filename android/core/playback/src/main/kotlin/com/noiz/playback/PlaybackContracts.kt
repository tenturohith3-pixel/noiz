package com.noiz.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Process-wide service locator for playback collaborators.
 *
 * `PlaybackService` is instantiated by the Android framework and cannot receive
 * constructor injection, so `:app` installs its collaborators here from
 * `NoizApplication.onCreate` (via `AppGraph.install`) before the service binds.
 *
 * Every slot defaults to a factory that throws with an actionable message, so a
 * missing installation fails loudly at bind time instead of silently degrading to a
 * bare ExoPlayer.
 */
object PlaybackComponents {

    /** `(context, scope) -> engine`. The scope is the service's main-thread scope. */
    @Volatile
    var engineFactory: (Context, CoroutineScope) -> DualPlayerEngine = { _, _ ->
        notInstalled("engineFactory")
    }

    @Volatile
    var restoreStoreFactory: (Context) -> QueueRestoreStore = { notInstalled("restoreStoreFactory") }

    @Volatile
    var browseTreeFactory: (Context) -> BrowseTreeProvider = { notInstalled("browseTreeFactory") }

    @Volatile
    var favoritesFactory: (Context) -> FavoritesWriter = { notInstalled("favoritesFactory") }

    @Volatile
    var settingsFactory: (Context) -> PlaybackSettings = { notInstalled("settingsFactory") }

    private fun notInstalled(name: String): Nothing = error(
        "PlaybackComponents.$name was never installed. " +
            "Call AppGraph.install(this) from NoizApplication.onCreate before the " +
            "MediaSessionService binds.",
    )
}

/** Custom session commands (§3.5). */
object NoizCommands {
    const val ACTION_LIKE = "com.noiz.action.LIKE"
    const val ACTION_UNLIKE = "com.noiz.action.UNLIKE"
    const val ACTION_SHUFFLE = "com.noiz.action.SHUFFLE"
    const val ACTION_REPEAT = "com.noiz.action.REPEAT"
    const val ACTION_CYCLE_SLEEP_TIMER = "com.noiz.action.CYCLE_SLEEP_TIMER"
    const val ACTION_OPEN_OUTPUT_PICKER = "com.noiz.action.OPEN_OUTPUT_PICKER"

    const val ARG_MEDIA_ID = "media_id"

    private fun cmd(action: String) = SessionCommand(action, Bundle.EMPTY)

    /** Available everywhere, including Android Auto (max four custom actions there). */
    val all: Set<SessionCommand> = setOf(
        cmd(ACTION_LIKE),
        cmd(ACTION_UNLIKE),
        cmd(ACTION_SHUFFLE),
        cmd(ACTION_REPEAT),
    )

    /** Notification-only extras. */
    val notificationExtras: Set<SessionCommand> = setOf(
        cmd(ACTION_CYCLE_SLEEP_TIMER),
        cmd(ACTION_OPEN_OUTPUT_PICKER),
    )
}

/** Queue/position snapshot used for process-death recovery (§11 row 13). */
data class RestoredQueue(
    val queueMediaIds: List<String>,
    val renditionIds: List<String>,
    val index: Int,
    val positionMs: Long,
)

/** Implemented in :core:data over Room's `playback_state` table. */
interface QueueRestoreStore {
    fun save(
        queueMediaIds: List<String>,
        index: Int,
        positionMs: Long,
        renditionIds: List<String>,
    )

    /** Blocking on purpose: called by Media3 off the main thread. */
    fun loadBlocking(): RestoredQueue?
}

/**
 * Android Auto / notification browse tree (§3.6). Implementations answer from Room so
 * every call returns well inside the 2 s budget, then refresh in the background.
 */
interface BrowseTreeProvider {
    fun root(): MediaItem?

    fun children(parentId: String, page: Int, pageSize: Int): ImmutableList<MediaItem>

    /** Voice search: "play something by Radiohead". Falls back to local FTS offline. */
    fun searchBlocking(query: String): List<MediaItem>

    /** Resolves persisted media ids back into playable MediaItems after process death. */
    fun resolveBlocking(mediaIds: List<String>): List<MediaItem>
}

/** Optimistic favorite writes go straight to Room + outbox (§3.3). */
interface FavoritesWriter {
    fun setLiked(mediaId: String, liked: Boolean)
}

/** Snapshot of playback-relevant settings, read once when the service starts. */
interface PlaybackSettings {
    val crossfadeMs: Long
    val normalizationTargetLufs: Float?
    val pureMode: Boolean
    val outputVolume: Float
}

/**
 * Sleep timer (§6.1 FR-PB-13). Runs in the foreground service while audio plays, so
 * Doze cannot suspend it and no exact-alarm permission is needed (§11 row 22).
 */
class SleepTimerController(
    private val engine: DualPlayerEngine,
    private val scope: CoroutineScope,
) {

    enum class Mode(val durationMs: Long?, val label: String) {
        Off(null, "Off"),
        Minutes15(15 * 60_000L, "15 minutes"),
        Minutes30(30 * 60_000L, "30 minutes"),
        Minutes60(60 * 60_000L, "1 hour"),
        EndOfTrack(null, "End of track"),
    }

    var mode: Mode = Mode.Off
        private set

    private var timeoutJob: Job? = null
    private var endOfTrackJob: Job? = null

    /** Cycles Off → 15 → 30 → 60 → End of track → Off and returns the new mode. */
    fun cycle(): Mode {
        val order = Mode.entries
        val next = order[(mode.ordinal + 1) % order.size]
        setMode(next)
        return next
    }

    fun setMode(next: Mode) {
        cancel()
        mode = next
        when (next) {
            Mode.Off -> Unit
            Mode.EndOfTrack -> armEndOfTrack()
            else -> armTimeout(next.durationMs ?: return)
        }
    }

    private fun armTimeout(durationMs: Long) {
        timeoutJob = scope.launch {
            delay(durationMs)
            fadeOutAndPause()
        }
    }

    private fun armEndOfTrack() {
        engine.setRepeatMode(Player.REPEAT_MODE_OFF)
        endOfTrackJob = engine.snapshot
            .onEach { snapshot ->
                if (snapshot.playbackState == Player.STATE_ENDED) {
                    fadeOutAndPause()
                    cancel()
                }
            }
            .launchIn(scope)
    }

    /** Stops within 500 ms of the target: a 2.5 s fade inside the 15 s tail window. */
    private suspend fun fadeOutAndPause() {
        val target = engine.volume
        val steps = (FADE_MS / 16L).toInt()
        for (i in steps downTo 0) {
            engine.setVolume(target * (i / steps.toFloat()))
            delay(16L)
        }
        engine.pause()
        engine.setVolume(target)
    }

    fun cancel() {
        timeoutJob?.cancel()
        timeoutJob = null
        endOfTrackJob?.cancel()
        endOfTrackJob = null
    }

    companion object {
        /** PRD §7.4: fade-out over the last 15 s; we use 2.5 s so it is never abrupt. */
        const val FADE_MS = 2_500L
    }
}
