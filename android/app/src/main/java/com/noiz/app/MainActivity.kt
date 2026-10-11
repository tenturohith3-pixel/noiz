package com.noiz.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.noiz.designsystem.GlassTierConstraint
import com.noiz.designsystem.GlassTierController
import com.noiz.designsystem.NoizPalette
import com.noiz.designsystem.NoizTypography
import com.noiz.designsystem.ProvideGlass
import com.noiz.home.HomeScreen
import com.noiz.home.HomeUiState
import dev.chrisbanes.haze.HazeState

/**
 * Root scaffold.
 *
 * One [ProvideGlass] scope, one [HazeState], and a theme that follows the system.
 *
 * The previous version hardcoded `GlassTier.Full`, so the entire tier/thermal/battery/
 * jank system was dead code *and* Android 10/11 devices ran live `RenderEffect` blur —
 * exactly the per-device cost §7.3 rule 3 warns about. It also never wrapped the tree in
 * a `MaterialTheme`, so `MaterialTheme.typography.displayLarge` resolved to the M3
 * default while every other string used [NoizTypography].
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    private val glassController by lazy {
        GlassTierController(applicationContext, lifecycleScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val hazeState = remember { HazeState() }
            val tier by glassController.tier.collectAsStateWithLifecycle()

            ObservePlatformGlassSignals(glassController)

            val isDark = isSystemInDarkTheme()
            val palette = if (isDark) NoizPalette.Brand else NoizPalette.Light
            val colorScheme = if (isDark) {
                dynamicDarkColorScheme(this)
            } else {
                dynamicLightColorScheme(this)
            }

            MaterialTheme(typography = NoizTypography, colorScheme = colorScheme) {
                ProvideGlass(tier = tier, palette = palette) {
                    HomeScreen(
                        state = HomeUiState(),
                        hazeState = hazeState,
                    )
                }
            }
        }
    }
}

/**
 * Pushes battery-saver state into the tier controller.
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

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = publish()
        }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}