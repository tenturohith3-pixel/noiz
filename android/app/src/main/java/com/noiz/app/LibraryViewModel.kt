package com.noiz.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noiz.data.CatalogRepository
import com.noiz.data.toDomain
import com.noiz.datastore.SessionStore
import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.Playlist
import com.noiz.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the signed-in user sees. */
data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val query: String = "",
    val serverUrl: String = "",
    val username: String = "",
)

/**
 * Reads the catalogue from Room and keeps it fresh from the server.
 *
 * Room is the playback source of truth: `/catalog/sync` writes into it here, the
 * playback service resolves queue ids from the same rows, and this screen observes the
 * table directly. The previous version fetched `/catalog/tracks` into a list, never
 * wrote Room, and therefore every tap resolved zero playable items.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    private val repository: CatalogRepository,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val allTracks = MutableStateFlow<List<Track>>(emptyList())
    private var syncJob: Job? = null

    init {
        val sessionStore = SessionStore(application)

        viewModelScope.launch {
            // Resolve the base URL BEFORE any catalogue emission maps its artwork:
            // artwork URLs are absolute and the image loader has no other way to know
            // which server to dial. Collects below are infinite, so they live in
            // nested launches started only after the session read completes.
            val session = runCatching { sessionStore.session.first() }.getOrNull()
            _state.value = _state.value.copy(
                serverUrl = session?.serverUrl.orEmpty(),
                username = session?.user?.username.orEmpty(),
            )
            val serverUrl = session?.serverUrl

            // An exception escaping any collector would take down the whole process
            // (unhandled coroutine exception): a single Room open failure must surface
            // as the error screen instead.
            launch {
                runCatching {
                    repository.tracks().collect { entities ->
                        val tracks = entities.map { it.toDomain(serverUrl = serverUrl) }
                        allTracks.value = tracks
                        _state.value = _state.value.copy(
                            tracks = filter(tracks, _state.value.query),
                            loading = false,
                            // Cached rows are enough to play; a stale sync error must not hide
                            // the library once Room has content.
                            error = if (tracks.isEmpty()) _state.value.error else null,
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "library read failed", error)
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "Could not read the library.",
                    )
                }
            }

            launch {
                runCatching {
                    repository.albums().collect { rows ->
                        _state.value = _state.value.copy(
                            albums = rows.map { it.toDomain(serverUrl = serverUrl) },
                        )
                    }
                }.onFailure { Log.w(TAG, "albums read failed", it) }
            }

            launch {
                runCatching {
                    repository.artists().collect { rows ->
                        _state.value = _state.value.copy(artists = rows.map { it.toDomain() })
                    }
                }.onFailure { Log.w(TAG, "artists read failed", it) }
            }

            launch {
                runCatching {
                    repository.playlists().collect { rows ->
                        _state.value = _state.value.copy(
                            playlists = rows.map { it.toDomain(serverUrl = serverUrl) },
                        )
                    }
                }.onFailure { Log.w(TAG, "playlists read failed", it) }
            }
        }

        refresh()
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(
            query = value,
            tracks = filter(allTracks.value, value),
        )
    }

    /** Tracks of one album, in disc/track order, for album-card taps. */
    fun tracksForAlbum(albumId: String): List<Track> =
        allTracks.value.filter { it.albumId == albumId }

    /** Tracks by one artist (name contains), for artist-card taps. */
    fun tracksForArtist(artistName: String): List<Track> =
        allTracks.value.filter { it.artistName.contains(artistName, ignoreCase = true) }

    /** Tracks of a playlist, resolved from Room at tap time. */
    suspend fun tracksForPlaylist(playlistId: String): List<Track> = runCatching {
        repository.playlistTracks(playlistId).first()
            .map { it.toDomain(serverUrl = _state.value.serverUrl) }
    }.getOrDefault(emptyList())

    /**
     * Pulls any new catalog pages, then waits for Room to re-emit.
     *
     * The UI is never blocked on the network when cached rows exist: playback works
     * offline from the replica, and the sync result only surfaces as an error when the
     * library is genuinely empty.
     */
    fun refresh() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = allTracks.value.isEmpty(),
                error = null,
            )
            runCatching { repository.syncAll() }.onFailure { error ->
                Log.w(TAG, "catalogue sync failed", error)
                if (allTracks.value.isEmpty()) {
                    _state.value = _state.value.copy(
                        error = error.message ?: "Could not reach the server.",
                    )
                }
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    private fun filter(source: List<Track>, rawQuery: String): List<Track> {
        val q = rawQuery.trim()
        if (q.isEmpty()) return source
        return source.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artistName.contains(q, ignoreCase = true) ||
                it.albumTitle?.contains(q, ignoreCase = true) == true
        }
    }

    companion object {
        private const val TAG = "LibraryViewModel"
    }
}
