package com.noiz.designsystem

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

/** User-visible appearance preference (Settings → Appearance). */
enum class GlassTierPreference { Auto, Full, Static, Opaque }

/**
 * Heat / power events that force a downgrade, surfaced from platform listeners.
 */
enum class GlassTierConstraint { BatterySaver, ThermalModerate, ThermalSevere, ReduceTransparency, HighContrast }

/**
 * Chooses the glass tier at launch and re-evaluates it live (§7.2).
 *
 *  Full   — API >= 31 and (MEDIA_PERFORMANCE_CLASS >= 31 or measured healthy frames)
 *  Static — > 12% janky frames over a 5 s window, thermal >= MODERATE, or user override
 *  Opaque — Battery Saver, thermal >= SEVERE, "Reduce transparency", "High contrast"
 *
 * Transitions cross-fade over 300 ms in the UI layer and never interrupt playback.
 *
 * The platform signals (battery saver, thermal, reduce-transparency) must be pushed in
 * by the host via [setConstraint]; `:app` wires the corresponding listeners.
 */
class GlassTierController(
    private val appContext: Context,
    private val scope: CoroutineScope,
) {

    private val _tier = MutableStateFlow(GlassTier.Full)
    val tier: StateFlow<GlassTier> = _tier.asStateFlow()

    private val constraints = MutableStateFlow<Set<GlassTierConstraint>>(emptySet())

    /** Set from Settings; persisted by the caller in DataStore. */
    var preference: GlassTierPreference = GlassTierPreference.Auto
        set(value) {
            field = value
            recompute()
        }

    /** True once we have observed a healthy 5 s window on the Full tier. */
    private var measuredHealthy = false
    private var forceStatic = false

    private var windowFrames = 0
    private var windowJankyFrames = 0
    private var windowStartedAtMs = 0L

    /** Written by the JankStats listener; safe to call from the main thread. */
    private val jankEvents = MutableSharedFlow<Boolean>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        observePlatformConstraints()
        jankEvents
            .onStart { emit(false) }
            .onEach(::onFrame)
            .launchIn(scope)
    }

    /**
     * Records one rendered frame. Called from JankStats' `OnFrameListener`.
     * We only feed this while a glass-heavy screen is visible, so idle screens
     * never trip the downgrade.
     */
    fun reportFrame(isJanky: Boolean) {
        jankEvents.tryEmit(isJanky)
    }

    private fun onFrame(isJanky: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (windowStartedAtMs == 0L) windowStartedAtMs = now
        windowFrames++
        if (isJanky) windowJankyFrames++

        if (now - windowStartedAtMs >= JANK_WINDOW_MS) {
            val ratio = if (windowFrames == 0) 0f else windowJankyFrames.toFloat() / windowFrames
            when {
                ratio > JANKY_DOWNGRADE_RATIO -> {
                    measuredHealthy = false
                    forceStatic = true
                }
                ratio < JANKY_RECOVER_RATIO -> {
                    measuredHealthy = true
                    forceStatic = false
                }
            }
            windowFrames = 0
            windowJankyFrames = 0
            windowStartedAtMs = now
            recompute()
        }
    }

    private fun observePlatformConstraints() {
        val power = appContext.getSystemService<PowerManager>()
        if (power != null && power.isPowerSaveMode) {
            constraints.value = constraints.value + GlassTierConstraint.BatterySaver
        }

        // `AccessibilityManager.isHighContrastTextEnabled` was added in API 34. Calling
        // it unguarded throws NoSuchMethodError on anything older — minSdk here is 29,
        // so this crashed on launch on real devices and on the API-34 emulator image
        // the method is present but the class in the boot classpath may not be.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val am = appContext.getSystemService<AccessibilityManager>()
            if (am != null && am.isHighContrastTextEnabled) {
                constraints.value = constraints.value + GlassTierConstraint.HighContrast
            }
        }
        // Thermal listener + BatterySaver receiver are wired in :app so this module
        // stays free of manifest registrations; both call setConstraint(...).
    }

    fun setConstraint(constraint: GlassTierConstraint, active: Boolean) {
        constraints.value = if (active) {
            constraints.value + constraint
        } else {
            constraints.value - constraint
        }
        recompute()
    }

    private fun recompute() {
        val c = constraints.value
        val requested = when {
            preference != GlassTierPreference.Auto -> preference.tierOrNull() ?: resolveAuto(c)
            else -> resolveAuto(c)
        }
        // An explicit "Full" override bypassed hasCapableGpu(), so a persisted
        // preference on Android 10/11 selected live RenderEffect blur — the exact
        // per-device cost §7.3 rule 3 says to avoid. Clamp here rather than at each call
        // site so no panel can opt back in.
        _tier.value = if (requested == GlassTier.Full && !hasCapableGpu()) {
            GlassTier.Static
        } else {
            requested
        }
    }

    private fun resolveAuto(c: Set<GlassTierConstraint>): GlassTier {
        if (GlassTierConstraint.BatterySaver in c) return GlassTier.Opaque
        if (GlassTierConstraint.ThermalSevere in c) return GlassTier.Opaque
        if (GlassTierConstraint.ReduceTransparency in c) return GlassTier.Opaque
        if (GlassTierConstraint.HighContrast in c) return GlassTier.Opaque
        if (forceStatic || GlassTierConstraint.ThermalModerate in c) return GlassTier.Static
        if (!hasCapableGpu()) return GlassTier.Static
        if (!measuredHealthy && !devicePerfClassSuggestsFull()) return GlassTier.Static
        return GlassTier.Full
    }

    private fun hasCapableGpu(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun devicePerfClassSuggestsFull(): Boolean =
        Build.VERSION.SDK_INT >= 34 && Build.VERSION.MEDIA_PERFORMANCE_CLASS >= 31

    private fun GlassTierPreference.tierOrNull(): GlassTier? = when (this) {
        GlassTierPreference.Auto -> null
        GlassTierPreference.Full -> GlassTier.Full
        GlassTierPreference.Static -> GlassTier.Static
        GlassTierPreference.Opaque -> GlassTier.Opaque
    }

    companion object {
        const val JANK_WINDOW_MS = 5_000L
        const val JANKY_DOWNGRADE_RATIO = 0.12f
        const val JANKY_RECOVER_RATIO = 0.04f
    }
}
