package com.noiz.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The UI's window into playback (§3.3, §8.2).
 *
 * ViewModels take this interface rather than a `MediaController` so that:
 *  - screens are testable without binding to a foreground service;
 *  - the notification, Android Auto and the in-app UI all observe the *same* state —
 *    the service remains the single owner, exactly as §8.3 requires.
 *
 * The concrete implementation lives in `:app`, which is the composition root: it owns
 * the `MediaController` bound to [PlaybackService] and translates calls into session
 * commands.
 */
interface PlaybackController {

    /** One coherent snapshot; never exposes the dual-player swap underneath. */
    val state: StateFlow<PlaybackState>

    /** Ordered queue as the user sees it (canonical, gapless-index aware). */
    val queueFlow: StateFlow<List<QueueEntry>>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()

    fun setShuffle(enabled: Boolean)
    fun cycleRepeatMode()

    /** Jumps to an absolute queue position (queue screen reordering and taps). */
    fun playQueueIndex(index: Int)

    /** Removes [index] from the running queue without restarting playback. */
    fun removeFromQueue(index: Int)

    /** Moves [from] to [to], preserving the currently playing item. */
    fun moveInQueue(from: Int, to: Int)

    /** Whether the session is connected; screens show a "player unavailable" state. */
    val isConnected: StateFlow<Boolean>
}

/** UI-facing playback state (§8.2 `NowPlayingUiState`). */
data class PlaybackState(
    val current: QueueEntry? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = androidx.media3.common.Player.REPEAT_MODE_OFF,
    /** `lossless` | `high` | `saver` | `offline` | `unknown` — never claim lossless for a transcode. */
    val quality: String = "unknown",
    val error: String? = null,
) {
    val hasQueue: Boolean get() = current != null
}

data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val trackId: String,
    val title: String,
    val subtitle: String,
    val artworkHash: String?,
    val durationMs: Long,
    val isCurrent: Boolean,
    val unavailable: Boolean = false,
)

/**
 * [PlaybackController] backed directly by a [DualPlayerEngine].
 *
 * The engine is the session's single owner (§8.3), so the in-app UI talks to it through
 * this adapter instead of binding a second MediaController — which would give the
 * notification, Android Auto and the screens three different views of the same state.
 * `PlaybackService` hands the same engine instance to the session, so both surfaces stay
 * coherent.
 */
class EnginePlaybackController(
    private val engine: DualPlayerEngine,
    private val queueController: QueueController,
) : PlaybackController {

    // A single scope shared by both flows so the adapter owns exactly one SupervisorJob.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val state: StateFlow<PlaybackState> =
        engine.snapshot.map { it.toPlaybackState() }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = engine.snapshot.value.toPlaybackState(),
        )

    override val queueFlow: StateFlow<List<QueueEntry>> =
        engine.snapshot.map { it.toQueueEntries() }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = engine.snapshot.value.toQueueEntries(),
        )

    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)

    override fun play() = engine.play()
    override fun pause() = engine.pause()
    override fun togglePlayPause() = if (engine.snapshot.value.isPlaying) pause() else play()
    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)
    override fun next() = engine.seekToNext()
    override fun previous() = engine.seekToPrevious()
    override fun setShuffle(enabled: Boolean) = engine.setShuffleModeEnabled(enabled)
    override fun cycleRepeatMode() = engine.cycleRepeatMode()

    override fun playQueueIndex(index: Int) = queueController.playAt(index)
    override fun removeFromQueue(index: Int) = queueController.removeFrom(index)
    override fun moveInQueue(from: Int, to: Int) = queueController.move(from, to)

    /** Stops the state collectors; call when the host screen goes away. */
    fun release() = scope.cancel()

    private fun EngineSnapshot.toPlaybackState() = PlaybackState(
        current = toQueueEntries().getOrNull(currentIndex),
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedMs = bufferedPositionMs,
        shuffleEnabled = shuffleModeEnabled,
        repeatMode = repeatMode,
        quality = "unknown",
        error = error?.errorCodeName,
    )

    private fun EngineSnapshot.toQueueEntries(): List<QueueEntry> {
        val broken = engine.unplayableMediaIds()
        return queue.mapIndexed { idx, track ->
            QueueEntry(
                index = idx,
                mediaId = track.mediaItem.mediaId,
                trackId = track.mediaItem.mediaId,
                title = track.mediaItem.mediaMetadata.title?.toString().orEmpty(),
                subtitle = track.mediaItem.mediaMetadata.artist?.toString().orEmpty(),
                artworkHash = null,
                durationMs = track.durationMs,
                isCurrent = idx == currentIndex,
                unavailable = track.mediaItem.mediaId in broken,
            )
        }
    }
}
