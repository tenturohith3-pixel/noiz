package com.noiz.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.noiz.designsystem.GlassTierConstraint
import com.noiz.designsystem.GlassTierController
import com.noiz.designsystem.NoizPalette
import com.noiz.designsystem.NoizTypography
import com.noiz.designsystem.ProvideGlass
import com.noiz.data.artworkUrl
import com.noiz.home.HomeCard
import com.noiz.home.HomeScreen
import com.noiz.home.HomeShelf
import com.noiz.home.HomeUiState
import com.noiz.home.NowPlayingUiState
import com.noiz.model.RepeatMode
import com.noiz.model.Track
import com.noiz.playback.PlaybackService
import com.noiz.playback.PlaybackStateBus
import com.noiz.playback.PlaybackStatus
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * Root scaffold.
 *
 * One [ProvideGlass] scope, one [HazeState], and a theme that follows the system.
 *
 * The previous version hardcoded `GlassTier.Full`, so the entire tier/thermal/battery/
 * jank system was dead code *and* Android 10/11 devices ran live `RenderEffect` blur.
 * It also never wrapped the tree in a `MaterialTheme`.
 */
@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val glassController by lazy { GlassTierController(applicationContext, lifecycleScope) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // The media notification is the only way to control playback from outside the
        // app. On API 33+ it is invisible until this permission is granted, and the
        // manifest declaration alone does not request it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val hazeState = remember { HazeState() }
            val tier by glassController.tier.collectAsStateWithLifecycle()
            ObservePlatformGlassSignals(glassController)

            val isDark = isSystemInDarkTheme()
            val palette = if (isDark) NoizPalette.Brand else NoizPalette.Light
            val colorScheme = if (isDark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)

            MaterialTheme(typography = NoizTypography, colorScheme = colorScheme) {
                ProvideGlass(tier = tier, palette = palette) {
                    NoizApp(hazeState)
                }
            }
        }
    }
}

/**
 * Chooses between Connect and the library based on whether a session exists, then
 * renders the real catalogue.
 */
@Composable
private fun NoizApp(hazeState: HazeState) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = hiltViewModel()
    // `lifecycleScope` is an extension on LifecycleOwner and does not resolve inside a
    // standalone composable; a remembered scope is the composable-native equivalent.
    val appScope = rememberCoroutineScope()
    val playback by PlaybackStateBus.state.collectAsStateWithLifecycle()

    var signedIn by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        // A corrupt DataStore file must degrade to the sign-in screen, not a crash
        // loop: this read used to be uncaught, so every launch died before rendering.
        val session = runCatching {
            com.noiz.datastore.SessionStore(context).session.first()
        }.getOrNull()
        signedIn = session != null && session.accessToken.isNotBlank()
        if (signedIn == true) viewModel.refresh()
    }

    when (signedIn) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        false -> ConnectScreen(onSignedIn = {
            signedIn = true
            viewModel.refresh()
        })

        true -> {
            val state by viewModel.state.collectAsStateWithLifecycle()
            LibraryScreen(
                state = state,
                playback = playback,
                hazeState = hazeState,
                onQueryChange = viewModel::setQuery,
                onRetry = viewModel::refresh,
                onPlay = { track -> PlaybackBridge.play(context, track) },
                onPlayAll = { tracks -> PlaybackBridge.playAll(context, tracks) },
                onPlayPlaylist = { playlistId ->
                    appScope.launch {
                        val tracks = viewModel.tracksForPlaylist(playlistId)
                        if (tracks.isNotEmpty()) PlaybackBridge.playAll(context, tracks)
                    }
                },
                onPlayPause = { PlaybackBridge.send(context, PlaybackService.ACTION_TOGGLE_PLAY) },
                onNext = { PlaybackBridge.send(context, PlaybackService.ACTION_NEXT) },
                onSeek = { positionMs -> PlaybackBridge.seek(context, positionMs) },
                onShuffle = { PlaybackBridge.send(context, PlaybackService.ACTION_SHUFFLE) },
                onRepeat = { PlaybackBridge.send(context, PlaybackService.ACTION_REPEAT) },
            )
        }
    }
}

