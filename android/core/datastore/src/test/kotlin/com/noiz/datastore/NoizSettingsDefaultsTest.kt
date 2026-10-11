package com.noiz.datastore

import com.noiz.model.QualityProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Defaults are part of the contract: a fresh install must be correct before the user
 * ever opens Settings (§6.1, §7.2).
 */
class NoizSettingsDefaultsTest {

    @Test
    fun `fresh install is gapless, normalizing and privacy preserving`() {
        val defaults = NoizSettings()

        assertEquals(0L, defaults.crossfadeMs, "gapless is the default path (§6.2)")
        assertEquals(true, defaults.normalizationEnabled)
        assertEquals(-14f, defaults.targetLufs)
        assertEquals(false, defaults.pureMode)
        assertEquals(QualityProfile.HIGH, defaults.qualityProfile)
        assertEquals("auto", defaults.glassTier)
        assertEquals(false, defaults.reduceTransparency)
        assertEquals(false, defaults.privateHistory, "listening history stays local until opted in")
        assertEquals(true, defaults.spokenWordCrossfadeOff)
    }

    @Test
    fun `crossfade bounds match the PRD range`() {
        assertEquals(12_000L, SettingsStore.MAX_CROSSFADE_MS)
    }
}
