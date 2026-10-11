package com.noiz.network

import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.Artwork
import com.noiz.model.LyricLine
import com.noiz.model.Lyrics
import com.noiz.model.Playlist
import com.noiz.model.QualityProfile
import com.noiz.model.Rendition
import com.noiz.model.SearchResult
import com.noiz.model.Track
import com.noiz.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for `/api/v1` (§10).
 *
 * Rules that keep these honest:
 *  - every field the server may add later is optional or defaulted (`ignoreUnknownKeys`
 *    is on in [NoizJson]), so an older client survives a newer server within v1;
 *  - snake_case on the wire, camelCase in Kotlin, mapped with `@SerialName`;
 *  - *nothing* here reaches the UI: [mappers] converts to `:core:model` types, which is
 *    the only place the rest of the app reads from.
 */

@Serializable
data class DeviceInfoDto(
    val id: String,
    val name: String,
    val platform: String,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
    val device: DeviceInfoDto,
)

@Serializable
data class RefreshRequestDto(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class UserPayloadDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String = "",
    val role: String = "listener",
    @SerialName("can_upload") val canUpload: Boolean = false,
    @SerialName("can_download") val canDownload: Boolean = true,
) {
    fun toModel(): User = User(
        id = id,
        username = username,
        displayName = displayName.ifEmpty { username },
        role = role,
        canUpload = canUpload,
        canDownload = canDownload,
    )
}

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Int = 3600,
    val user: UserPayloadDto,
)

// -------------------------------------------------------------------------------
// Catalog
// -------------------------------------------------------------------------------

@Serializable
data class ArtworkDto(
    val hash: String? = null,
    val url: String? = null,
) {
    fun toModel(): Artwork = Artwork(hash = hash, url = url)
}

@Serializable
data class RenditionDto(
    val id: String,
    val codec: String,
    val container: String? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
) {
    fun toModel(): Rendition = Rendition(
        id = id,
        codec = codec,
        container = container,
        bitrateKbps = bitrateKbps,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )
}

@Serializable
data class ArtistSummaryDto(val id: String, val name: String)

@Serializable
data class AlbumSummaryDto(val id: String, val title: String)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val album: AlbumSummaryDto? = null,
    val artists: List<ArtistSummaryDto> = emptyList(),
    @SerialName("disc_number") val discNumber: Int = 1,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val explicit: Boolean = false,
    val isrc: String? = null,
    @SerialName("loudness_lufs") val loudnessLufs: Float? = null,
    @SerialName("true_peak_db") val truePeakDb: Float? = null,
    @SerialName("gapless_next") val gaplessNext: Boolean = false,
    @SerialName("has_lyrics") val hasLyrics: Boolean = false,
    @SerialName("has_canvas") val hasCanvas: Boolean = false,
    val artwork: ArtworkDto? = null,
    val renditions: List<RenditionDto> = emptyList(),
    @SerialName("added_at") val addedAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toModel(): Track = Track(
        id = id,
        title = title,
        albumId = album?.id,
        albumTitle = album?.title,
        artistName = artists.joinToString(", ") { it.name },
        discNo = discNumber,
        trackNo = trackNumber,
        durationMs = durationMs,
        explicit = explicit,
        isrc = isrc,
        loudnessLufs = loudnessLufs,
        truePeakDb = truePeakDb,
        gaplessNext = gaplessNext,
        hasLyrics = hasLyrics,
        hasCanvas = hasCanvas,
        artwork = artwork?.toModel() ?: Artwork.None,
        renditions = renditions.map { it.toModel() },
        addedAt = addedAt,
        updatedAt = updatedAt,
    )
}

@Serializable
data class AlbumDto(
    val id: String,
    val title: String,
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_type") val albumType: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    val artwork: ArtworkDto? = null,
    @SerialName("dominant_hex") val dominantHex: String? = null,
    @SerialName("vibrant_hex") val vibrantHex: String? = null,
    @SerialName("muted_hex") val mutedHex: String? = null,
    val tracks: List<TrackDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toModel(): Album = Album(
        id = id,
        title = title,
        artistName = artistName,
        albumType = albumType,
        releaseYear = releaseYear,
        trackCount = trackCount.ifZero { tracks.size },
        artwork = artwork?.toModel() ?: Artwork.None,
        dominantHex = dominantHex,
        vibrantHex = vibrantHex,
        mutedHex = mutedHex,
        updatedAt = updatedAt,
    )

    private inline fun Int.ifZero(fallback: () -> Int): Int = if (this == 0) fallback() else this
}

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    @SerialName("sort_name") val sortName: String = "",
    @SerialName("image_hash") val imageHash: String? = null,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toModel(): Artist = Artist(
        id = id,
        name = name,
        sortName = sortName.ifEmpty { name },
        imageHash = imageHash,
        updatedAt = updatedAt,
    )
}

