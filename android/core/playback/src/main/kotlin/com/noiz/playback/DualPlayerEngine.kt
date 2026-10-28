package com.noiz.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the engine needs to play one queue item without touching the network layer. */
data class EngineTrack(
    val mediaItem: MediaItem,
    val renditionId: String,
    val loudnessLufs: Float? = null,
    val truePeakDb: Float? = null,
    /** Continuous with the next track: never crossfaded (§6.2). */
    val gaplessNext: Boolean = false,
    val durationMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_RENDITION = "orig"
    }
}

enum class PlaybackMode {
    /** crossfade == 0: one ExoPlayer plays the playlist, ExoPlayer handles gapless. */
    Gapless,

    /** crossfade > 0: two players, manual advancement with an equal-power ramp. */
    Dual,
}

data class EngineSnapshot(
    val queue: List<EngineTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    /**
     * Distinct from [isPlaying]: this stays true while buffering or preparing, which is
     * what MediaSession and the foreground service key off. Conflating the two made the
     * notification flicker and the service drop on every rebuffer.
     */
    val playWhenReady: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val mode: PlaybackMode = PlaybackMode.Gapless,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: PlaybackException? = null,
)

/**
 * Owns `playerA`/`playerB` and promotes the prefetched player at track boundaries
 * (§3.4, §6.2).
 *
 * The canonical queue lives here, not in either ExoPlayer, so the facade can present
 * one coherent playlist while the two decoders are swapped underneath. That is what
 * keeps MediaSession, the notification and Android Auto unaware of the swap.
 *
 * Mode selection:
 *  - crossfade == 0 → [PlaybackMode.Gapless]; the primary player is loaded with the
 *    remaining queue and ExoPlayer honours encoder delay/padding across items.
 *  - crossfade  > 0 → [PlaybackMode.Dual]; each item is loaded alone and the engine
 *    prepares the next on the idle player, then ramps.
 */
