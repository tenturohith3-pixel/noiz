package com.noiz.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FormattingTest {

    @Test
    fun `formats track durations`() {
        assertEquals("0:05", formatDuration(5))
        assertEquals("3:04", formatDuration(184))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("0:00", formatDuration(-1))
    }

    @Test
    fun `formats byte counts`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("4.2 GB", formatBytes(4_546_425_487))
        assertEquals("100 MB", formatBytes(104_857_600))
    }
}
