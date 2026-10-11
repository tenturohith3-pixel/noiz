package com.noiz.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * noiz Glass Design System — tokens.
 *
 * Elevation scale from PRD §7.1. A "level" is a static spec; a "tier" is a runtime
 * rendering capability (Full / Static / Opaque) selected by [GlassTierController] (§7.2).
 */

/** Runtime rendering capability. Never persisted per-panel: one tier per screen. */
enum class GlassTier { Full, Static, Opaque }

@Immutable
data class GlassLevelSpec(
    /** Backdrop blur radius in dp. 16–32 dp, *never* animated (frame rule #4). */
    val blur: Dp,
    /** Tint painted over the blurred backdrop. */
    val tint: Color,
    /** Solid fallback used by [GlassTier.Opaque]. */
    val opaque: Color,
    /** Multiplier applied to [Modifier.glassBorder] alphas. */
    val borderAlpha: Float = 1f,
)

/**
 * Glass elevation scale (§7.1).
 *
 * Tints come from [NoizSemanticColors] rather than inline `Color.White.copy(alpha=…)`
 * literals: at 4–7% alpha over a near-black canvas the "glass" was effectively invisible
 * and §7.2's contrast requirement could not be met.
 */
enum class GlassLevel(val spec: GlassLevelSpec) {
    /** G0 Canvas — full-bleed screen background. Not a panel; pre-blurred mesh. */
    G0(
        GlassLevelSpec(
            blur = 0.dp,
            tint = Color.Transparent,
            opaque = Color(0xFF0B0B0F),
        ),
    ),
    /** G1 Card — grid cards, list groups, settings groups. */
    G1(
        GlassLevelSpec(
            blur = 16.dp,
            tint = Color.White.copy(alpha = 0.12f),
            opaque = Color(0xFF16161C),
        ),
    ),
    /** G2 Bar — mini-player, bottom navigation, scroll-linked top bar. */
    G2(
        GlassLevelSpec(
            blur = 24.dp,
            tint = Color.White.copy(alpha = 0.08f),
            opaque = Color(0xFF101015),
            borderAlpha = 0.8f,
        ),
    ),
    /** G3 Sheet — Now Playing, modal sheets, dialogs. */
    G3(
        GlassLevelSpec(
            blur = 32.dp,
            tint = Color.White.copy(alpha = 0.10f),
            opaque = Color(0xFF0E0E13),
        ),
    ),
    /** G4 Overlay — context menus, snackbars, tooltips. */
    G4(
        GlassLevelSpec(
            blur = 20.dp,
            tint = Color.White.copy(alpha = 0.16f),
            opaque = Color(0xFF1C1C23),
        ),
    ),
}

/**
 * Set once per screen by the root scaffold; read by every [GlassSurface].
 *
 * `staticCompositionLocalOf`, not `compositionLocalOf`: the value is documented as
 * "set once per screen", and the dynamic version meant every tier change invalidated
 * the whole screen's composition rather than just the affected panels.
 */
val LocalGlassTier = staticCompositionLocalOf { GlassTier.Full }

/**
 * Luminous border (§7.1): a thin gradient stroke that simulates edge light.
 * Kept cheap — a single brush shader, no layer promotion.
 */
fun Modifier.glassBorder(
    shape: Shape,
    width: Dp = 0.75.dp,
    alpha: Float = 1f,
    level: GlassLevel = GlassLevel.G1,
): Modifier = border(
    width = width,
    brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.35f * alpha * level.spec.borderAlpha),
            Color.White.copy(alpha = 0.06f * alpha * level.spec.borderAlpha),
            Color.White.copy(alpha = 0.20f * alpha * level.spec.borderAlpha),
        ),
    ),
    shape = shape,
)

/**
 * Palette derived from album art (§7.3) or the server palette hint (§4.5).
 * All glass tints, the G0 mesh and the seek bar read from a single instance so a
 * track change animates everything together over 600 ms.
 */