class DualPlayerEngine(
    private val scope: CoroutineScope,
    private val assembler: ExoPlayerAssembler,
    private val crossfade: CrossfadeController = CrossfadeController(),
) {

    val primaryNormalizer: NormalizationProcessor = assembler.newNormalizer()
    val secondaryNormalizer: NormalizationProcessor = assembler.newNormalizer()

    /** Holds audio focus and is the player the session binds to. */
    val primary: ExoPlayer = assembler.buildPlayer(handleFocus = true, normalizer = primaryNormalizer)

    /** Never holds focus; only ever audible while a ramp is in flight. */
    val secondary: ExoPlayer = assembler.buildPlayer(handleFocus = false, normalizer = secondaryNormalizer)

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var active: ExoPlayer = primary

    @Volatile
    private var idle: ExoPlayer = secondary

    private var queue: List<EngineTrack> = emptyList()
    private var index: Int = 0

    /** Canonical index that maps to player index 0 in [PlaybackMode.Gapless]. */
    private var gaplessBaseIndex: Int = 0
    private var crossfadeMs: Long = 0L
    private var prefetchLeadMs: Long = PREFETCH_LEAD_MS

    /** Linear output volume, 0..1. Set by Settings; ramped by the service's resume fader. */
    var volume: Float = 1f
        private set

    private var shuffleModeEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF

    /**
     * Engine-owned shuffle permutation of canonical indices. Empty when shuffle is off.
     *
     * ExoPlayer's own shuffle cannot be used: the canonical queue stays unshuffled so
     * queue editing and index mapping work, which made a shuffled player index resolve
     * to the wrong track.
     */
    private var shuffleOrder: List<Int> = emptyList()

    /** Current playback-mode setting, applied to both players. */
    var normalizationTargetLufs: Float? = NormalizationProcessor.TARGET_NORMAL_LUFS
        set(value) {
            field = value
            applyNormalization(index)
        }

    /** Crossfade duration from Settings. 0 disables dual-player mode entirely. */
    fun setCrossfadeMs(ms: Long) {
        crossfadeMs = ms.coerceIn(0L, CrossfadeController.MAX_CROSSFADE_MS)
        if (crossfadeMs == 0L) {
            crossfade.abort(active, idle)
            idle.pause()
            idle.clearMediaItems()
            reloadForMode()
        } else if (_snapshot.value.mode == PlaybackMode.Gapless) {
            reloadForMode()
        }
        publish()
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        active.volume = volume
    }

    /** Current normalized loudness target, null in Pure mode. */
    fun normalizationTarget(): Float? = normalizationTargetLufs

    fun currentPlayer(): ExoPlayer = active

    // -----------------------------------------------------------------------
    // Queue
    // -----------------------------------------------------------------------

    /**
     * Replaces the queue. [startPositionMs] only applies to the first [startIndex].
     */
    fun setQueue(tracks: List<EngineTrack>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        queue = tracks
        index = startIndex.coerceIn(0, maxOf(0, tracks.lastIndex))
        // A new queue is a new context; a stale error from the previous one would
        // otherwise still be on screen.
        lastError = null
        unplayable.clear()
        cancelCrossfade()
        reloadForMode(startPositionMs)
        applyNormalization(index)
        publish()
    }

    private fun reloadForMode(startPositionMs: Long = 0L) {
        val mode = if (crossfadeMs == 0L) PlaybackMode.Gapless else PlaybackMode.Dual
        // Preserve the play intent: `stop()` also clears playWhenReady, so without this
        // every queue edit silently paused playback.
        val wasPlayWhenReady = active.playWhenReady
        active.stop()
        active.clearMediaItems()
        idle.stop()
        idle.clearMediaItems()

        when (mode) {
            PlaybackMode.Gapless -> {
                gaplessBaseIndex = index
                val remaining = queue.drop(index).map { it.mediaItem }
                if (remaining.isNotEmpty()) {
                    active.setMediaItems(remaining, 0, startPositionMs)
                    // Deliberately NOT delegating shuffle to ExoPlayer: the canonical
                    // queue stays unshuffled, so a shuffled player index mapped onto it
                    // reported the wrong track as playing and applied its loudness to the
                    // wrong item. The engine owns the shuffle order instead.
                    active.repeatMode = repeatMode
                }
            }

            PlaybackMode.Dual -> {
                queue.getOrNullSafe(index)?.let { track ->
                    active.setMediaItem(track.mediaItem, startPositionMs)
                }
                active.repeatMode = Player.REPEAT_MODE_OFF
                active.shuffleModeEnabled = false
            }
        }
        active.prepare()
        active.volume = volume
        if (wasPlayWhenReady) {
            active.play()
        }
    }

    // -----------------------------------------------------------------------
    // Transport
    // -----------------------------------------------------------------------

    fun play() {
        if (queue.isEmpty()) return
        active.play()
        startMonitor()
        publish()
    }

    fun pause() {
        // A crossfade in flight means `idle` is already playing at full volume, and
        // `run()`'s finally-block never pauses it. Pausing only `active` therefore left
        // audio playing after the user pressed pause.
        cancelCrossfade()
        active.pause()
        idle.pause()
        idle.volume = 0f
        publish()
    }

    fun stop() {
        cancelCrossfade()
        active.stop()
        idle.pause()
        stopMonitor()
        publish()
    }

    fun seekTo(positionMs: Long) {
        // A seek invalidates any in-flight ramp: snap both players (abort).
        cancelCrossfade()
        active.seekTo(positionMs)
        if (idle.isPlaying) {
            idle.pause()
            idle.clearMediaItems()
        }
        publish()
    }

    /** User-initiated skip: short 300 ms blend, never a full crossfade (§6.2). */
    fun seekToNext() {
        val next = nextIndex(forward = true, userInitiated = true) ?: return
        transitionTo(next, userSkip = true)
    }

    fun seekToPrevious() {
        val next = previousIndex() ?: return
        transitionTo(next, userSkip = true)
    }

    fun seekToIndex(target: Int, userSkip: Boolean = true) {
        if (target !in queue.indices) return
        transitionTo(target, userSkip)
    }

    private fun transitionTo(target: Int, userSkip: Boolean) {
        if (target !in queue.indices) return
        cancelCrossfade()
        // At a natural end `isPlaying` is already false while `playWhenReady` is still
        // true; using only isPlaying stopped dual-mode from starting the next track.
        val wasPlaying = active.isPlaying || active.playWhenReady || _snapshot.value.isPlaying

        if (_snapshot.value.mode == PlaybackMode.Gapless) {
            // Keep the playlist loaded so ExoPlayer can keep honouring encoder
            // delay/padding across items; a skip is just a playlist seek.
            index = target
            val playerIndex = target - gaplessBaseIndex
            if (playerIndex >= 0) {
                active.seekTo(playerIndex, 0L)
                applyNormalization(target)
                if (wasPlaying) active.play()
            } else {
                reloadForMode(startPositionMs = 0L)
                if (wasPlaying) active.play()
            }
            publish()
            return
        }

        val from = active
        index = target

        // User skip: 300 ms blend, suppressed for album-continuous tracks (§6.2).
        val shouldBlend = userSkip && wasPlaying && !queue[target].gaplessNext

        if (shouldBlend) {
            val to = idle
            val blend = minOf(CrossfadeController.SKIP_BLEND_MS, crossfadeMs)
            prepareOn(to, target, playWhenReady = true)
            val job = crossfade.launch(scope, from, to, blend, volume)
            crossfadeJob = job
            awaitCrossfadeThenSwap(job, target)
        } else {
            prepareOn(from, target, playWhenReady = wasPlaying)
            applyNormalization(target)
        }
        publish()
    }

    // -----------------------------------------------------------------------
    // Prefetch + boundary monitor
    // -----------------------------------------------------------------------

    private var monitorJob: Job? = null
    private var crossfadeJob: Job? = null
    private var nextPreparedIndex: Int = -1

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                if (_snapshot.value.mode == PlaybackMode.Dual) tickDualMode()
                publish()
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun tickDualMode() {
        val ended = active.playbackState == Player.STATE_ENDED

        // Never start a ramp while paused: `run()` calls `inPlayer.play()`, so a tick
        // landing after a `pause()` would resume audio with no user intent. A paused
        // player that has reached its natural end still needs the queue to advance —
        // the old early return left dual mode stuck on the last track forever.
        if (!active.isPlaying && !ended) return

        if (ended) {
            val next = nextIndex(forward = true, userInitiated = false)
                ?: run {
                    // REPEAT_ONE returns the same index; restarting it is correct.
                    if (repeatMode == Player.REPEAT_MODE_ONE) transitionTo(index, userSkip = false)
                    return
                }
            transitionTo(next, userSkip = false)
            return
        }

        val duration = active.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val position = active.currentPosition
        val remaining = duration - position

        val next = nextIndex(forward = true, userInitiated = false) ?: return
        val nextTrack = queue[next]
        val currentTrack = queue.getOrNullSafe(index)

        // Never crossfade album-continuous or live tracks (§6.2), and never fade a
        // short incoming track that would end before the ramp finishes.
        val mayCrossfade = currentTrack?.gaplessNext != true &&
            !nextTrack.gaplessNext &&
            CrossfadeController.canCrossfade(
                currentDurationMs = duration,
                nextDurationMs = nextTrack.durationMs,
                crossfadeMs = crossfadeMs,
            )

        if (!mayCrossfade) {
            if (remaining <= 0L) transitionTo(next, userSkip = false)
            return
        }

        // 1. Prefetch at duration − max(30 s, crossfade).
        val lead = maxOf(PREFETCH_LEAD_MS, crossfadeMs)
        if (remaining <= lead && nextPreparedIndex != next) {
            prepareOn(idle, next, playWhenReady = false)
            nextPreparedIndex = next
        }

        // 2. Start the equal-power ramp at duration − crossfade.
        if (remaining <= crossfadeMs && crossfadeJob?.isActive != true) {
            if (nextPreparedIndex != next) {
                prepareOn(idle, next, playWhenReady = false)
                nextPreparedIndex = next
            }
            val job = crossfade.launch(scope, active, idle, crossfadeMs, volume)
            crossfadeJob = job
            awaitCrossfadeThenSwap(job, next)
        }
    }

    private fun prepareOn(player: ExoPlayer, target: Int, playWhenReady: Boolean) {
        val track = queue.getOrNullSafe(target) ?: return
        // Set the incoming track's gain before its first sample reaches the sink, so a
        // crossfade never has a level jump mid-ramp (§6.4).
        val normalizer = if (player === primary) primaryNormalizer else secondaryNormalizer
        normalizationTargetLufs?.let {
            normalizer.setGain(track.loudnessLufs, track.truePeakDb, it)
        } ?: normalizer.bypass()
        player.setMediaItem(track.mediaItem)
        player.volume = if (playWhenReady) volume else 0f
        player.prepare()
        if (playWhenReady) player.play()
    }

    private fun swap() {
        val previousActive = active
        active = idle
        idle = previousActive
        idle.pause()
        idle.volume = 0f
        idle.clearMediaItems()
        active.volume = volume
    }

    private fun cancelCrossfade() {
        val job = crossfadeJob
        crossfadeJob = null
        nextPreparedIndex = -1
        if (job?.isActive == true) {
            crossfade.abort(active, idle)
            job.cancel()
        }
        // `abort()` zeroes the outgoing player's volume. Every caller of this method
        // (seek / skip / stop) continues to use `active` afterwards, so restoring the
        // user's volume here is what stops a seek during a crossfade from muting the
        // rest of the session.
        active.volume = volume
        idle.volume = 0f
    }

    /**
     * Awaits [job] and promotes the incoming player, but only if the ramp actually
     * completed. `Job.join()` returns normally for an already-cancelled job, so without
     * this guard a seek during a crossfade still advanced the canonical index and
     * promoted the wrong player.
     */
    private fun awaitCrossfadeThenSwap(job: Job, next: Int) {
        scope.launch {
            job.join()
            if (job.isCancelled) return@launch
            index = next
            swap()
            nextPreparedIndex = -1
            applyNormalization(index)
            // Pre-roll the loop for a continuous blend on the next boundary.
            startMonitor()
            publish()
        }
    }

    // -----------------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------------

    fun setShuffleModeEnabled(enabled: Boolean) {
        if (shuffleModeEnabled == enabled) return
        shuffleModeEnabled = enabled
        // In Gapless mode the player holds the real playlist, so its own shuffle would
        // permute player indices while the canonical queue stayed linear — the index
        // mapping in onPositionDiscontinuity would then report the wrong track as
        // playing. The engine owns the order instead and never delegates.
        shuffleOrder = if (enabled) {
            val shuffled = queue.indices.shuffled()
            // Keep the current track first so shuffle does not interrupt playback.
            (listOf(index) + shuffled.filter { it != index })
        } else {
            emptyList()
        }
        publish()
    }

    /** Canonical index that follows [current], honouring shuffle order. */
    private fun advanceShuffle(step: Int): Int? {
        if (shuffleOrder.isEmpty()) return null
        val pos = shuffleOrder.indexOf(index)
        if (pos < 0) return null
        val nextPos = pos + step
        return when {
            nextPos in shuffleOrder.indices -> shuffleOrder[nextPos]
            repeatMode == Player.REPEAT_MODE_ALL -> shuffleOrder.first()
            else -> null
        }
    }

    fun setRepeatMode(mode: Int) {
        repeatMode = mode
        if (_snapshot.value.mode == PlaybackMode.Gapless) active.repeatMode = mode
        publish()
    }

    /**
     * Next/previous canonical index.
     *
     * Skips candidates already known to be unplayable: the error handler records them,
     * but nothing consulted the set, so a corrupt file was retried on every pass and
     * under REPEAT_ALL forever.
     */
    private fun nextIndex(forward: Boolean, userInitiated: Boolean): Int? {
        if (queue.isEmpty()) return null

        // Shuffle order is owned by the engine, so both modes advance identically.
        if (shuffleOrder.isNotEmpty()) {
            return advanceShuffle(if (forward) 1 else -1)?.takeUnless { isUnplayable(it) }
        }

        val current = index
        val candidate = if (forward) current + 1 else current - 1
        return when {
            candidate in queue.indices -> candidate.takeUnless { isUnplayable(it) }
            repeatMode == Player.REPEAT_MODE_ALL ->
                if (forward) 0 else queue.lastIndex
            repeatMode == Player.REPEAT_MODE_ONE && !userInitiated -> current
            else -> null
        }
    }

    private fun previousIndex(): Int? = nextIndex(forward = false, userInitiated = true)

    private fun isUnplayable(candidate: Int): Boolean =
        queue.getOrNullSafe(candidate)?.mediaItem?.mediaId in unplayable

    // -----------------------------------------------------------------------
    // Normalization
    // -----------------------------------------------------------------------

    private fun applyNormalization(target: Int) {
        val track = queue.getOrNullSafe(target) ?: return
        val targetLufs = normalizationTargetLufs
        if (targetLufs == null) {
            activeNormalizer().bypass()
        } else {
            activeNormalizer().setGain(track.loudnessLufs, track.truePeakDb, targetLufs)
        }
        // The idle player gets its gain set when it is prepared, so a crossfade has
        // each track at its own level from the first sample.
    }

    private fun activeNormalizer(): NormalizationProcessor =
        if (active === primary) primaryNormalizer else secondaryNormalizer

    // -----------------------------------------------------------------------
    // Snapshot
    // -----------------------------------------------------------------------

    private fun listenerFor(owner: ExoPlayer) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (owner === active) publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (owner === active) publish()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (_snapshot.value.mode == PlaybackMode.Gapless) {
                val canonical = gaplessBaseIndex + newPosition.mediaItemIndex
                if (canonical in queue.indices) {
                    index = canonical
                    applyNormalization(index)
                }
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Mutating a player from inside its own event dispatch is not allowed, so
            // post the transition. The listener is bound to one specific player: a
            // prefetch failure on the idle player must not blacklist the track that is
            // currently playing.
            scope.launch {
                if (owner !== active) {
                    owner.currentMediaItem?.mediaId?.let { unplayable += it }
                    return@launch
                }

                // A single bad file must never halt the queue (§11 row 15).
                queue.getOrNullSafe(index)?.mediaItem?.mediaId?.let { unplayable += it }
                lastError = error
                publish()
                val next = nextIndex(forward = true, userInitiated = true)
                if (next != null) transitionTo(next, userSkip = false) else pause()
            }
        }
    }

    private val primaryListener = listenerFor(primary)
    private val secondaryListener = listenerFor(secondary)

    /** Held until explicitly cleared, so the UI can actually show it. */
    @Volatile
    private var lastError: PlaybackException? = null

    fun clearError() {
        lastError = null
        publish()
    }

    private val unplayable = mutableSetOf<String>()

    fun unplayableMediaIds(): Set<String> = unplayable.toSet()

    /**
 * Invoked by the service when a session custom command arrives, so the mutation goes
 * through the engine rather than being acknowledged and dropped.
 */