/**
 * The library surface.
 *
 * Maps [LibraryUiState] and the process-wide [PlaybackStatus] onto the [HomeScreen]
 * contract so the glass UI, the mini-player and the Now Playing sheet all show and
 * control the same session the notification does. Content is organised into
 * Spotify-style shelves — Recently added / Albums / Artists / Playlists — filterable
 * with the chip row under the search field.
 */
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    playback: PlaybackStatus,
    hazeState: HazeState,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPlay: (Track) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
    onPlayPlaylist: (String) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val loadingLabel = stringResource(R.string.connect_loading)
    val emptyLabel = stringResource(R.string.connect_empty)
    val retryLabel = stringResource(R.string.connect_retry)

    var tab by remember { mutableIntStateOf(0) }

    val nowArtworkUrl = remember(state.tracks, playback.trackId) {
        state.tracks.firstOrNull { it.id == playback.trackId }?.artwork?.url
    }
    val positionEpochMs = remember(playback) { SystemClock.elapsedRealtime() }

    val shelves = remember(state, tab) {
        buildList {
            if (tab == 0 || tab == 1) {
                add(
                    HomeShelf(
                        id = "tracks",
                        title = "Recently added",
                        cards = state.tracks.take(30).map { t ->
                            HomeCard(
                                id = t.id,
                                title = t.title,
                                subtitle = t.artistName,
                                artworkUrl = t.artwork.url,
                            )
                        },
                    ),
                )
            }
            if (tab == 0 || tab == 2) {
                add(
                    HomeShelf(
                        id = "albums",
                        title = "Albums",
                        cards = state.albums.map { album ->
                            HomeCard(
                                id = "album:${album.id}",
                                title = album.title,
                                subtitle = album.artistName,
                                artworkUrl = album.artwork.url,
                            )
                        },
                    ),
                )
            }
            if (tab == 0 || tab == 3) {
                add(
                    HomeShelf(
                        id = "artists",
                        title = "Artists",
                        cards = state.artists.map { artist ->
                            HomeCard(
                                id = "artist:${artist.id}",
                                title = artist.name,
                                subtitle = null,
                                artworkUrl = artworkUrl(artist.imageHash, state.serverUrl),
                            )
                        },
                    ),
                )
            }
            if (tab == 0 || tab == 4) {
                add(
                    HomeShelf(
                        id = "playlists",
                        title = "Playlists",
                        cards = state.playlists.map { playlist ->
                            HomeCard(
                                id = "playlist:${playlist.id}",
                                title = playlist.name,
                                subtitle = playlist.description,
                                artworkUrl = playlist.artwork.url,
                            )
                        },
                    ),
                )
            }
        }
    }

    val homeState = remember(shelves, playback, nowArtworkUrl, state.query) {
        HomeUiState(
            shelves = shelves,
            nowPlaying = NowPlayingUiState(
                title = playback.title,
                artist = playback.artist,
                album = playback.album,
                artworkUrl = nowArtworkUrl,
                isPlaying = playback.isPlaying,
                durationMs = playback.durationMs,
                positionMs = {
                    if (playback.isPlaying) {
                        playback.positionMs +
                            (SystemClock.elapsedRealtime() - positionEpochMs)
                    } else {
                        playback.positionMs
                    }
                },
                hasNext = playback.hasNext,
                canPlay = playback.canPlay,
                repeatMode = playback.repeatMode.toRepeatMode(),
                shuffleEnabled = playback.shuffleEnabled,
            ),
        )
    }

    val hasContent = shelves.any { it.cards.isNotEmpty() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search your library") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 96.dp)
                .height(52.dp),
        )
        Spacer(Modifier.height(8.dp))

        // Library filter chips — Spotify's Home/Search/Library split lives here as a
        // single scrollable chip row so the sheet, mini-player and backdrop stay put.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("All", "Tracks", "Albums", "Artists", "Playlists")
                .forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(label) },
                    )
                }
        }
        Spacer(Modifier.height(4.dp))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null && !hasContent -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, style = NoizTypography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text(retryLabel) }
                }
            }

            !hasContent -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text(emptyLabel, style = NoizTypography.bodyMedium) }

            else -> HomeScreen(
                state = homeState,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onSeek = onSeek,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onCardClick = { card ->
                    when {
                        card.id.startsWith("album:") -> {
                            val tracks = albumTracksFrom(state, card.id.removePrefix("album:"))
                            if (tracks.isNotEmpty()) onPlayAll(tracks)
                        }
                        card.id.startsWith("artist:") -> {
                            val tracks = artistTracksFrom(state, card.subtitle.orEmpty())
                            if (tracks.isNotEmpty()) onPlayAll(tracks)
                        }
                        card.id.startsWith("playlist:") ->
                            onPlayPlaylist(card.id.removePrefix("playlist:"))
                        else -> state.tracks.firstOrNull { it.id == card.id }?.let(onPlay)
                    }
                },
            )
        }
    }
}

