package com.noiz.model

/**
 * Catalog domain models (§5.1, §6.2, §9).
 *
 * These are the *client's* view of the catalog: immutable, UI-ready, and deliberately
 * free of both Room annotations and kotlinx-serialization so that neither persistence
 * nor wire format can leak into the presentation layer. Mapping happens at the edges —
 * `:core:network` maps DTOs in, `:core:database` maps entities in.
 */

/** Delivery profiles from §6.2. The wire value is the lowercase name. */
enum class QualityProfile(val wire: String, val label: String) {
    ORIGINAL("original", "Original lossless"),
    HIGH("high", "High quality"),
    SAVER("saver", "Data saver"),
    OFFLINE_LOSSLESS("offline", "Offline lossless"),
    ;

    companion object {
        /**
         * Unknown/absent wire values fall back to [HIGH], but a server that adds a new
         * profile must not be silently mislabelled — `null` lets the caller report it.
         */
        fun fromWire(value: String?): QualityProfile =
            entries.firstOrNull { it.wire == value } ?: HIGH

        fun fromWireOrNull(value: String?): QualityProfile? =
            entries.firstOrNull { it.wire == value }
    }
}

/** UI-facing repeat mode; the engine speaks Media3's `Player.REPEAT_MODE_*`. */
enum class RepeatMode { OFF, ONE, ALL }

/**
 * One server-side rendition of a track. `id == "orig"` is the untouched source file;
 * everything else is derived (§6.2 "the server must not transcode unnecessarily").
 */
data class Rendition(
    val id: String,
    val codec: String,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val sizeBytes: Long,
    val sha256: String,
) {
    val isOriginal: Boolean get() = id == ORIGINAL_ID

    /**
     * True only for genuinely lossless codecs.
     *
     * This used to return `isOriginal || bitDepth != null`, which labelled an uploaded
     * MP3 or AAC source as lossless (PRD §5.2 supports those) and any lossy rendition
     * that happened to carry a `bitDepth` of 16. §2.2 forbids exactly that.
     */
    val isLossless: Boolean
        get() = codec.lowercase() in LOSSLESS_CODECS

    companion object {
        const val ORIGINAL_ID = "orig"

        /** Codecs that decode to the exact PCM of the source (§6.2). */
        val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv")
    }
}

/** Artwork is content-addressed and cacheable-immutable (§5.3). */
data class Artwork(
    val hash: String? = null,
    /** `null` when the server has no artwork; the UI falls back to a gradient. */
    val url: String? = null,
) {
    val isEmpty: Boolean get() = hash == null

    companion object {
        val None = Artwork()
    }
}

data class Track(
    val id: String,
    val title: String,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val artistName: String = "",
    val discNo: Int = 1,
    val trackNo: Int = 0,
    val durationMs: Long = 0L,
    val explicit: Boolean = false,
    val isrc: String? = null,
    /** R128 loudness; `null` means the sink must not apply gain. */
    val loudnessLufs: Float? = null,
    val truePeakDb: Float? = null,
    /** Continuous with the next track: never crossfaded (§6.2). */
    val gaplessNext: Boolean = false,
    val hasLyrics: Boolean = false,
    val hasCanvas: Boolean = false,
    val artwork: Artwork = Artwork.None,
    val renditions: List<Rendition> = emptyList(),
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /**
     * Best rendition for [profile].
     *
     * A lossless profile only ever returns a genuinely lossless rendition. Falling back
     * to `firstOrNull()` here is what allowed "offline lossless" to resolve to a 64 kbps
     * Opus transcode, which §2.2 forbids outright.
     *
     * Renditions with an unknown bitrate sort last for HIGH and first for SAVER only
     * when nothing better exists, so a missing bitrate does not silently win either
     * selection.
     */
    fun renditionFor(profile: QualityProfile): Rendition? = when (profile) {
        QualityProfile.ORIGINAL, QualityProfile.OFFLINE_LOSSLESS ->
            renditions.firstOrNull { it.isOriginal && it.isLossless }
                ?: renditions.firstOrNull { it.isLossless }
                ?: renditions.firstOrNull { it.isOriginal }

        QualityProfile.HIGH ->
            renditions.filterNot { it.isOriginal }
                .maxWithOrNull(compareByDescending<Rendition> { it.bitrateKbps != null }
                    .thenByDescending { it.bitrateKbps ?: 0 })
                ?: renditions.firstOrNull { it.isOriginal }

        QualityProfile.SAVER ->
            renditions.filterNot { it.isOriginal }
                .minWithOrNull(compareByDescending<Rendition> { it.bitrateKbps != null }
                    .thenBy { it.bitrateKbps ?: Int.MAX_VALUE })
                ?: renditions.firstOrNull { it.isOriginal }
    }

    val primaryArtist: String get() = artistName.ifEmpty { "Unknown artist" }
}

data class Album(
    val id: String,
    val title: String,
    val artistName: String = "",
    val albumType: String? = null,
    val releaseYear: Int? = null,
    val trackCount: Int = 0,
    val artwork: Artwork = Artwork.None,
    val dominantHex: String? = null,
    val vibrantHex: String? = null,
    val mutedHex: String? = null,
    val updatedAt: Long = 0L,
)

data class Artist(
    val id: String,
    val name: String,
    val sortName: String = name,
    val imageHash: String? = null,
    val updatedAt: Long = 0L,
)

data class Playlist(
    val id: String,
    val ownerId: String,
    val name: String,
    val description: String? = null,
    val isCollaborative: Boolean = false,
    val artwork: Artwork = Artwork.None,
    val trackCount: Int = 0,
    val updatedAt: Long = 0L,
)

/** A track plus the rendition chosen for this queue entry. */
data class QueueItem(
    val track: Track,
    val renditionId: String = Rendition.ORIGINAL_ID,
)

/** One search hit; [kind] is `track` | `album` | `artist` | `playlist`. */
data class SearchResult(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkHash: String? = null,
)

/** Synchronized or plain lyrics (§6.4). */
data class Lyrics(
    val trackId: String,
    val lines: List<LyricLine>,
    val synced: Boolean,
    val provider: String? = null,
    val attribution: String? = null,
) {
    companion object {
        val None = Lyrics("", emptyList(), synced = false)
    }
}

data class LyricLine(
    /** `null` for unsynchronized text. */
    val timeMs: Long?,
    val text: String,
)

/** Signed stream URL returned by `/tracks/{id}/stream-manifest` (§10). */
data class StreamUrl(
    val renditionId: String,
    val url: String,
    val expiresAtEpochMs: Long,
) {
    fun isExpiringWithin(windowMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs - nowMs <= windowMs
}
