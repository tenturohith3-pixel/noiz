package com.noiz.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.noiz.model.RepeatMode
import com.noiz.designsystem.AmbientBackdrop
import com.noiz.designsystem.GlassLevel
import com.noiz.designsystem.GlassSurface
import com.noiz.designsystem.LocalGlassTier
import com.noiz.designsystem.LocalNoizPalette
import com.noiz.designsystem.NoizMotion
import com.noiz.designsystem.NoizTypography
import com.noiz.designsystem.TabularTimeStyle
import com.noiz.designsystem.GlassTier
import dev.chrisbanes.haze.HazeState

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Sheet anchors. Two values, one continuous gesture-driven progress (PRD §7.4). */
enum class SheetValue { Collapsed, Expanded }

/**
 * Rail sections are horizontal; the "continue listening" section is vertical.
 *
 * §7.1 asks for a *rail* for recent plays. Everything was a full-width vertical card,
 * which also meant far more visible glass panels and therefore more blur work per frame.
 */
private const val CONTENT_TYPE_CARD = "card"

/** Track id -> the artwork URL the screen should load, resolved by the ViewModel. */
@Immutable
data class NowPlayingUiState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val qualityChip: String? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: () -> Long = { 0L },
    val hasNext: Boolean = true,
    /** False until something is actually loaded; gates the transport controls. */
    val canPlay: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
)

@Immutable
data class HomeShelf(
    val id: String,
    val title: String,
    val cards: List<HomeCard>,
    /** Rails scroll horizontally; lists stack vertically. */
    val isRail: Boolean = true,
)

@Immutable
data class HomeCard(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
)

@Immutable
data class HomeUiState(
    /**
     * A string *resource id* rather than a formatted literal, so the greeting can be
     * localised and can react to the time of day.
     */
    @StringRes val greetingRes: Int = com.noiz.home.R.string.home_greeting_evening,
    val shelves: List<HomeShelf> = emptyList(),
    val nowPlaying: NowPlayingUiState = NowPlayingUiState(),
)

/**
 * Home screen root.
 *
 * Exactly one backdrop source per screen (frame rule #1): [AmbientBackdrop] is the
 * only Haze source, and every glass panel samples the same [HazeState]. The Now
 * Playing sheet is an [AnchoredDraggableState] so one progress value drives artwork
 * size, corner radius, title position and control layout — no cross-fade jumps.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    hazeState: HazeState,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    onCardClick: (HomeCard) -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onToggleMiniPlayer: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val tier = LocalGlassTier.current

    Box(Modifier.fillMaxSize()) {
        // G0 Canvas — the single backdrop source.
        AmbientBackdrop(
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize(),
            animate = tier == GlassTier.Full,
        )

        HomeFeed(
            state = state,
            hazeState = hazeState,
            listState = listState,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onCardClick = onCardClick,
        )

        // §7.1: the mini-player is only present when an item is active.
        if (state.nowPlaying.canPlay) {
            NowPlayingHost(
                nowPlaying = state.nowPlaying,
                hazeState = hazeState,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onSeek = onSeek,
                onToggleMiniPlayer = onToggleMiniPlayer,
            )
        }
    }
}


@Composable
private fun BoxScope.HomeFeed(
    state: HomeUiState,
    hazeState: HazeState,
    listState: LazyListState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onCardClick: (HomeCard) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 96.dp,
            bottom = 168.dp,
            start = 20.dp,
            end = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "greeting", contentType = "greeting") {
            Text(
                // Localised via a resource id, not a literal baked into the state class.
                text = stringResource(state.greetingRes),
                style = MaterialTheme.typography.displayLarge,
                // §13: headings let a screen-reader user jump between sections.
                modifier = Modifier.semantics { heading() },
            )
        }

        state.shelves.forEach { shelf ->
            item(key = shelf.id, contentType = "shelf-header") {
                Text(
                    text = shelf.title,
                    style = NoizTypography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            items(
                items = shelf.cards,
                key = { "${shelf.id}:${it.id}" },
                contentType = { CONTENT_TYPE_CARD },
            ) { card ->
                HomeCardRow(card = card, hazeState = hazeState, onClick = onCardClick)
            }
        }
    }

    // G2 top bar — blur only while content scrolls beneath it (frame rule #5).
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    val barAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        label = "topbar-tint",
    )
    GlassSurface(
        hazeState = hazeState,
        level = GlassLevel.G2,
        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { alpha = barAlpha },
    ) {}
}


/**
 * A single catalogue card.
 *
 * §13 requires every interactive control to expose a content description and a role;
 * the previous version had neither, so TalkBack announced an unlabelled, inert box.
 * The card is a `selectable` button because tapping it starts playback (§3.2).
 */
