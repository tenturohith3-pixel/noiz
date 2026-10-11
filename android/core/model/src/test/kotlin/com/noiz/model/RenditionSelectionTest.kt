package com.noiz.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RenditionSelectionTest {

    private val original = Rendition(
        id = "orig",
        codec = "flac",
        sampleRate = 96_000,
        bitDepth = 24,
        sizeBytes = 81_234_567,
        sha256 = "aa",
    )
    private val high = Rendition("opus256", codec = "opus", bitrateKbps = 256, sizeBytes = 8_000_000, sha256 = "bb")
    private val saver = Rendition("opus64", codec = "opus", bitrateKbps = 64, sizeBytes = 2_000_000, sha256 = "cc")

    private val track = Track(id = "t1", title = "Track", renditions = listOf(saver, high, original))

    @Test
    fun `original profile prefers the untouched source`() {
        assertEquals("orig", track.renditionFor(QualityProfile.ORIGINAL)?.id)
        assertEquals("orig", track.renditionFor(QualityProfile.OFFLINE_LOSSLESS)?.id)
    }

    @Test
    fun `high profile picks the highest derived bitrate`() {
        assertEquals("opus256", track.renditionFor(QualityProfile.HIGH)?.id)
    }

    @Test
    fun `saver profile picks the lowest derived bitrate`() {
        assertEquals("opus64", track.renditionFor(QualityProfile.SAVER)?.id)
    }

    @Test
    fun `falls back to the original when a catalog has only lossless`() {
        val losslessOnly = Track(id = "t2", title = "Track", renditions = listOf(original))
        assertEquals("orig", losslessOnly.renditionFor(QualityProfile.SAVER)?.id)
    }

    @Test
    fun `missing renditions resolve to null`() {
        assertNull(Track(id = "t3", title = "Empty").renditionFor(QualityProfile.HIGH))
    }

    @Test
    fun `only real lossless renditions are labelled lossless`() {
        assertEquals(true, original.isLossless)
        assertEquals(false, high.isLossless)
        assertEquals(false, saver.isLossless)
    }

    @Test
    fun `quality profiles round-trip their wire value`() {
        QualityProfile.entries.forEach { profile ->
            assertEquals(profile, QualityProfile.fromWire(profile.wire))
        }
        assertEquals(QualityProfile.HIGH, QualityProfile.fromWire("something-else"))
        assertEquals(QualityProfile.HIGH, QualityProfile.fromWire(null))
    }
}
