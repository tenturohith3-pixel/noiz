package com.noiz.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * `SimpleBasePlayer` facade over [DualPlayerEngine] (§3.3).
 *
 * One coherent `State` so MediaSession, the notification and Android Auto never see the
 * dual-player swap: `getState()` reads [DualPlayerEngine.snapshot], and every handler
 * delegates to the engine. The playlist view is the *canonical* queue, not whichever
 * ExoPlayer happens to be loaded — that is what lets the UI reorder and remove items
 * while gapless mode has the rest of the queue resident in the primary player.
 *
 * SimpleBasePlayer diffs consecutive `getState()` snapshots and emits the matching
 * events itself, so state changes surface via a single [invalidateState] call whenever
 * the engine snapshot changes.
 */
class NoizPlayer(
    private val engine: DualPlayerEngine,
    scope: CoroutineScope,
) : SimpleBasePlayer(android.os.Looper.getMainLooper()) {

    // invalidateState() must run on the looper this facade was built with.
    private val collectorJob = scope.launch {
        require(scope.coroutineContext[kotlinx.coroutines.Job] != null) {
            "NoizPlayer requires a scope with a Job so the collector can be cancelled"
        }
        engine.snapshot.collect { invalidateState() }
    }

    /** The engine's current state, for handler decisions. */
    private val snapshot: EngineSnapshot get() = engine.snapshot.value

    override fun getState(): State {
        val snapshot = engine.snapshot.value

        val items = snapshot.queue.map { track ->
            MediaItemData.Builder(track.mediaItem.mediaId)
                .setMediaItem(track.mediaItem)
                .setMediaMetadata(track.mediaItem.mediaMetadata)
                .setDurationUs(if (track.durationMs > 0) track.durationMs * 1000 else C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }

        return State.Builder()
            .setAvailableCommands(availableCommands)
            // Engine state ints match Player.STATE_* / Player.REPEAT_MODE_*.
            .setPlaybackState(snapshot.playbackState)
            // `playWhenReady` stays true across a rebuffer, unlike `isPlaying`. Using
            // the latter made MediaSession treat a buffering player as stopped and drop
            // the foreground notification mid-track.
            .setPlayWhenReady(
                snapshot.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setPlaylist(items)
            .setCurrentMediaItemIndex(
                snapshot.currentIndex.coerceIn(0, maxOf(0, snapshot.queue.lastIndex)),
            )
            .setContentPositionMs(snapshot.positionMs.coerceAtLeast(0L))
            .setContentBufferedPositionMs(
                PositionSupplier.getConstant(snapshot.bufferedPositionMs.coerceAtLeast(0L)),
            )
            .setRepeatMode(snapshot.repeatMode)
            .setShuffleModeEnabled(snapshot.shuffleModeEnabled)
            .setVolume(engine.volume)
            .build()
    }

    // -----------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        engine.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        // Stop invalidating a released facade.
        collectorJob.cancel()
        engine.release()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        // Apply exactly one target. Doing both below meant a combined
        // "seek to item N at T" ran transitionTo() and then seekTo(), and the second
        // call re-aborted the transition the first had just started.
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != snapshot.currentIndex) {
            engine.seekToIndex(mediaItemIndex, userSkip = false)
        } else if (positionMs != C.TIME_UNSET) {
            engine.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        engine.setRepeatMode(repeatMode)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        engine.setShuffleModeEnabled(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        engine.setVolume(volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        // Variable speed is not part of the noiz playback model; acknowledge and ignore.
        return Futures.immediateVoidFuture()
    }

    // -----------------------------------------------------------------------
    // Playlist (canonical queue)
    // -----------------------------------------------------------------------

    /**
 * Builds the canonical queue from externally supplied [MediaItem]s.
 *
 * Loudness, true peak, rendition and the gapless flag travel in
 * `MediaItem.requestMetadata` so Android Auto, the Assistant and session restore all
 * keep normalization and gapless semantics. Previously this rebuilt bare
 * `EngineTrack`s, so anything set through a `MediaController` played un-normalized with
 * crossfades applied across album-continuous tracks.
 */
override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        engine.setQueue(
            mediaItems.map { item -> item.toEngineTrack() },
            startIndex,
            startPositionMs.coerceAtLeast(0L),
        )
        return Futures.immediateVoidFuture()
    }

    /**
 * Reads the engine metadata that travels with a `MediaItem`.
 *
 * `MediaItem.requestMetadata` is a `RequestMetadata`, not a Bundle — the Bundle lives
 * at `requestMetadata.extras`, which is where `MediaItem.Builder.setRequestMetadata`
 * puts anything passed through. `MediaItemData`/`durationMs` are not available here
 * because only the incoming `MediaItem` is.
 */
private fun MediaItem.toEngineTrack(): EngineTrack {
        val extras = requestMetadata?.extras
        return EngineTrack(
            mediaItem = this,
            renditionId = extras?.getString(KEY_RENDITION) ?: EngineTrack.DEFAULT_RENDITION,
            loudnessLufs = extras?.getFloatOrNull(KEY_LOUDNESS_LUFS),
            truePeakDb = extras?.getFloatOrNull(KEY_TRUE_PEAK_DB),
            gaplessNext = extras?.getBoolean(KEY_GAPLESS_NEXT) ?: false,
            durationMs = extras?.getLong(KEY_DURATION_MS) ?: 0L,
        )
    }

    private fun Bundle.getFloatOrNull(key: String): Float? =
        if (containsKey(key)) getFloat(key, Float.NaN).takeIf { !it.isNaN() } else null

    companion object {
        const val KEY_RENDITION = "noiz.rendition"
        const val KEY_LOUDNESS_LUFS = "noiz.loudnessLufs"
        const val KEY_TRUE_PEAK_DB = "noiz.truePeakDb"
        const val KEY_GAPLESS_NEXT = "noiz.gaplessNext"
        const val KEY_DURATION_MS = "noiz.durationMs"
    }
}