fun publishCommandResult(customAction: String) {
    when (customAction) {
        NoizCommands.ACTION_SHUFFLE -> setShuffleModeEnabled(!shuffleModeEnabled)
        NoizCommands.ACTION_REPEAT -> setRepeatMode(nextRepeatMode())
    }
}

private fun nextRepeatMode(): Int = when (repeatMode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

/** Cycles Off → All → One → Off, used by the notification and the Now Playing screen. */
fun cycleRepeatMode() = setRepeatMode(nextRepeatMode())

private fun publish() {
        val duration = when (val d = active.duration) {
            C.TIME_UNSET -> queue.getOrNullSafe(index)?.durationMs ?: 0L
            else -> d
        }
        _snapshot.value = EngineSnapshot(
            queue = queue,
            currentIndex = index,
            positionMs = active.currentPosition.coerceAtLeast(0L),
            bufferedPositionMs = active.bufferedPosition.coerceAtLeast(0L),
            durationMs = duration,
            isPlaying = active.isPlaying,
            // `isPlaying` is false while buffering; `playWhenReady` stays true. Reporting
            // one as the other made the notification and Android Auto flash "paused" on
            // every rebuffer and drop the foreground service.
            playWhenReady = active.playWhenReady,
            playbackState = active.playbackState,
            mode = if (crossfadeMs == 0L) PlaybackMode.Gapless else PlaybackMode.Dual,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            error = lastError,
        )
    }

    init {
        primary.addListener(primaryListener)
        secondary.addListener(secondaryListener)
        primary.repeatMode = Player.REPEAT_MODE_OFF
        primary.volume = volume
        secondary.volume = 0f
    }

    fun release() {
        cancelCrossfade()
        stopMonitor()
        primary.removeListener(primaryListener)
        secondary.removeListener(secondaryListener)
        primary.release()
        secondary.release()
    }

    companion object {
        /** §3.4: the next item is prefetched at T−30 s. */
        const val PREFETCH_LEAD_MS = 30_000L

        /** Monitor cadence. Cheap: two property reads and a comparison. */
        const val MONITOR_INTERVAL_MS = 200L
    }
}

private fun <T> List<T>.getOrNullSafe(index: Int): T? =
    if (index in indices) this[index] else null
