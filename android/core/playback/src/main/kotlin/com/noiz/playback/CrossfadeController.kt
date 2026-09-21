package com.noiz.playback

import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power crossfade (§6.2).
 *
 * out(t) = cos(t·π/2), in(t) = sin(t·π/2) keeps constant perceived loudness, because
 * cos²+sin² = 1. Both players share one audio session, so a single EQ/limiter handles
 * the mixed output.
 *
 * A user-issued skip uses [SKIP_BLEND_MS] instead of the full slider value and never
 * overlaps album-continuous tracks.
 */
class CrossfadeController {

    @Volatile
    private var abortRequested = false

    /**
     * Ramps [outPlayer] down to silence while [inPlayer] fades up, over [durationMs].
     * Suspending: cancelling the calling job aborts the ramp via `finally`.
     */
    suspend fun run(
        outPlayer: ExoPlayer,
        inPlayer: ExoPlayer,
        durationMs: Long,
    ) {
        abortRequested = false
        val duration = durationMs.coerceAtLeast(MIN_RAMP_MS)

        inPlayer.volume = 0f
        inPlayer.play()

        val start = SystemClock.elapsedRealtime()
        try {
            while (coroutineContext.isActive && !abortRequested) {
                val t = ((SystemClock.elapsedRealtime() - start) / duration.toFloat())
                    .coerceIn(0f, 1f)
                outPlayer.volume = cos(t * (PI / 2)).toFloat()
                inPlayer.volume = sin(t * (PI / 2)).toFloat()
                if (t >= 1f) break
                delay(FRAME_MS) // main-thread cadence, matches the display refresh
            }
        } finally {
            if (abortRequested) {
                abort(outPlayer, inPlayer)
            } else {
                outPlayer.volume = 0f
                inPlayer.volume = 1f
                outPlayer.pause()
                outPlayer.clearMediaItems()
            }
        }
    }

    /**
     * Called on audio-focus loss or an explicit seek while a ramp is in flight.
     * Snaps volumes rather than leaving a half-faded pair (§11 row 4).
     */
    fun abort(outPlayer: ExoPlayer, inPlayer: ExoPlayer) {
        abortRequested = true
        outPlayer.volume = 0f
        inPlayer.volume = 1f
    }

    /** Fire-and-forget variant used by the prefetch monitor. */
    fun launch(scope: CoroutineScope, outPlayer: ExoPlayer, inPlayer: ExoPlayer, durationMs: Long): Job =
        scope.launch { run(outPlayer, inPlayer, durationMs) }

    companion object {
        /** Full slider range (§6.1 FR-PB-02). */
        const val MAX_CROSSFADE_MS = 12_000L

        /** Tracks shorter than 2× the fade never crossfade; they blend instead. */
        fun canCrossfade(trackDurationMs: Long, crossfadeMs: Long): Boolean =
            crossfadeMs > 0 && trackDurationMs >= 2 * crossfadeMs

        /** 300 ms equal-power blend for next/previous presses and gapless albums. */
        const val SKIP_BLEND_MS = 300L

        private const val MIN_RAMP_MS = 80L
        private const val FRAME_MS = 16L
    }
}
