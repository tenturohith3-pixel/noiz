package com.noiz.playback

import android.os.Bundle
import androidx.media3.common.MediaItem

/**
 * Keys carried in `MediaItem.requestMetadata.extras`.
 *
 * They survive MediaSession serialization, so process-death restore and Android Auto
 * controllers replay with the same loudness/gapless semantics as the in-app queue.
 */
object NoizMediaExtras {
    const val RENDITION = "noiz.rendition"
    const val LOUDNESS_LUFS = "noiz.loudnessLufs"
    const val TRUE_PEAK_DB = "noiz.truePeakDb"
    const val GAPLESS_NEXT = "noiz.gaplessNext"
    const val DURATION_MS = "noiz.durationMs"
}

/** Rebuilds the engine's per-item metadata from a [MediaItem]. */
fun MediaItem.toEngineTrack(): EngineTrack {
    val extras = requestMetadata?.extras
    return EngineTrack(
        mediaItem = this,
        renditionId = extras?.getString(NoizMediaExtras.RENDITION) ?: EngineTrack.DEFAULT_RENDITION,
        loudnessLufs = extras?.getFloatOrNull(NoizMediaExtras.LOUDNESS_LUFS),
        truePeakDb = extras?.getFloatOrNull(NoizMediaExtras.TRUE_PEAK_DB),
        gaplessNext = extras?.getBoolean(NoizMediaExtras.GAPLESS_NEXT) ?: false,
        durationMs = extras?.getLong(NoizMediaExtras.DURATION_MS) ?: 0L,
    )
}

private fun Bundle.getFloatOrNull(key: String): Float? =
    if (containsKey(key)) getFloat(key, Float.NaN).takeIf { !it.isNaN() } else null
