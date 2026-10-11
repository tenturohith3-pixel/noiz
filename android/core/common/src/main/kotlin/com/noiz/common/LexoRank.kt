package com.noiz.common

/**
 * Fractional indexing for ordered lists (§8.4 playlist entries).
 *
 * `PlaylistEntryEntity.positionKey` is stored as one of these keys and sorted with
 * SQLite `BINARY` collation, so *plain byte order of the key is the list order*.
 * Reordering therefore rewrites a single row instead of renumbering the playlist —
 * which is exactly what makes two devices editing the same playlist converge without
 * conflicts.
 *
 * Key rules (all four are covered by tests, because the ordering is only correct if
 * they all hold):
 *
 *  1. Keys use [DIGITS], whose byte order is ASCII order, so lexicographic == numeric.
 *  2. A key never ends in the first digit — otherwise no key can be inserted after it
 *     (`"a" < x < "a0"` has no solution).
 *  3. `between(a, b)` always returns `a < k < b`, for every reachable a and b.
 *  4. `between(null, x)` / `between(x, null)` work against the open ends.
 */
object LexoRank {

    /** ASCII-ordered: digits, then upper case, then lower case. */
    const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val FIRST = DIGITS.first()
    private val LAST = DIGITS.last()

    /** Middle digit: leaves room to grow in both directions from a fresh list. */
    private val MID = DIGITS[DIGITS.length / 2]

    /** Key for the first (or only) item of a list. */
    fun first(): String = MID.toString()

    /**
     * A key strictly between [lower] and [upper]. Either bound may be null for the
     * open ends. Result is guaranteed `lower < result < upper` under byte ordering.
     */
    fun between(lower: String?, upper: String?): String {
        val lo = lower.orEmpty()
        val hi = upper
        require(hi == null || lo < hi) {
            "lower key must sort before upper key: '$lo' !< '${hi.orEmpty()}'"
        }
        return betweenOrThrow(lo, hi)
    }

    /** Positions [count] keys evenly between the same bounds. */
    fun spread(lower: String?, upper: String?, count: Int): List<String> {
        require(count > 0) { "count must be positive" }
        var lo = lower
        val result = ArrayList<String>(count)
        repeat(count) {
            // Leave the last key anchored at [upper] by halving the remaining gap.
            val hi = if (result.size == count - 1) upper else null
            val key = if (hi == null && upper != null) {
                // Distribute: pick the midpoint between lo and upper for all but the
                // final item, which takes the tail end of the gap.
                betweenOrThrow(lo.orEmpty(), upper)
            } else {
                betweenOrThrow(lo.orEmpty(), hi)
            }
            result += key
            lo = key
        }
        return result
    }

    private fun betweenOrThrow(lo: String, hi: String?): String {
        // No upper bound: extend downward-free space past the lower key.
        if (hi == null) return lo + MID

        require(lo < hi) {
            "lower key must sort before upper key: '$lo' !< '$hi'"
        }

        // Open lower end: take the digit just below the upper bound, descending into
        // it when the bound starts with the first digit (e.g. "" vs "0a").
        if (lo.isEmpty()) {
            val head = hi.first()
            if (head != FIRST) {
                val candidate = previousDigit(head).toString()
                // Rule 2 forbids a key ending in FIRST, because nothing can be inserted
                // after it. Appending a MID makes the key extensible.
                return if (candidate == FIRST.toString()) candidate + MID else candidate
            }
            // `hi` starts with the first digit, so descend into its tail.
            require(hi.length > 1) {
                "'$hi' is the minimum possible key; nothing can be inserted before it"
            }
            return FIRST + betweenOrThrow("", hi.substring(1))
        }

        // Cheapest move: bump the last digit of the lower key while it stays under
        // the upper bound. Bumping never yields the first digit, so the invariant
        // "keys do not end in FIRST" is preserved.
        if (lo.last() != LAST) {
            val candidate = lo.dropLast(1) + nextDigit(lo.last())
            if (candidate < hi) return candidate
        }

        return descend(lo, hi)
    }

    /**
     * General case: find the first position where the bounds differ and put a key
     * there — either a digit strictly between the two, or a deeper recursion when the
     * digits are adjacent (or when [lo] is a prefix of [hi]).
     */
    private fun descend(lo: String, hi: String): String {
        // lo is a prefix of hi (and lo < hi, so hi has a tail).
        if (hi.startsWith(lo)) {
            return lo + betweenOrThrow("", hi.substring(lo.length))
        }

        val limit = minOf(lo.length, hi.length)
        var i = 0
        while (i < limit && lo[i] == hi[i]) i++

        // At the first difference lo[i] < hi[i] (else lo would not be smaller).
        val loDigit = lo[i]
        val hiDigit = hi[i]
        val loIndex = DIGITS.indexOf(loDigit)
        val hiIndex = DIGITS.indexOf(hiDigit)

        return if (hiIndex - loIndex >= 2) {
            // A digit strictly between the two bounds ends this key.
            lo.take(i) + DIGITS[(loIndex + hiIndex) / 2]
        } else {
            // Adjacent digits: keep lo's digit and recurse on the tails. The upper
            // tail runs out exactly when hi ends here, i.e. the rest is unbounded.
            val prefix = lo.take(i + 1)
            val lowerTail = lo.substring(i + 1)
            val upperTail = if (i + 1 < hi.length) hi.substring(i + 1) else null
            prefix + betweenOrThrow(lowerTail, upperTail)
        }
    }

    private fun nextDigit(c: Char): Char =
        if (c == LAST) LAST else DIGITS[DIGITS.indexOf(c) + 1]

    private fun previousDigit(c: Char): Char {
        val index = DIGITS.indexOf(c)
        require(index > 0) { "no digit before '$c'" }
        return DIGITS[index - 1]
    }
}
