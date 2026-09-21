package com.noiz.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * A single logical [Player] presented to MediaSession, the notification, Android Auto
 * and the UI (§6.2).
 *
 * It computes one coherent State from the two ExoPlayers inside [engine], so nobody
 * above this class ever observes the crossfade swap. Command handling is delegated
 * back to the engine.
 */
@UnstableApi
class NoizPlayer(
    private val engine: DualPlayerEngine,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var playWhenReady: Boolean = false
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var repeatMode: Int = Player.REPEAT_MODE_OFF
    private var shuffleModeEnabled: Boolean = false
    private var volume: Float = 1f
    private var released = false

    init {
        engine.snapshot
            .onEach { invalidateState() }
            .launchIn(scope)
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    override fun getState(): State {
        val snap = engine.snapshot.value

        val playlist: List<MediaItemData> = snap.queue.mapIndexed { i, track ->
            MediaItemData.Builder(track.mediaItem.mediaId.ifEmpty { "noiz-item-$i" })
                .setMediaItem(track.mediaItem)
                .setDurationUs(
                    if (track.durationMs > 0L) Util.msToUs(track.durationMs) else C.TIME_UNSET,
                )
                .setIsSeekable(true)
                .setIsDynamic(false)
                .build()
        }

        val builder = State.Builder()
            .setAvailableCommands(ALL_COMMANDS)
            .setPlaylist(playlist)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                if (released) Player.STATE_IDLE else snap.playbackState,
            )
            .setPlaybackParameters(playbackParameters)
            .setRepeatMode(repeatMode)
            .setShuffleModeEnabled(shuffleModeEnabled)
            .setVolume(volume)
            .setContentPositionMs(if (playlist.isEmpty()) 0L else snap.positionMs)

        if (playlist.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(snap.currentIndex.coerceIn(0, playlist.lastIndex))
        }

        return builder.build()
    }

    // -----------------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------------

    override fun handleSetPlayWhenReady(
        playWhenReady: Boolean,
        playerCommand: Int,
    ): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady) engine.play() else engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        engine.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        engine.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        if (!released) {
            released = true
            scope.cancel()
            engine.release()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        // MediaItems here already carry a `noiz://track/{id}/{rendition}` URI; the
        // loudness/gapless extras are read back out so normalization and crossfade
        // decisions survive the trip through the session.
        val tracks = mediaItems.map { item ->
            val extras = item.mediaMetadata.extras
            EngineTrack(
                mediaItem = item,
                renditionId = item.uri.lastPathSegment ?: "orig",
                loudnessLufs = extras?.getFloat(EXTRA_LUFS, Float.NaN)?.takeIf { it.isFinite() },
                truePeakDb = extras?.getFloat(EXTRA_PEAK_DB, Float.NaN)?.takeIf { it.isFinite() },
                gaplessNext = extras?.getBoolean(EXTRA_GAPLESS_NEXT) ?: false,
                durationMs = extras?.getLong(EXTRA_DURATION_MS) ?: 0L,
            )
        }
        engine.setQueue(tracks, startIndex, startPositionMs)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            -> engine.seekToPrevious()

            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            -> engine.seekToNext()

            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> engine.seekToIndex(mediaItemIndex)
            else -> engine.seekTo(positionMs)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        engine.setRepeatMode(repeatMode)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        this.shuffleModeEnabled = shuffleModeEnabled
        engine.setShuffleModeEnabled(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        this.volume = volume.coerceIn(0f, 1f)
        engine.setVolume(this.volume)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(
        playbackParameters: PlaybackParameters,
    ): ListenableFuture<*> {
        // Crossfade math assumes real-time playback; speed changes are forwarded but
        // the ramp duration is scaled by the ratio in the engine.
        this.playbackParameters = playbackParameters
        engine.currentPlayer().playbackParameters = playbackParameters
        return Futures.immediateVoidFuture()
    }

    /** Exposed to the service so the media notification can label the queue. */
    fun engineQueue(): List<EngineTrack> = engine.snapshot.value.queue

    companion object {
        const val EXTRA_LUFS = "noiz.loudness_lufs"
        const val EXTRA_PEAK_DB = "noiz.true_peak_db"
        const val EXTRA_GAPLESS_NEXT = "noiz.gapless_next"
        const val EXTRA_DURATION_MS = "noiz.duration_ms"

        private val ALL_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAllCommands()
            .build()
    }
}
