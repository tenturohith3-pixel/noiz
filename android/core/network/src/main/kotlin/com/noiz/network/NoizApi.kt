package com.noiz.network

import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.Lyrics
import com.noiz.model.Playlist
import com.noiz.model.SearchResult
import com.noiz.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * `/api/v1` surface (§10).
 *
 * Two conventions worth stating:
 *
 *  - **Base URL is injected.** The server address is user input (Connect screen), so
 *    every call uses an absolute `@Url` or a path relative to the OkHttp client that
 *    was built for that server. Retrofit's `baseUrl` is a placeholder for that reason.
 *  - **Auth is an interceptor concern.** No method here takes an access token except
 *    [streamManifestRaw], which is called by the playback loading thread through the
 *    dedicated signed-URL client.
 */
interface NoizApi {

    // ------------------------------------------------------------------ auth --

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): TokenResponseDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): TokenResponseDto

    @POST("api/v1/auth/logout")
    suspend fun logout()

    @GET("api/v1/me")
    suspend fun me(): UserPayloadDto

    // ---------------------------------------------------------------- catalog --

    @GET("api/v1/catalog/sync")
    suspend fun catalogSync(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 200,
    ): CatalogPageDto

    @GET("api/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int = 50,
    ): SearchResponseDto

    @GET("api/v1/tracks/{trackId}")
    suspend fun track(@Path("trackId") trackId: String): TrackDto

    @GET("api/v1/albums/{albumId}")
    suspend fun album(@Path("albumId") albumId: String): AlbumDto

    @GET("api/v1/artists/{artistId}")
    suspend fun artist(@Path("artistId") artistId: String): ArtistDto

    @GET("api/v1/artists/{artistId}/tracks")
    suspend fun artistTracks(@Path("artistId") artistId: String): List<TrackDto>

    @GET("api/v1/playlists")
    suspend fun playlists(): List<PlaylistDto>

    @GET("api/v1/playlists/{playlistId}")
    suspend fun playlist(@Path("playlistId") playlistId: String): PlaylistDto

    @GET("api/v1/playlists/{playlistId}/tracks")
    suspend fun playlistTracks(@Path("playlistId") playlistId: String): List<TrackDto>

    @POST("api/v1/playlists")
    suspend fun createPlaylist(@Body body: PlaylistCreateRequestDto): PlaylistDto

    @POST("api/v1/playlists/{playlistId}/tracks")
    suspend fun addToPlaylist(
        @Path("playlistId") playlistId: String,
        @Body body: PlaylistAddRequestDto,
    )

    @DELETE("api/v1/playlists/{playlistId}/tracks/{trackId}")
    suspend fun removeFromPlaylist(
        @Path("playlistId") playlistId: String,
        @Path("trackId") trackId: String,
    )

    @GET("api/v1/recommendations/home")
    suspend fun homeSections(): HomeSectionsDto

    // ------------------------------------------------------- favorites / history --

    @PUT("api/v1/favorites/{trackId}")
    suspend fun addFavorite(@Path("trackId") trackId: String, @Body body: FavoriteRequestDto = FavoriteRequestDto(true))

    @DELETE("api/v1/favorites/{trackId}")
    suspend fun removeFavorite(@Path("trackId") trackId: String)

    @GET("api/v1/favorites")
    suspend fun favorites(): List<String>

    @POST("api/v1/history/events")
    suspend fun pushHistory(@Body body: List<HistoryEventDto>)

    // --------------------------------------------------------------- playback --

    /**
     * Renditions + freshly signed URLs (§9.4). Called from ExoPlayer's loading thread,
     * so it is `suspend` and run through [await] rather than blocking a dispatcher.
     */
    @GET("api/v1/tracks/{trackId}/stream-manifest")
    suspend fun streamManifest(@Path("trackId") trackId: String): StreamManifestDto

    /**
     * Same endpoint as a streaming GET with an absolute URL — kept separate so the
     * signed-URL resolver can hand Retrofit a fully-formed URL including `?exp&sig`.
     */
    @Streaming
    @GET
    suspend fun streamManifestRaw(@Url url: String, @Header("Authorization") bearer: String? = null): StreamManifestDto

    // ---------------------------------------------------------------- lyrics --

    @GET("api/v1/lyrics/{trackId}")
    suspend fun lyrics(@Path("trackId") trackId: String): LyricsDto

    // ---------------------------------------------------------------- upload --

    @POST("api/v1/uploads")
    suspend fun createUpload(@Body body: CreateUploadRequestDto): UploadSessionDto

    @PUT("api/v1/uploads/{uploadId}/parts/{partNumber}")
    suspend fun uploadPart(
        @Path("uploadId") uploadId: String,
        @Path("partNumber") partNumber: Int,
        @Body body: RequestBody,
    )

    @POST("api/v1/uploads/{uploadId}/complete")
    suspend fun completeUpload(
        @Path("uploadId") uploadId: String,
        @Body body: CompleteUploadRequestDto = CompleteUploadRequestDto(),
    )

    @DELETE("api/v1/uploads/{uploadId}")
    suspend fun cancelUpload(@Path("uploadId") uploadId: String)

    @GET("api/v1/downloads/manifest")
    suspend fun downloadManifest(): DownloadManifestDto
}

// ------------------------------------------------------------------------------
// Requests / responses that only this module needs
// ------------------------------------------------------------------------------

@Serializable
data class PlaylistAddRequestDto(
    val trackId: String,
    /** Fractional index from `:core:common`'s LexoRank; omitted appends. */
    @SerialName("position_key") val positionKey: String? = null,
)

@Serializable
data class HomeCardDto(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("artwork_hash") val artworkHash: String? = null,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
)

@Serializable
data class HomeShelfDto(
    val id: String,
    val title: String,
    val reason: String? = null,
    val cards: List<HomeCardDto> = emptyList(),
)

@Serializable
data class HomeSectionsDto(val shelves: List<HomeShelfDto> = emptyList())

@Serializable
data class DownloadManifestItemDto(
    @SerialName("track_id") val trackId: String,
    @SerialName("rendition_id") val renditionId: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
    val url: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0L,
)

@Serializable
data class DownloadManifestDto(
    val items: List<DownloadManifestItemDto> = emptyList(),
    @SerialName("part_size_bytes") val partSizeBytes: Long = 4L * 1024 * 1024,
)

/** Plain-text helpers so callers never hand-build request bodies. */
object Bodies {
    private val octet = "application/octet-stream".toMediaType()
    private val text = "text/plain; charset=utf-8".toMediaType()

    fun bytes(value: ByteArray): RequestBody = value.toRequestBody(octet)

    fun text(value: String): RequestBody = value.toRequestBody(text)
}

// ------------------------------------------------------------------------------
// Domain conversions kept next to the DTOs they belong to
// ------------------------------------------------------------------------------

fun TrackDto.toDomain(): Track = toModel()
fun AlbumDto.toDomain(): Album = toModel()
fun ArtistDto.toDomain(): Artist = toModel()
fun PlaylistDto.toDomain(): Playlist = toModel()
fun SearchResultDto.toDomain(): SearchResult = toModel()
fun LyricsDto.toDomain(trackId: String): Lyrics = toModel(trackId)

/** Shared instance; see [NoizJson] for the field-by-field rationale. */
fun noizJson(): Json = NoizJson
