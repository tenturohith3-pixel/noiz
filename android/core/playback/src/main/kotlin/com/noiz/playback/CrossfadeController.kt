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

    /**
     * Set while a ramp is in flight so [abort] can reach the running [run].
     *
     * Held in an AtomicReference rather than a single `@Volatile Boolean`: two ramps
     * could overlap (a skip blend launched while the monitor's ramp was active), and one
     * shared flag meant the second `run()` reset the first's abort request — leaving a
     * player stuck at volume 0.
     */
    private val abortRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Ramps [outPlayer] down to silence while [inPlayer] fades up, over [durationMs].
     * Suspending: cancelling the calling job aborts the ramp via `finally`.
     *
     * @param targetVolume the user's output volume. The ramp scales both players by it
     *   rather than ending at a hardcoded 1f, which ignored the volume slider entirely.
     */
    suspend fun run(
        outPlayer: ExoPlayer,
        inPlayer: ExoPlayer,
        durationMs: Long,
        targetVolume: Float = 1f,
    ) {
        abortRequested.set(false)
        val duration = durationMs.coerceAtLeast(MIN_RAMP_MS)
        val peak = targetVolume.coerceIn(0f, 1f)

        inPlayer.volume = 0f
        inPlayer.play()

        val start = SystemClock.elapsedRealtime()
        try {
            while (coroutineContext.isActive && !abortRequested.get()) {
                val t = ((SystemClock.elapsedRealtime() - start) / duration.toFloat())
                    .coerceIn(0f, 1f)
                outPlayer.volume = (cos(t * (PI / 2)) * peak).toFloat()
                inPlayer.volume = (sin(t * (PI / 2)) * peak).toFloat()
                if (t >= 1f) break
                delay(FRAME_MS) // main-thread cadence, matches the display refresh
            }
        } finally {
            if (abortRequested.get()) {
                abort(outPlayer, inPlayer, peak)
            } else {
                outPlayer.volume = 0f
                inPlayer.volume = peak
                outPlayer.pause()
                outPlayer.clearMediaItems()
            }
        }
    }

    /**
     * Called on audio-focus loss or an explicit seek while a ramp is in flight.
     * Snaps volumes rather than leaving a half-faded pair (§11 row 4).
     */
    @JvmOverloads
    fun abort(outPlayer: ExoPlayer, inPlayer: ExoPlayer, targetVolume: Float = 1f) {
        abortRequested.set(true)
        outPlayer.volume = 0f
        inPlayer.volume = targetVolume.coerceIn(0f, 1f)
    }

    /** Fire-and-forget variant used by the prefetch monitor. */
    @JvmOverloads
    fun launch(
        scope: CoroutineScope,
        outPlayer: ExoPlayer,
        inPlayer: ExoPlayer,
        durationMs: Long,
        targetVolume: Float = 1f,
    ): Job = scope.launch { run(outPlayer, inPlayer, durationMs, targetVolume) }

    companion object {
        /** Full slider range (§6.1 FR-PB-02). */
        const val MAX_CROSSFADE_MS = 12_000L

        /**
         * A track only crossfades when it is long enough to host the whole ramp *and*
         * the incoming track outlives it. Checking only the outgoing track let an 8 s
         * fade overrun a 5 s next track, whose `finally` then paused mid-playback.
         */
        fun canCrossfade(
            currentDurationMs: Long,
            crossfadeMs: Long,
            nextDurationMs: Long = Long.MAX_VALUE,
        ): Boolean =
            crossfadeMs > 0 &&
                currentDurationMs >= 2 * crossfadeMs &&
                nextDurationMs >= crossfadeMs

        /** 300 ms equal-power blend for next/previous presses and gapless albums. */
        const val SKIP_BLEND_MS = 300L

        private const val MIN_RAMP_MS = 80L
        private const val FRAME_MS = 16L
    }
}
