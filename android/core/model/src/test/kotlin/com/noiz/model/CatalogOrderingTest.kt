package com.noiz.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CatalogOrderingTest {

    private val track = Track(
        id = "t1",
        title = "Track",
        artistName = "",
        durationMs = 180_000,
        renditions = listOf(
            Rendition("opus64", codec = "opus", bitrateKbps = 64, sizeBytes = 1, sha256 = "a"),
            Rendition("orig", codec = "flac", bitDepth = 24, sizeBytes = 2, sha256 = "b"),
        ),
    )

    @Test
    fun `missing artist degrades to a usable label`() {
        assertEquals("Unknown artist", track.primaryArtist)
        assertEquals("Björk", track.copy(artistName = "Björk").primaryArtist)
    }

    @Test
    fun `artwork is content addressed`() {
        assertTrue(Artwork.None.isEmpty)
        assertEquals(false, Artwork(hash = "sha256:1", url = "/api/v1/artwork/sha256:1").isEmpty)
    }

    @Test
    fun `queue item defaults to the original rendition`() {
        assertEquals(Rendition.ORIGINAL_ID, QueueItem(track).renditionId)
        assertEquals("opus64", QueueItem(track, "opus64").renditionId)
    }

    @Test
    fun `admin role is derived, not stored twice`() {
        val admin = User("u1", "root", "Root", role = "admin", canUpload = true, canDownload = true)
        val listener = User("u2", "guest", "Guest", role = "listener", canUpload = false, canDownload = true)

        assertTrue(admin.isAdmin)
        assertEquals(false, listener.isAdmin)
    }

    @Test
    fun `session exposes the identifiers sync code needs`() {
        val session = NoizSession(
            serverUrl = "https://noiz.example/",
            accessToken = "a",
            refreshToken = "r",
            expiresAtEpochMs = 1L,
            user = User("u1", "root", "Root", "admin", true, true),
        )

        assertEquals("https://noiz.example", session.serverUrl, "base URLs are normalized")
        assertEquals("u1", session.userId)
        assertEquals("root", session.username)
    }
}
