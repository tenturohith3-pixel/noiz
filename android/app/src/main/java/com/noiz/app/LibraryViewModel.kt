package com.noiz.app

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noiz.model.Album
import com.noiz.model.Artist
import com.noiz.model.QualityProfile
import com.noiz.model.Rendition
import com.noiz.model.Track
import com.noiz.network.NoizApi
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the signed-in user sees. */
data class LibraryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val tracks: List<Track> = emptyList(),
    val query: String = "",
    val serverUrl: String = "",
    val username: String = "",
)

/**
 * Loads the server catalogue and hands playable items to the session.
 *
 * The view model owns no player (§8.2): it builds `MediaItem`s and the service's
 * `MediaLibrarySession` does the actual playback, so the notification, Android Auto and
 * the UI never disagree.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    private val api: NoizApi,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val allTracks = MutableStateFlow<List<Track>>(emptyList())

    init {
        refresh()
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
        applyFilter()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                val session = com.noiz.datastore.SessionStore(getApplication())
                val url = session.serverUrl()
                val current = session.session.first()
                val tracks: List<Track> =
                    api.catalogTracks(limit = 200).tracks.map(TrackMapper::toDomain)
                Triple(url, current, tracks)
            }.fold(
                onSuccess = { loaded ->
                    val tracks = loaded.third
                    allTracks.value = tracks
                    _state.value = _state.value.copy(
                        loading = false,
                        error = null,
                        tracks = tracks,
                        serverUrl = loaded.first,
                        username = loaded.second?.username.orEmpty(),
                    )
                },
                onFailure = { error ->
                    Log.w(TAG, "catalogue load failed", error)
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error.message ?: "Could not reach the server.",
                    )
                },
            )
        }
    }

    private fun applyFilter() {
        val q = _state.value.query.trim()
        val source = allTracks.value
        _state.value = _state.value.copy(
            tracks = if (q.isEmpty()) {
                source
            } else {
                source.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.artistName.contains(q, ignoreCase = true) ||
                        it.albumTitle?.contains(q, ignoreCase = true) == true
                }
            },
        )
    }

    companion object {
        private const val TAG = "LibraryViewModel"
    }
}

@Singleton
object TrackMapper {
    fun toDomain(dto: com.noiz.network.TrackDto): Track = Track(
        id = dto.id,
        title = dto.title,
        albumId = dto.album?.id,
        albumTitle = dto.album?.title,
        artistName = dto.artists.firstOrNull()?.name.orEmpty(),
        discNo = dto.discNumber,
        trackNo = dto.trackNumber,
        durationMs = dto.durationMs,
        explicit = dto.explicit,
        loudnessLufs = dto.loudnessLufs,
        truePeakDb = dto.truePeakDb,
        gaplessNext = dto.gaplessNext,
        hasLyrics = dto.hasLyrics,
        artwork = com.noiz.model.Artwork(
            hash = dto.artwork?.hash,
            url = dto.artwork?.url,
        ),
        renditions = dto.renditions.map {
            Rendition(
                id = it.id,
                codec = it.codec,
                container = it.container,
                bitrateKbps = it.bitrateKbps,
                sampleRate = it.sampleRate,
                bitDepth = it.bitDepth,
                sizeBytes = it.sizeBytes,
                sha256 = it.sha256,
            )
        },
    )
}