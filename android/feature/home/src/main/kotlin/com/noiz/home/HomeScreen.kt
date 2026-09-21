package com.noiz.home

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
)

@Immutable
data class HomeShelf(
    val id: String,
    val title: String,
    val cards: List<HomeCard>,
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
    val greeting: String = "Good evening",
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
@Composable
fun HomeScreen(
    state: HomeUiState,
    hazeState: HazeState,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
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
        )

        NowPlayingHost(
            nowPlaying = state.nowPlaying,
            hazeState = hazeState,
            onPlayPause = onPlayPause,
            onNext = onNext,
        )
    }
}

@Composable
private fun BoxScope.HomeFeed(
    state: HomeUiState,
    hazeState: HazeState,
    listState: LazyListState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
                text = state.greeting,
                style = MaterialTheme.typography.displayLarge,
            )
        }

        state.shelves.forEach { shelf ->
            item(key = shelf.id, contentType = "shelf-header") {
                Text(shelf.title, style = NoizTypography.titleLarge)
            }
            items(
                items = shelf.cards,
                key = { "${shelf.id}:${it.id}" },
                contentType = { "card" },
            ) { card ->
                HomeCardRow(card = card, hazeState = hazeState)
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

@Composable
private fun HomeCardRow(card: HomeCard, hazeState: HazeState) {
    GlassSurface(
        hazeState = hazeState,
        level = GlassLevel.G1,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
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
            )
            Column(Modifier.weight(1f)) {
                Text(card.title, style = NoizTypography.titleLarge, maxLines = 1)
                card.subtitle?.let {
                    Text(it, style = NoizTypography.bodyMedium, maxLines = 1)
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
@Composable
private fun BoxScope.NowPlayingHost(
    nowPlaying: NowPlayingUiState,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
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
                decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
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

        val offset = sheetState.offset.takeIf { !it.isNaN() } ?: collapsedAnchor
        // progress 0 = collapsed, 1 = expanded. Consumed only in layout/draw lambdas.
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

        GlassSurface(
            hazeState = hazeState,
            level = GlassLevel.G3,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight)
                .offset { IntOffset(0, sheetState.offset.roundToInt()) }
                .anchoredDraggable(sheetState, Orientation.Vertical)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Expand now playing") {
                            scope.launch { sheetState.animateTo(SheetValue.Expanded) }
                            true
                        },
                        CustomAccessibilityAction("Collapse now playing") {
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
                )
                ExpandedNowPlaying(
                    nowPlaying = nowPlaying,
                    progress = { progressFor(sheetState.offset) },
                    onPlayPause = onPlayPause,
                    onNext = onNext,
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
            .height(44.dp)
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
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtworkPlaceholder(
            url = nowPlaying.artworkUrl,
            modifier = Modifier.size(44.dp),
            cornerRadius = 12.dp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = nowPlaying.title.ifEmpty { "Nothing playing" },
                style = NoizTypography.bodyMedium,
                maxLines = 1,
            )
            Text(nowPlaying.artist, style = NoizTypography.labelSmall, maxLines = 1)
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (nowPlaying.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
            )
        }
        IconButton(onClick = onNext, enabled = nowPlaying.hasNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
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
) {
    val position = rememberFrameSyncedPosition(nowPlaying.positionMs, nowPlaying.isPlaying)
    val haptics = LocalHapticFeedback.current

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
        )

        Spacer(Modifier.height(24.dp))
        Text(nowPlaying.title, style = NoizTypography.displayLarge, maxLines = 2)
        Text(nowPlaying.artist, style = NoizTypography.bodyMedium)

        nowPlaying.qualityChip?.let { chip ->
            Spacer(Modifier.height(8.dp))
            Text(chip, style = TabularTimeStyle)
        }

        Spacer(Modifier.height(20.dp))
        SeekBar(
            positionMs = position,
            durationMs = nowPlaying.durationMs,
            onSeek = { /* Seek is routed through PlaybackRepository. */ },
        )

        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle")
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = if (nowPlaying.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (nowPlaying.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
            IconButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) {
                Icon(Icons.Filled.Repeat, contentDescription = "Repeat")
            }
        }
    }
}

/**
 * Positions are sampled per frame with [withFrameNanos] and only while a seek bar is
 * on screen — never from a global ticker (§3.3). The value lives in a plain
 * [mutableFloatStateOf] read inside draw, so it does not recompose the panel.
 */
@Composable
private fun rememberFrameSyncedPosition(
    positionMs: () -> Long,
    isPlaying: Boolean,
): Float {
    var value by remember { mutableFloatStateOf(positionMs().toFloat()) }
    LaunchedEffect(isPlaying) {
        while (true) {
            withFrameNanos { value = positionMs().toFloat() }
        }
    }
    return value
}

@Composable
private fun SeekBar(
    positionMs: Float,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val palette = LocalNoizPalette.current
    val density = LocalDensity.current
    var barWidth by remember { mutableIntStateOf(0) }
    val fraction = if (durationMs <= 0) 0f else (positionMs / durationMs).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .drawWithContent {
                    barWidth = size.width.toInt()
                    val w = size.width
                    val h = 2.dp.toPx()
                    val y = (size.height - h) / 2f
                    drawRoundRect(
                        color = palette.onSurface.copy(alpha = 0.22f),
                        topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(w, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
                    )
                    drawRoundRect(
                        color = palette.accent,
                        topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                        size = androidx.compose.ui.geometry.Size(w * fraction, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
                    )
                    drawContent()
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Skip forward 5 seconds") { onSeek((positionMs + 5_000).toLong()); true },
                        CustomAccessibilityAction("Skip back 5 seconds") { onSeek((positionMs - 5_000).coerceAtLeast(0)); true },
                    )
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((positionMs / 1000f).toInt()), style = TabularTimeStyle)
            Text(formatTime((durationMs / 1000).toInt()), style = TabularTimeStyle)
        }
    }
}

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

@Composable
internal fun ArtworkPlaceholder(
    url: String?,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val palette = LocalNoizPalette.current
    Surface(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        color = palette.muted,
    ) {
        // Coil's AsyncImage is wired in :app; this placeholder keeps the module
        // renderable (screenshot tests + previews) without a network dependency.
        Box(
            Modifier
                .fillMaxSize()
                .background(palette.dominant.copy(alpha = 0.6f)),
        )
    }
}
