package com.noiz.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

class LexoRankTest {

    @Test
    fun `keys never end with the first digit`() {
        val random = Random(7)
        val keys = randomKeys(random, 500)

        keys.forEach { key ->
            assertTrue(key.last() != LexoRank.DIGITS.first(), "key '$key' must not end in '${LexoRank.DIGITS.first()}'")
        }
    }

    @Test
    fun `inserting between every pair keeps the list sorted`() {
        val random = Random(11)
        val keys = mutableListOf(LexoRank.first())

        repeat(400) {
            val position = random.nextInt(keys.size + 1)
            val lower = keys.getOrNull(position - 1)
            val upper = keys.getOrNull(position)
            val key = LexoRank.between(lower, upper)

            lower?.let { assertTrue(it < key, "'$key' must sort after '$it'") }
            upper?.let { assertTrue(key < it, "'$key' must sort before '$it'") }

            keys.add(position, key)
        }

        assertEquals(keys, keys.sorted(), "byte order must equal list order")
        assertEquals(keys.size, keys.toSet().size, "keys must be unique")
    }

    @Test
    fun `open ends work for first and last inserts`() {
        val head = LexoRank.between(null, "0")
        assertTrue(head < "0")

        val tail = LexoRank.between("z", null)
        assertTrue(tail > "z")
    }

    @ParameterizedTest
    @CsvSource(
        "a, b",
        "a0, a1",
        "aZ, b",
        "0a, 0b",
        "a0, a0b",
        "V, VV",
        "a, az",
        "z0, z0a",
    )
    fun `known bounds produce an in-between key`(lower: String, upper: String) {
        val key = LexoRank.between(lower, upper)
        assertTrue(lower < key, "expected $key > $lower")
        assertTrue(key < upper, "expected $key < $upper")
        assertTrue(key.last() != LexoRank.DIGITS.first(), "keys never end in the first digit")
    }

    /**
     * `"a0"` as an upper bound is unreachable: no generated key ends in `0`, and
     * nothing sorts strictly between `"a"` and `"a0"`. The caller contract rejects it
     * instead of returning a key that would break ordering.
     */
    @Test
    fun `a bound that leaves no room is rejected`() {
        assertThrows<IllegalArgumentException> { LexoRank.between("a", "a0") }
    }

    @Test
    fun `inverted bounds are rejected`() {
        assertThrows<IllegalArgumentException> { LexoRank.between("b", "a") }
        assertThrows<IllegalArgumentException> { LexoRank.between("a", "a") }
    }

    @Test
    fun `spread yields evenly ordered keys`() {
        val keys = LexoRank.spread("a", "z", 5)

        assertEquals(5, keys.size)
        assertEquals(keys, keys.sorted())
        assertTrue(keys.first() > "a")
        assertTrue(keys.last() < "z")
        keys.zipWithNext().forEach { (left, right) -> assertTrue(left < right) }
    }

    private fun randomKeys(random: Random, count: Int): List<String> {
        val keys = mutableListOf(LexoRank.first())
        repeat(count) {
            val position = random.nextInt(keys.size + 1)
            keys.add(
                position,
                LexoRank.between(keys.getOrNull(position - 1), keys.getOrNull(position)),
            )
        }
        return keys
    }
}
