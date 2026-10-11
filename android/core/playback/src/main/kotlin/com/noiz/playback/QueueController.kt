package com.noiz.playback

import androidx.media3.common.Player

/**
 * Queue manipulation surface used by `:core:data` and the feature screens.
 *
 * `DualPlayerEngine` deliberately exposes only *what* to play; the *ordering* rules
 * (fractional keys, canonical index mapping, outbox ops) live above it so the engine
 * never has to know about Room or sync.
 */
interface QueueController {

    /** Current queue in canonical order. */
    fun snapshot(): EngineSnapshot

    /** Replace the queue and start at [startIndex]. */
    fun setQueue(tracks: List<EngineTrack>, startIndex: Int = 0, startPositionMs: Long = 0L)

    fun playAt(index: Int)
    fun removeFrom(index: Int)
    fun move(from: Int, to: Int)
    fun clear()
}

/**
 * [QueueController] backed by [DualPlayerEngine].
 *
 * In gapless mode the engine loads the remaining queue into one ExoPlayer, so the
 * canonical index must be mapped back to the player's media index; this adapter is the
 * only place that translation happens.
 */
class EngineQueueController(
    private val engine: DualPlayerEngine,
) : QueueController {

    override fun snapshot(): EngineSnapshot = engine.snapshot.value

    override fun setQueue(tracks: List<EngineTrack>, startIndex: Int, startPositionMs: Long) {
        engine.setQueue(tracks, startIndex, startPositionMs)
    }

    override fun playAt(index: Int) = engine.seekToIndex(index, userSkip = true)

    override fun removeFrom(index: Int) {
        val current = engine.snapshot.value
        if (index !in current.queue.indices) return

        val remaining = current.queue.filterIndexed { i, _ -> i != index }
        val newIndex = when {
            index < current.currentIndex -> current.currentIndex - 1
            index == current.currentIndex -> current.currentIndex.coerceAtMost(remaining.lastIndex)
            else -> current.currentIndex
        }.coerceAtLeast(0)

        val wasPlaying = current.isPlaying
        engine.setQueue(remaining, newIndex, startPositionMs = if (index == current.currentIndex) 0L else current.positionMs)
        if (wasPlaying) engine.play()
    }

    override fun move(from: Int, to: Int) {
        val current = engine.snapshot.value
        if (from !in current.queue.indices || to !in current.queue.indices) return

        val mutable = current.queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)

        // Locate the *playing track* by identity after the reorder. Comparing the new
        // index against the old one was meaningless: the playing item is preserved by
        // construction, so its position within the track must always carry over —
        // otherwise reordering the queue restarts the current track from 0.
        val playingMediaId = current.queue.getOrNull(current.currentIndex)?.mediaItem?.mediaId
        val newIndex = mutable.indexOfFirst { it.mediaItem.mediaId == playingMediaId }

        val wasPlaying = current.isPlaying || current.playWhenReady

        engine.setQueue(
            mutable,
            newIndex.coerceAtLeast(0),
            startPositionMs = current.positionMs,
        )
        // reloadForMode now preserves playWhenReady, but be explicit: a reorder is not a
        // reason to stop the music.
        if (wasPlaying) engine.play()
    }

    override fun clear() {
        engine.setQueue(emptyList(), 0, 0L)
    }
}