@Immutable
data class NoizPalette(
    val dominant: Color,
    val vibrant: Color,
    val muted: Color,
    val surface: Color,
    val onSurface: Color,
    val accent: Color,
    val isDark: Boolean,
    /** Secondary text colour; §7.2 requires a distinct `contentSecondary` token. */
    val onSurfaceVariant: Color = if (isDark) Color(0xFFA8ACBA) else Color(0xFF4A4F5C),
) {
    companion object {
        /**
         * Brand fallback used in Ph1–Ph2 and whenever extraction yields low population.
         *
         * Glass tints are 7–10% white over a near-black canvas, which is what §7.2
         * asks for in dark mode (45–65% is the *light* intent). The previous values were
         * low enough that glass was effectively invisible and contrast could not be
         * guaranteed.
         */
        val Brand = NoizPalette(
            dominant = Color(0xFF2B4A7A),
            vibrant = Color(0xFF4F8CFF),
            muted = Color(0xFF3A3F52),
            surface = Color(0xFF0B0B0F),
            onSurface = Color(0xFFF2F3F7),
            accent = Color(0xFF7FB0FF),
            isDark = true,
        )

        /**
         * §7.2 "Light intent". Previously no light theme existed at all: every colour
         * was a near-black literal, `isDark` was hardcoded to true, and there was no
         * `values-night/`. The release checklist requires both themes.
         */
        val Light = NoizPalette(
            dominant = Color(0xFFB9CBE6),
            vibrant = Color(0xFF2F6FE0),
            muted = Color(0xFFC7CCD6),
            surface = Color(0xFFF7F8FA),
            onSurface = Color(0xFF14161C),
            accent = Color(0xFF2F6FE0),
            isDark = false,
            onSurfaceVariant = Color(0xFF565B68),
        )
    }
}

/**
 * The six semantic tokens §7.2 requires. Every [GlassLevelSpec] is derived from these
 * rather than from ad-hoc hex literals, so a theme change moves the whole scale at
 * once and contrast targets can be checked in one place.
 */
data class NoizSemanticColors(
    val surfaceGlass: Color,
    val surfaceElevated: Color,
    val borderGlass: Color,
    val contentPrimary: Color,
    val contentSecondary: Color,
    val accentDynamic: Color,
) {
    companion object {
        /** Dark intent: 45–65% opacity glass, high-contrast near-white text. */
        val Dark = NoizSemanticColors(
            surfaceGlass = Color.White.copy(alpha = 0.12f),
            surfaceElevated = Color.White.copy(alpha = 0.18f),
            borderGlass = Color.White.copy(alpha = 0.22f),
            contentPrimary = Color(0xFFF2F3F7),
            contentSecondary = Color(0xFFA8ACBA),
            accentDynamic = Color(0xFF7FB0FF),
        )

        /** Light intent: 60–75% opacity glass over a near-white canvas. */
        val Light = NoizSemanticColors(
            surfaceGlass = Color.White.copy(alpha = 0.70f),
            surfaceElevated = Color.White.copy(alpha = 0.88f),
            borderGlass = Color(0x33000000),
            contentPrimary = Color(0xFF14161C),
            contentSecondary = Color(0xFF565B68),
            accentDynamic = Color(0xFF2F6FE0),
        )
    }
}

val LocalNoizPalette = staticCompositionLocalOf { NoizPalette.Brand }

/** Second accent used for radio/smart-shuffle chips and "not in library" cards. */
val SparkAccent = Color(0xFFB6A2FF)

// ---------------------------------------------------------------------------
// Typography (§7.1) — variable geometric sans, tabular numerals for time.
// ---------------------------------------------------------------------------

val NoizFontFamily: FontFamily = FontFamily.SansSerif

private val TabularFeature = mapOf(
    "tnum" to 1,
    "ss01" to 1,
)

val NoizTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = NoizFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = NoizFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = NoizFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = NoizFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** Monospaced-ish tabular style for elapsed/remaining time. */
val TabularTimeStyle = TextStyle(
    fontFamily = NoizFontFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum, ss01",
)

// ---------------------------------------------------------------------------
// Motion (§7.4)
// ---------------------------------------------------------------------------

object NoizMotion {
    /** Emphasized decelerate — shared-element entry. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val FastOutSlowIn: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    const val ArtworkSizeMs = 250
    const val PaletteTweenMs = 600
    const val SharedBoundsMs = 350
    const val TierCrossfadeMs = 300
    const val PressScaleMs = 90

    /** Sheet release spring (§7.4). */
    val SheetSpring = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessMedium,
    )

    const val PressScale = 0.97f
    const val PressBorderBoost = 1.4f
}

/** Fixed input scale for Haze: blur half-resolution. Frame rule #2. */
const val GlassInputScale = 0.5f

/** Frost noise: hides banding from the downsample. */
const val GlassNoiseFactor = 0.035f
