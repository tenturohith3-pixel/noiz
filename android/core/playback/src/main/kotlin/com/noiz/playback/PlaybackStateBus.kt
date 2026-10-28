package com.noiz.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide playback snapshot consumed by the in-app UI.
 *
 * The service is the only writer. Compose observes this instead of binding a
 * `MediaController`, which keeps the now-playing surface usable before the session
 * finishes connecting and avoids a second source of truth for position.
 */
data class PlaybackStatus(
    val trackId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val canPlay: Boolean = false,
    val hasNext: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleEnabled: Boolean = false,
    val errorMessage: String? = null,
)

object PlaybackStateBus {

    private val _state = MutableStateFlow(PlaybackStatus())
    val state: StateFlow<PlaybackStatus> = _state.asStateFlow()

    fun publish(status: PlaybackStatus) {
        _state.value = status
    }
}
