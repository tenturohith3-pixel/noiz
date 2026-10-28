package com.noiz.data

import com.noiz.database.AlbumEntity
import com.noiz.database.ArtistEntity
import com.noiz.database.PlaylistEntity
import com.noiz.database.TrackEntity
import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.Playlist
import com.noiz.model.Track
import com.noiz.network.AlbumDto
import com.noiz.network.ArtistDto
import com.noiz.network.PlaylistDto
import com.noiz.network.TrackDto

/**
 * DTO → Room mappings.
 *
 * The normalization rule matters more than it looks: search must return the same
 * results offline and online (§5.7), so the client stores a display title *and* uses
 * the server's normalized form when present. Where the server has not sent one we fall
 * back to a Unicode-aware fold, and FTS's `remove_diacritics=2` covers the rest.
 */
fun normalizeForIndex(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .trim()

fun TrackDto.toEntity(): TrackEntity = TrackEntity(
    id = id,
    title = title,
    albumId = album?.id,
    artistName = artists.joinToString(", ") { it.name },
    discNo = discNumber,
    trackNo = trackNumber,
    durationMs = durationMs,
    explicit = explicit,
    isrc = isrc,
    loudnessLufs = loudness.trackLufs,
    truePeakDb = loudness.trackPeakDb,
    gaplessNext = gaplessNext,
    hasLyrics = hasLyrics,
    hasCanvas = hasCanvas,
    artworkHash = artwork?.hash,
    addedAt = addedAt,
    updatedAt = updatedAt,
)

fun AlbumDto.toEntity(): AlbumEntity = AlbumEntity(
    id = id,
    title = title,
    artistName = artistName,
    albumType = albumType,
    releaseYear = releaseYear,
    trackCount = trackCount,
    artworkHash = artwork?.hash,
    dominantHex = dominantHex,
    vibrantHex = vibrantHex,
    mutedHex = mutedHex,
    updatedAt = updatedAt,
)

fun ArtistDto.toEntity(): ArtistEntity = ArtistEntity(
    id = id,
    name = name,
    sortName = sortName.ifEmpty { normalizeForIndex(name) },
    imageHash = imageHash,
    updatedAt = updatedAt,
)

fun PlaylistDto.toEntity(): PlaylistEntity = PlaylistEntity(
    id = id,
    ownerId = ownerId,
    name = name,
    description = description,
    isCollaborative = isCollaborative,
    artworkHash = artwork?.hash,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------------------------
// Room → domain, for the UI
// ------------------------------------------------------------------------------

fun TrackEntity.toDomain(
    renditions: List<com.noiz.model.Rendition> = emptyList(),
    serverUrl: String? = null,
): Track = Track(
    id = id,
    title = title,
    albumId = albumId,
    albumTitle = null,
    artistName = artistName,
    discNo = discNo,
    trackNo = trackNo,
    durationMs = durationMs,
    explicit = explicit,
    isrc = isrc,
    loudnessLufs = loudnessLufs,
    truePeakDb = truePeakDb,
    gaplessNext = gaplessNext,
    hasLyrics = hasLyrics,
    hasCanvas = hasCanvas,
    artwork = com.noiz.model.Artwork(hash = artworkHash, url = artworkUrl(artworkHash, serverUrl)),
    renditions = renditions,
    addedAt = addedAt,
    updatedAt = updatedAt,
)

fun AlbumEntity.toDomain(serverUrl: String? = null): Album = Album(
    id = id,
    title = title,
    artistName = artistName,
    albumType = albumType,
    releaseYear = releaseYear,
    trackCount = trackCount,
    artwork = com.noiz.model.Artwork(hash = artworkHash, url = artworkUrl(artworkHash, serverUrl)),
    dominantHex = dominantHex,
    vibrantHex = vibrantHex,
    mutedHex = mutedHex,
    updatedAt = updatedAt,
)

fun ArtistEntity.toDomain(): Artist = Artist(
    id = id,
    name = name,
    sortName = sortName,
    imageHash = imageHash,
    updatedAt = updatedAt,
)

fun PlaylistEntity.toDomain(trackCount: Int = 0, serverUrl: String? = null): Playlist = Playlist(
    id = id,
    ownerId = ownerId,
    name = name,
    description = description,
    isCollaborative = isCollaborative,
    artwork = com.noiz.model.Artwork(hash = artworkHash, url = artworkUrl(artworkHash, serverUrl)),
    trackCount = trackCount,
    updatedAt = updatedAt,
)

/**
 * Artwork is content-addressed and cacheable-immutable (§5.3).
 *
 * The server serves it at `/artwork/{hash}`. Callers that render it (Coil, the
 * notification) need an absolute URL, so pass the signed-in server's base URL.
 */
fun artworkUrl(hash: String?, serverUrl: String? = null): String? {
    val path = hash?.let { "/artwork/${it.removePrefix("sha256:")}" } ?: return null
    return if (serverUrl.isNullOrBlank()) path else serverUrl.trimEnd('/') + path
}
