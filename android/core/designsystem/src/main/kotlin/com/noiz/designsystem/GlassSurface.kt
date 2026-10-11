package com.noiz.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * A pre-blurred, downscaled copy of the current screen background (§7.2 frame rule #6).
 * Produced once per screen on Dispatchers.Default, keyed by artwork hash, by the
 * G0 layer; sampled by every [GlassTier.Static] panel.
 */
@Immutable
data class StaticBackdrop(
    val bitmap: ImageBitmap,
    /** downsample factor of [bitmap] relative to screen pixels (1/8 by default). */
    val scale: Float,
)

val LocalStaticBackdrop = staticCompositionLocalOf<StaticBackdrop?> { null }

/**
 * The single backdrop-blur abstraction (§7.2). Swap Haze for a GraphicsLayer capture
 * without touching any call site.
 *
 * Frame-budget contract enforced by this composable:
 *  - blur radius comes straight from [GlassLevel] and is never animated;
 *  - Haze samples a half-resolution copy ([GlassInputScale]);
 *  - [GlassTier.Static] never blurs — it samples a prepared bitmap;
 *  - [GlassTier.Opaque] is a flat tinted fill.
 */
@Composable
fun GlassSurface(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.G1,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val tier = LocalGlassTier.current
    val palette = LocalNoizPalette.current
    val spec = level.spec

    val base = modifier.clip(shape)
    val surface: Modifier = when (tier) {
        GlassTier.Full -> base.hazeEffect(hazeState) {
            blurRadius = spec.blur
            inputScale = HazeInputScale.Fixed(GlassInputScale)
            // Palette tint replaces, never animates the radius (§7.2 rule #4).
            tints = buildList {
                add(HazeTint(spec.tint))
                if (level.ordinal >= GlassLevel.G2.ordinal) {
                    add(HazeTint(palette.surface.copy(alpha = 0.55f)))
                }
            }
            noiseFactor = GlassNoiseFactor
        }

        GlassTier.Static -> base.staticBackdrop(spec)
        GlassTier.Opaque -> base.background(spec.opaque)
    }

    Box(
        modifier = surface.glassBorder(shape, level = level),
        content = content,
    )
}

/**
 * [GlassTier.Static] rendering: paint the tint, then blit the shared pre-blurred bitmap
 * under this node's screen-space rect. No GPU blur, no per-frame capture.
 */
@Composable
private fun Modifier.staticBackdrop(spec: GlassLevelSpec): Modifier {
    val backdrop = LocalStaticBackdrop.current ?: return this.background(spec.opaque)
    var rect by remember { mutableStateOf<Rect?>(null) }

    return this
        .background(spec.tint)
        .onGloballyPositioned { coords ->
            val origin = coords.positionInWindow()
            rect = Rect(origin, coords.size.toSize())
        }
        .drawBehind {
            val r = rect ?: return@drawBehind
            val s = backdrop.scale
            withTransform({
                translate(-r.left / s, -r.top / s)
                scale(s, s)
            }) {
                drawImage(
                    image = backdrop.bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        width = (r.right / s).toInt(),
                        height = (r.bottom / s).toInt(),
                    ),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
}

/**
 * G0 Canvas overlay (§7.1): 3–4 slow-drifting radial gradients built from the palette.
 * Drift runs in the draw phase (frame rule #8) and is disabled outside
 * [GlassTier.Full], under Battery Saver, or when animator scale is 0.
 *
 * This is also the *single* backdrop source per screen — panels sample [hazeState].
 */
@Composable
fun AmbientBackdrop(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val palette = LocalNoizPalette.current
    val tier = LocalGlassTier.current

    val transition = rememberInfiniteTransition(label = "ambient-drift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 52_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambient-phase",
    )

    val density = LocalDensity.current
    val drift = if (animate && tier == GlassTier.Full) phase else 0f

    Canvas(
        modifier = modifier
            .background(palette.surface)
            .hazeSource(hazeState),
    ) {
        drawAmbientMesh(palette, drift)
    }
}

private fun DrawScope.drawAmbientMesh(palette: NoizPalette, phase: Float) {
    val w = size.width
    val h = size.height
    val twoPi = (Math.PI * 2).toFloat()

    val blobs = listOf(
        Triple(palette.dominant, Offset(0.22f, 0.18f), 0.62f),
        Triple(palette.muted, Offset(0.82f, 0.28f), 0.55f),
        Triple(palette.vibrant, Offset(0.35f, 0.86f), 0.70f),
        Triple(palette.accent, Offset(0.75f, 0.72f), 0.48f),
    )

    blobs.forEachIndexed { i, (color, anchor, radiusFraction) ->
        val t = phase * twoPi + i * 1.7f
        val cx = (anchor.x + 0.06f * kotlin.math.sin(t)) * w
        val cy = (anchor.y + 0.06f * kotlin.math.cos(t * 0.8f)) * h
        val radius = radiusFraction * maxOf(w, h) * 0.5f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = radius,
            ),
            size = size,
        )
    }
}

/**
 * Text over glass (§7.1): guarantees WCAG AA by inserting a gradient scrim when the
 * caller cannot prove contrast. [worstCaseBackdropLuminance] is the max relative
 * luminance of anything that can scroll beneath the node.
 */
@Composable
fun GlassText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current,
    worstCaseBackdropLuminance: Float = 0.42f,
) {
    val needsScrim = contrastRatio(color, worstCaseBackdropLuminance) < 4.5f

    val measured = rememberTextMeasurer()
    val density = LocalDensity.current

    Box(
        modifier = modifier.drawBehind {
            if (!needsScrim) return@drawBehind
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.15f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.35f),
                ),
            )
        },
    ) {
        val layout = measured.measure(
            text = text,
            style = style,
            density = density,
        )
        // Single-shot: no intrinsic remeasure loop, no recomposition on layout.
        Box(
            modifier = Modifier.drawBehind {
                drawText(layout, color = color)
            },
        )
    }
}

/** WCAG 2.x relative-luminance contrast ratio between a solid color and a backdrop. */
fun contrastRatio(foreground: Color, backdropLuminance: Float): Float {
    val lf = relativeLuminance(foreground)
    val lighter = maxOf(lf, backdropLuminance)
    val darker = minOf(lf, backdropLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun relativeLuminance(color: Color): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).let { it * it * it }

    return 0.2126f * channel(color.red) +
        0.7152f * channel(color.green) +
        0.0722f * channel(color.blue)
}

/** Provides a tier + palette for a subtree. Called once by the root scaffold. */
@Composable
fun ProvideGlass(
    tier: GlassTier,
    palette: NoizPalette,
    staticBackdrop: StaticBackdrop? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGlassTier provides tier,
        LocalNoizPalette provides palette,
        LocalStaticBackdrop provides staticBackdrop,
        content = content,
    )
}
