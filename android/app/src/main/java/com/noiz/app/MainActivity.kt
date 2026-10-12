package com.noiz.app

import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.noiz.designsystem.GlassTierConstraint
import com.noiz.designsystem.GlassTierController
import com.noiz.designsystem.NoizPalette
import com.noiz.designsystem.NoizTypography
import com.noiz.designsystem.ProvideGlass
import com.noiz.home.HomeCard
import com.noiz.home.HomeScreen
import com.noiz.home.HomeShelf
import com.noiz.home.HomeUiState
import com.noiz.home.NowPlayingUiState
import com.noiz.model.RepeatMode
import com.noiz.model.Track
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.first
import androidx.hilt.navigation.compose.hiltViewModel

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
class MainActivity : ComponentActivity() {

    private val glassController by lazy { GlassTierController(applicationContext, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

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

    var signedIn by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        val session = com.noiz.datastore.SessionStore(context).session.first()
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
                hazeState = hazeState,
                viewModel = viewModel,
                onQueryChange = viewModel::setQuery,
                onRetry = viewModel::refresh,
                onPlay = { track -> PlaybackBridge.play(context, track) },
                onPlayAll = { tracks -> PlaybackBridge.playAll(context, tracks) },
            )
        }
    }
}

/**
 * The library surface.
 *
 * Maps [LibraryUiState] onto the existing [HomeScreen] contract so the glass UI, the
 * mini-player and the Now Playing sheet all get real data. Search is a local filter
 * over the loaded catalogue, which is what §7.1 asks for on the Library tab.
 */
@Composable
private fun LibraryScreen(
    state: LibraryUiState,
    hazeState: HazeState,
    viewModel: LibraryViewModel,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onPlay: (Track) -> Unit,
    onPlayAll: (List<Track>) -> Unit,
) {
    val loadingLabel = stringResource(R.string.connect_loading)
    val emptyLabel = stringResource(R.string.connect_empty)
    val retryLabel = stringResource(R.string.connect_retry)

    val homeState = remember(state.tracks, state.query) {
        val cards = state.tracks.map { track ->
            HomeCard(
                id = track.id,
                title = track.title,
                subtitle = track.artistName,
                artworkUrl = track.artwork.url,
            )
        }
        HomeUiState(
            shelves = listOf(
                HomeShelf(
                    id = "library",
                    title = "Library",
                    cards = cards,
                ),
            ),
            nowPlaying = NowPlayingUiState(canPlay = false),
        )
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search your library") },
            singleLine = true,
            modifier = Modifier
                .fillMaxSize(0.94f)
                .padding(start = 20.dp, end = 20.dp, top = 96.dp)
                .height(52.dp),
        )
        Spacer(Modifier.height(8.dp))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, style = NoizTypography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text(retryLabel) }
                }
            }

            state.tracks.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text(emptyLabel, style = NoizTypography.bodyMedium) }

            else -> HomeScreen(
                state = homeState,
                hazeState = hazeState,
                onCardClick = { card ->
                    state.tracks.firstOrNull { it.id == card.id }?.let(onPlay)
                },
            )
        }
    }
}

/**
 * Hands a queue to the playback session.
 *
 * Starts [com.noiz.playback.PlaybackService] and lets the session's browse tree resolve
 * the media ids, so playback goes through the same engine the notification and Android
 * Auto use (§8.3).
 */
private object PlaybackBridge {

    fun play(context: android.content.Context, track: Track) = playAll(context, listOf(track))

    fun playAll(context: android.content.Context, tracks: List<Track>) {
        val ids = tracks.map { it.id }
        context.startService(
            android.content.Intent(
                context,
                com.noiz.playback.PlaybackService::class.java,
            ).apply {
                action = com.noiz.playback.PlaybackService.ACTION_PLAY
                putStringArrayListExtra(
                    com.noiz.playback.PlaybackService.EXTRA_TRACK_IDS,
                    ArrayList(ids),
                )
                putExtra(
                    com.noiz.playback.PlaybackService.EXTRA_START_INDEX,
                    0,
                )
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
        context.registerReceiver(receiver, IntentFilterPowerSave)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

private val IntentFilterPowerSave: android.content.IntentFilter =
    android.content.IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)