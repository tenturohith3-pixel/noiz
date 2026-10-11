package com.noiz.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NormalizeBaseUrlTest {

    @Test
    fun `adds https when the user types a bare host`() {
        assertEquals("https://noiz.example/", normalizeBaseUrl("noiz.example"))
        assertEquals("https://192.168.1.4:8000/", normalizeBaseUrl("192.168.1.4:8000"))
    }

    @Test
    fun `keeps an explicit https scheme and strips the trailing slash`() {
        assertEquals("https://noiz.example/", normalizeBaseUrl("https://noiz.example/"))
        assertEquals("https://noiz.example/", normalizeBaseUrl("https://noiz.example"))
        assertEquals("https://noiz.example/base/", normalizeBaseUrl("https://noiz.example/base/"))
    }

    @Test
    fun `allows plain http for a LAN deployment behind a private CA`() {
        assertEquals("http://nas.local:8000/", normalizeBaseUrl("http://nas.local:8000"))
    }

    @Test
    fun `rejects empty or nonsense input`() {
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("ftp://noiz.example"))
    }

    @Test
    fun `trims accidental whitespace from copy-paste`() {
        assertEquals("https://noiz.example/", normalizeBaseUrl("  https://noiz.example  "))
    }
}