@Serializable
data class PlaylistDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String = "",
    val name: String,
    val description: String? = null,
    @SerialName("is_collaborative") val isCollaborative: Boolean = false,
    val artwork: ArtworkDto? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toModel(): Playlist = Playlist(
        id = id,
        ownerId = ownerId,
        name = name,
        description = description,
        isCollaborative = isCollaborative,
        artwork = artwork?.toModel() ?: Artwork.None,
        trackCount = trackCount,
        updatedAt = updatedAt,
    )
}

@Serializable
data class SearchResultDto(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("artwork_hash") val artworkHash: String? = null,
) {
    fun toModel(): SearchResult = SearchResult(
        kind = kind,
        id = id,
        title = title,
        subtitle = subtitle,
        artworkHash = artworkHash,
    )
}

@Serializable
data class SearchResponseDto(val results: List<SearchResultDto> = emptyList())

@Serializable
data class CatalogPageDto(
    val tracks: List<TrackDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

// -------------------------------------------------------------------------------
// Playback / streaming (§6.2, §9.4)
// -------------------------------------------------------------------------------

@Serializable
data class StreamManifestRenditionDto(
    val id: String,
    val codec: String,
    val container: String? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    @SerialName("bit_depth") val bitDepth: Int? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
    /** Relative URL including the query-string signature: `/stream/{t}/{r}?exp&sig`. */
    val url: String,
)

@Serializable
data class LoudnessDto(
    @SerialName("track_lufs") val trackLufs: Float? = null,
    @SerialName("track_peak_db") val trackPeakDb: Float? = null,
    @SerialName("album_lufs") val albumLufs: Float? = null,
    @SerialName("album_peak_db") val albumPeakDb: Float? = null,
)

@Serializable
data class StreamManifestDto(
    @SerialName("track_id") val trackId: String,
    val renditions: List<StreamManifestRenditionDto> = emptyList(),
    val loudness: LoudnessDto = LoudnessDto(),
    @SerialName("gapless_next") val gaplessNext: Boolean = false,
    /** Epoch seconds, as returned by the server. */
    @SerialName("expires_at") val expiresAt: Long = 0L,
)

@Serializable
data class HistoryEventDto(
    val id: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("played_ms") val playedMs: Int,
    @SerialName("ended_reason") val endedReason: String,
    val rendition: String? = null,
    val offline: Boolean = false,
    val private: Boolean = false,
)

// -------------------------------------------------------------------------------
// Lyrics (§6.4)
// -------------------------------------------------------------------------------

@Serializable
data class LyricLineDto(
    /** Epoch millis for synchronized lines; absent for plain text. */
    val timeMs: Long? = null,
    val text: String,
)

@Serializable
data class LyricsDto(
    @SerialName("track_id") val trackId: String = "",
    val lines: List<LyricLineDto> = emptyList(),
    val synced: Boolean = false,
    val provider: String? = null,
    val attribution: String? = null,
) {
    fun toModel(trackId: String): Lyrics = Lyrics(
        trackId = trackId.ifEmpty { this.trackId },
        lines = lines.map { LyricLine(timeMs = it.timeMs, text = it.text) },
        synced = synced,
        provider = provider,
        attribution = attribution,
    )
}

// -------------------------------------------------------------------------------
// Uploads (§6.5)
// -------------------------------------------------------------------------------

@Serializable
data class CreateUploadRequestDto(
    @SerialName("file_name") val fileName: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("part_size_bytes") val partSizeBytes: Long,
    val sha256: String? = null,
)

@Serializable
data class UploadSessionDto(
    val id: String,
    @SerialName("part_size_bytes") val partSizeBytes: Long,
    @SerialName("received_parts") val receivedParts: List<Int> = emptyList(),
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class CompleteUploadRequestDto(
    @SerialName("sha256") val sha256: String = "",
)

// -------------------------------------------------------------------------------
// Misc payloads
// -------------------------------------------------------------------------------

@Serializable
data class FavoriteRequestDto(val liked: Boolean = true)

@Serializable
data class PlaylistCreateRequestDto(
    val name: String,
    val description: String? = null,
)

@Serializable
data class MeResponseDto(
    val user: UserPayloadDto? = null,
    val capabilities: ServerCapabilitiesDto? = null,
    @SerialName("offline_encryption_policy") val offlineEncryptionPolicy: String = "optional",
)

@Serializable
data class ServerCapabilitiesDto(
    val streaming: Boolean = true,
    val downloads: Boolean = true,
    val uploads: Boolean = true,
    val lyrics: Boolean = false,
    val recommendations: Boolean = false,
    @SerialName("remote_playback") val remotePlayback: Boolean = false,
)

/** RFC 7807 problem document — every error path on the server uses it (§10). */
@Serializable
data class ProblemDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    @SerialName("instance") val instance: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    val code: String? = null,
)