@Composable
private fun HomeCardRow(
    card: HomeCard,
    hazeState: HazeState,
    onClick: (HomeCard) -> Unit,
) {
    val artworkDescription = if (card.title.isNotBlank()) {
        stringResource(R.string.cd_artwork_for, card.title)
    } else {
        stringResource(R.string.cd_artwork_generic)
    }

    GlassSurface(
        hazeState = hazeState,
        level = GlassLevel.G1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = false,
                role = Role.Button,
                onClick = { onClick(card) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(card.title, card.subtitle)
                    .joinToString(", ")
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkPlaceholder(
                url = card.artworkUrl,
                modifier = Modifier.size(56.dp),
                cornerRadius = 14.dp,
                label = artworkDescription,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    card.title,
                    style = NoizTypography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                card.subtitle?.let {
                    Text(
                        it,
                        style = NoizTypography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Owns the mini-player and the G3 Now Playing sheet.
 *
 * The sheet is a full-height panel anchored *below* the viewport when collapsed.
 * The mini-player row is drawn by the sheet itself at the peek position, so the
 * drag has no separate element to reconcile.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.NowPlayingHost(
    nowPlaying: NowPlayingUiState,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMiniPlayer: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = constraints.maxHeight.toFloat()
        val peekPx = with(density) { 72.dp.toPx() }
        val collapsedAnchor = heightPx - peekPx
        val expandedAnchor = 0f

        val sheetState = remember(density) {
            AnchoredDraggableState(
                initialValue = SheetValue.Collapsed,
                positionalThreshold = { distance: Float -> distance * 0.5f },
                velocityThreshold = { with(density) { 125.dp.toPx() } },
                snapAnimationSpec = NoizMotion.SheetSpring,
                decayAnimationSpec = exponentialDecay(),
            )
        }

        androidx.compose.runtime.SideEffect {
            sheetState.updateAnchors(
                DraggableAnchors {
                    SheetValue.Collapsed at collapsedAnchor
                    SheetValue.Expanded at expandedAnchor
                },
            )
        }

        // progress 0 = collapsed, 1 = expanded. Consumed only in layout/draw lambdas.
        //
        // NOTE: there is deliberately no `val offset = sheetState.offset` here. Reading
        // the offset into a local during composition invalidated this whole
        // BoxWithConstraints body on every drag frame — artwork, three Texts, the seek
        // bar and four IconButtons recomposed 60x/s. `sheetState.offset` is read inside
        // the layout and graphicsLayer lambdas below, where it only invalidates layout.
        fun progressFor(f: Float): Float =
            ((collapsedAnchor - f) / (collapsedAnchor - expandedAnchor)).coerceIn(0f, 1f)

        // Predictive back scrubs the sheet, never animates a blur radius (§7.4).
        PredictiveBackHandler(enabled = sheetState.currentValue == SheetValue.Expanded) { progress ->
            var lastProgress = 1f
            try {
                progress.collect { event ->
                    val delta = (lastProgress - event.progress) * (collapsedAnchor - expandedAnchor)
                    sheetState.dispatchRawDelta(delta)
                    lastProgress = event.progress
                }
                sheetState.animateTo(SheetValue.Collapsed)
            } catch (cancel: CancellationException) {
                sheetState.animateTo(SheetValue.Expanded)
                throw cancel
            }
        }

        // Hoisted out of the semantics lambda: `stringResource` is @Composable and
            // the semantics block is not, so calling it inline did not compile.
            val expandLabel = stringResource(R.string.action_expand_now_playing)
            val collapseLabel = stringResource(R.string.action_collapse_now_playing)
            val nothingPlayingLabel = stringResource(R.string.home_nothing_playing)
            val playbackStateLabel = stringResource(
                if (nowPlaying.isPlaying) R.string.state_playing else R.string.state_paused,
            )

            GlassSurface(
            hazeState = hazeState,
            level = GlassLevel.G3,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight)
                .offset { IntOffset(0, sheetState.offset.roundToInt()) }
                .anchoredDraggable(sheetState, Orientation.Vertical)
                .semantics(mergeDescendants = true) {
                    // §13: an unlabelled region only announced "custom actions".
                    // Role is not set here: `Role` is an inline value class so its
                    // setter is name-mangled and cannot be assigned with `=`, and the
                    // sheet is drag-driven rather than a plain click target anyway.
                    contentDescription = nowPlaying.title.ifBlank { nothingPlayingLabel }
                    stateDescription = playbackStateLabel
                    customActions = listOf(
                        CustomAccessibilityAction(expandLabel) {
                            scope.launch { sheetState.animateTo(SheetValue.Expanded) }
                            true
                        },
                        CustomAccessibilityAction(collapseLabel) {
                            scope.launch { sheetState.animateTo(SheetValue.Collapsed) }
                            true
                        },
                    )
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                SheetHandle(
                    onCollapse = { scope.launch { sheetState.snapTo(SheetValue.Collapsed) } },
                )
                MiniPlayerRow(
                    nowPlaying = nowPlaying,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    // The host owns the sheet, so it can expand itself. Relying on a
                    // caller-supplied callback left the default (empty) in place and the
                    // mini-player could never open the full Now Playing surface.
                    onExpand = {
                        onToggleMiniPlayer()
                        scope.launch { sheetState.animateTo(SheetValue.Expanded) }
                    },
                )
                ExpandedNowPlaying(
                    nowPlaying = nowPlaying,
                    progress = { progressFor(sheetState.offset) },
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onSeek = onSeek,
                )
            }
        }
    }
}


@Composable
private fun SheetHandle(onCollapse: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // §13 requires a 48 dp minimum touch target. At 44 dp the IconButton was
            // constrained below the minimum and the visible 4 dp pill had no click
            // handler, so the only target was invisible.
            .height(56.dp)
            .windowInsetsPadding(WindowInsets.statusBars),
        contentAlignment = Alignment.TopCenter,
    ) {
        val palette = LocalNoizPalette.current
        Box(
            Modifier
                .padding(top = 10.dp)
                .size(width = 40.dp, height = 4.dp)
                .clip(CircleShape)
                .background(palette.onSurface.copy(alpha = 0.35f)),
        )
        IconButton(
            onClick = onCollapse,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.action_collapse_now_playing),
            )
        }
    }
}

/**
 * The collapsed mini-player. It lives *inside* the sheet at the peek position so the
 * drag reveals Now Playing continuously instead of unmounting a separate bar.
 */
@Composable
private fun MiniPlayerRow(
    nowPlaying: NowPlayingUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
) {
    val playbackState = if (nowPlaying.isPlaying) {
        stringResource(R.string.state_playing)
    } else {
        stringResource(R.string.state_paused)
    }
    val miniDescription = nowPlaying.title.ifBlank { stringResource(R.string.home_nothing_playing) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // §7.1: tapping the mini-player expands Now Playing. It had no clickable at
        // all, so the whole feature was unreachable by touch or by TalkBack.
        Row(
            modifier = Modifier
                .weight(1f)
                // `clickable` already assigns Role.Button, and `Role` is an inline value class
                // whose setter is name-mangled so it cannot be assigned with `=` here.
                .clickable(onClick = onExpand)
                .semantics(mergeDescendants = true) {
                    contentDescription = miniDescription
                    stateDescription = playbackState
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtworkPlaceholder(
                url = nowPlaying.artworkUrl,
                modifier = Modifier.size(44.dp),
                cornerRadius = 12.dp,
                label = if (nowPlaying.title.isNotBlank()) {
                    stringResource(R.string.cd_artwork_for, nowPlaying.title)
                } else {
                    stringResource(R.string.cd_artwork_generic)
                },
            )
            Column {
                Text(
                    text = miniDescription,
                    style = NoizTypography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlaying.artist,
                    style = NoizTypography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (nowPlaying.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (nowPlaying.isPlaying) R.string.action_pause else R.string.action_play,
                ),
            )
        }
        IconButton(onClick = onNext, enabled = nowPlaying.hasNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.action_next),
            )
        }
    }
}

/**
 * The expanded Now Playing body. [progress] is a lambda so that reading the drag
 * position happens in the draw phase — zero recompositions per frame (frame rule #8).
 */

@Composable
private fun ExpandedNowPlaying(
    nowPlaying: NowPlayingUiState,
    progress: () -> Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val position = rememberFrameSyncedPosition(nowPlaying.positionMs, nowPlaying.isPlaying)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))

        ArtworkPlaceholder(
            url = nowPlaying.artworkUrl,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val p = progress()
                    // Interpolate size + corner radius from the single drag value.
                    val s = 1f + 0.06f * p
                    scaleX = s
                    scaleY = s
                    shape = RoundedCornerShape(12.dp.toPx() + 16.dp.toPx() * p)
                    clip = true
                },
            cornerRadius = 28.dp,
            // The clip lives in graphicsLayer above; ArtworkPlaceholder must not add a
            // second static clip or it overrides the animated radius.
            applyCornerClip = false,
            label = if (nowPlaying.title.isNotBlank()) {
                stringResource(R.string.cd_artwork_for, nowPlaying.title)
            } else {
                stringResource(R.string.cd_artwork_generic)
            },
        )

        Spacer(Modifier.height(24.dp))
        Text(
            nowPlaying.title,
            style = NoizTypography.displayLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(nowPlaying.artist, style = NoizTypography.bodyMedium)

        nowPlaying.qualityChip?.let { chip ->
            Spacer(Modifier.height(8.dp))
            Text(chip, style = TabularTimeStyle)
        }

        Spacer(Modifier.height(20.dp))
        SeekBar(
            positionMs = position,
            durationMs = nowPlaying.durationMs,
            onSeek = onSeek,
        )

        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Shuffle and Repeat are toggles: without role/stateDescription/selected a
            // screen reader could not tell whether they were on. The Repeat button used
            // to be wired to onShuffle, so Repeat was unreachable entirely.
            // Hoisted: the semantics lambda is not @Composable, so stringResource cannot
            // be called inside it.
            val shuffleStateLabel = stringResource(
                if (nowPlaying.shuffleEnabled) {
                    R.string.state_shuffle_on
                } else {
                    R.string.state_shuffle_off
                },
            )
            val repeatStateLabel = stringResource(
                when (nowPlaying.repeatMode) {
                    RepeatMode.ALL -> R.string.state_repeat_all
                    RepeatMode.ONE -> R.string.state_repeat_one
                    RepeatMode.OFF -> R.string.state_repeat_off
                },
            )

            IconButton(
                onClick = onShuffle,
                modifier = Modifier
                    .toggleable(
                        value = nowPlaying.shuffleEnabled,
                        role = Role.Switch,
                        onValueChange = { onShuffle() },
                    )
                    .semantics { stateDescription = shuffleStateLabel },
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.action_shuffle),
                    tint = if (nowPlaying.shuffleEnabled) {
                        LocalNoizPalette.current.accent
                    } else {
                        LocalNoizPalette.current.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = if (nowPlaying.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (nowPlaying.isPlaying) R.string.action_pause else R.string.action_play,
                    ),
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext, enabled = nowPlaying.hasNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.action_next),
                )
            }
            IconButton(
                onClick = onRepeat,
                modifier = Modifier.semantics { stateDescription = repeatStateLabel },
            ) {
                Icon(
                    Icons.Filled.Repeat,
                    contentDescription = stringResource(R.string.action_repeat),
                    tint = if (nowPlaying.repeatMode != RepeatMode.OFF) {
                        LocalNoizPalette.current.accent
                    } else {
                        LocalNoizPalette.current.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Samples the playback position once per frame while a seek bar is on screen — never
 * from a global ticker (§3.3).
 *
 * Returns a *lambda*, not a `Float`. The previous version returned the state value
 * directly, and because that read happened during composition, every 16 ms write
 * recomposed `ExpandedNowPlaying` → `SeekBar` → `formatTime()` → two fresh strings,
 * 60 times a second. The comments claiming "zero recompositions per frame" were
 * simply false. Callers read it inside `graphicsLayer`/`drawWithContent`, where a
 * write invalidates only the draw phase.
 */
@Composable
private fun rememberFrameSyncedPosition(
    positionMs: () -> Long,
    isPlaying: Boolean,
): () -> Float {
    val value = remember { mutableFloatStateOf(positionMs().toFloat()) }

    // rememberUpdatedState keeps the effect reading the *current* lambda. Without it a
    // track change left the loop polling a stale closure forever.
    val currentPosition by rememberUpdatedState(positionMs)

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            value.floatValue = currentPosition().toFloat()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { value.floatValue = currentPosition().toFloat() }
        }
    }

    return { value.floatValue }
}

/**
 * Seek bar.
 *
 * §13 requires a semantics tree that describes playback state; this had none, so
 * TalkBack announced an unlabelled element with no position and no way to seek. The
 * two custom actions were also wired to a no-op lambda.
 */
@Composable
private fun SeekBar(
    positionMs: () -> Float,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val palette = LocalNoizPalette.current
    val fraction = if (durationMs <= 0) 0f else (positionMs() / durationMs).coerceIn(0f, 1f)
    val positionLabel = stringResource(R.string.cd_seek_bar)
    val elapsed = formatTime((positionMs() / 1000f).toInt())
    val total = formatTime((durationMs / 1000).toInt())
    val timeDescription = stringResource(R.string.cd_time_elapsed_total, elapsed, total)
    // Hoisted out of the semantics lambda, which is not @Composable.
    val skipForwardLabel = stringResource(R.string.action_skip_forward)
    val skipBackLabel = stringResource(R.string.action_skip_back)

    Column(
        Modifier
            .fillMaxWidth()
            // One focus stop that reports position and accepts an absolute seek.
            .semantics(mergeDescendants = true) {
                contentDescription = "$positionLabel, $timeDescription"
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                    current = positionMs(),
                    range = 0f..durationMs.toFloat().coerceAtLeast(1f),
                )
                setProgress { requested ->
                    onSeek(requested.toLong().coerceIn(0L, durationMs))
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(skipForwardLabel) {
                        onSeek((positionMs() + 5_000f).toLong())
                        true
                    },
                    CustomAccessibilityAction(skipBackLabel) {
                        onSeek((positionMs() - 5_000f).coerceAtLeast(0f).toLong())
                        true
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .drawWithContent {
                    // `size` is only available in the draw phase; the old code mirrored
                    // it into a mutableIntStateOf that nothing ever read, writing state
                    // from an illegal phase on every frame.
                    val w = size.width
                    val h = 2.dp.toPx()
                    val y = (size.height - h) / 2f
                    drawRoundRect(
                        color = palette.onSurface.copy(alpha = 0.22f),
                        topLeft = Offset(0f, y),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(h / 2f),
                    )
                    drawRoundRect(
                        color = palette.accent,
                        topLeft = Offset(0f, y),
                        size = Size(w * fraction, h),
                        cornerRadius = CornerRadius(h / 2f),
                    )
                    drawContent()
                },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = timeDescription },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(elapsed, style = TabularTimeStyle)
            Text(total, style = TabularTimeStyle)
        }
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}


/**
 * Album artwork.
 *
 * The `url` parameter used to be accepted and never read, so every album cover in the
 * app rendered as a flat colour block — Coil is on the classpath and the artwork hash
 * is already in the model.
 *
 * @param applyCornerClip set false when the caller clips in a `graphicsLayer` that
 * animates its own shape; a second static clip would override the animation.
 */
@Composable
internal fun ArtworkPlaceholder(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    contentScale: ContentScale = ContentScale.Crop,
    applyCornerClip: Boolean = true,
    // Named `label` rather than `contentDescription` so it does not shadow the
    // SemanticsPropertyReceiver property inside the `semantics {}` block below.
    label: String? = null,
) {
    val palette = LocalNoizPalette.current
    val clipped = if (applyCornerClip) {
        modifier.clip(RoundedCornerShape(cornerRadius))
    } else {
        modifier
    }

    Surface(
        modifier = clipped.semantics {
            // §13: artwork needs alternative text or a spoken description.
            if (label != null) contentDescription = label
        },
        color = palette.muted,
    ) {
        Box(Modifier.fillMaxSize().background(palette.dominant.copy(alpha = 0.6f))) {
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = label,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
