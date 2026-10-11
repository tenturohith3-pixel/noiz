package com.noiz.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HlcTest {

    @Test
    fun `advances within the same millisecond`() {
        var now = 1_000L
        val clock = HlcClock { now }

        val first = clock.next()
        val second = clock.next()
        val third = clock.next()

        assertTrue(first < second, "$first must sort before $second")
        assertTrue(second < third, "$second must sort before $third")
        assertEquals(0L to 0, HlcClock.parse(first))
        assertEquals(1_000L to 2, HlcClock.parse(third))
    }

    @Test
    fun `monotonic when the wall clock steps backwards`() {
        val times = ArrayDeque(listOf(5_000L, 5_000L, 1_000L, 1_000L))
        val clock = HlcClock { times.removeFirst() }

        val values = List(4) { clock.next() }

        assertEquals(values, values.sorted(), "HLC values must sort in issue order")
    }

    @Test
    fun `merges a remote observation from the future`() {
        val clock = HlcClock { 1_000L }
        val remote = HlcClock.format(9_000L, 7)

        val afterMerge = clock.next(remote)

        // Physical time jumps to the remote's, and the counter is strictly newer.
        assertEquals(HlcClock.parse(remote).first, HlcClock.parse(afterMerge).first)
        assertTrue(HlcClock.parse(afterMerge).second > 7)
        assertTrue(afterMerge > remote)
    }

    @Test
    fun `a mutation made after observing a remote value sorts after it`() {
        var now = 10_000L
        val local = HlcClock { now }
        val remote = HlcClock { 10_000L }.next()

        val observed = local.next(remote)
        now = 10_001L
        val later = local.next()

        assertTrue(later > observed, "$later must sort after the observed $remote/$observed")
    }

    @Test
    fun `rejects malformed values`() {
        assertThrows<IllegalArgumentException> { HlcClock.parse("not-a-clock") }
        assertThrows<IllegalArgumentException> { HlcClock.parse("123") }
    }

    @Test
    fun `fixed width keeps lexicographic order equal to chronological order`() {
        val clock = HlcClock { 42L }
        val early = clock.next()

        val parsed = HlcClock.parse(early)
        val late = HlcClock.format(parsed.first + 1, parsed.second)

        assertTrue(early < late)
        assertEquals(early.length, late.length)
    }
}