/**
 * Local shelf-tap helpers: the album/artist queues are resolved from the loaded track
 * list, so a tap needs no network round-trip.
 */
private fun albumTracksFrom(state: LibraryUiState, albumId: String): List<Track> =
    state.tracks.filter { it.albumId == albumId }

private fun artistTracksFrom(state: LibraryUiState, artistName: String): List<Track> =
    state.tracks.filter { it.artistName.contains(artistName, ignoreCase = true) }

private fun Int.toRepeatMode(): RepeatMode = when (this) {
    androidx.media3.common.Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    androidx.media3.common.Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}

/**
 * Hands a queue to the playback session.
 *
 * Starts [PlaybackService] and lets the session's browse tree resolve the media ids,
 * so playback goes through the same engine the notification and Android Auto use
 * (§8.3).
 */
private object PlaybackBridge {

    fun play(context: android.content.Context, track: Track) = playAll(context, listOf(track))

    fun playAll(context: android.content.Context, tracks: List<Track>) {
        val ids = tracks.map { it.id }
        context.startService(
            Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_PLAY
                putStringArrayListExtra(
                    PlaybackService.EXTRA_TRACK_IDS,
                    ArrayList(ids),
                )
                putExtra(PlaybackService.EXTRA_START_INDEX, 0)
            },
        )
    }

    fun send(context: android.content.Context, action: String) {
        context.startService(Intent(context, PlaybackService::class.java).setAction(action))
    }

    fun seek(context: android.content.Context, positionMs: Long) {
        context.startService(
            Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_SEEK
                putExtra(PlaybackService.EXTRA_POSITION_MS, positionMs.coerceAtLeast(0L))
            },
        )
    }
}

/**
 * Battery-saver state pushed into the tier controller.
 *
 * Battery Saver used to be sampled exactly once inside the controller's `init`, so
 * toggling it at runtime never re-evaluated the tier.
 */
@Composable
private fun ObservePlatformGlassSignals(controller: GlassTierController) {
    val context = LocalContext.current

    DisposableEffect(controller) {
        val powerManager = context.getSystemService(PowerManager::class.java)

        fun publish() {
            powerManager?.let {
                controller.setConstraint(GlassTierConstraint.BatterySaver, it.isPowerSaveMode)
            }
        }

        publish()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) = publish()
        }
        // API 34+ requires an explicit export flag. A system broadcast (power saver)
        // is delivered regardless, so RECEIVER_NOT_EXPORTED is correct and safest.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(
                receiver,
                IntentFilterPowerSave,
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilterPowerSave)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

private val IntentFilterPowerSave: android.content.IntentFilter =
    android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)