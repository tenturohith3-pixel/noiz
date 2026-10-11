package com.noiz.common

/**
 * Hybrid Logical Clock (§8.4, §12.5).
 *
 * Every outbox mutation carries one of these strings. Two rules follow from the sync
 * design:
 *
 *  1. **Ordering is lexicographic.** Physical time is zero-padded to a fixed width, so
 *     `ORDER BY hlc` in Room equals causal order — no parsing on the hot path.
 *  2. **Causality survives clock skew.** A value received from the server (or another
 *     device) that is "in the future" pulls the local clock forward instead of being
 *     discarded, so a mutation made *after* observing it always sorts after it.
 *
 * Counter width (5 digits = 99 999) bounds one millisecond's worth of events; the
 * clock simply advances the physical component past it rather than overflowing.
 */
class HlcClock(
    /** Injectable so tests do not sleep. Returns epoch millis. */
    private val now: () -> Long = System::currentTimeMillis,
) {

    private var physical = 0L
    private var counter = 0

    /** Next timestamp, optionally merging a remote observation. */
    @Synchronized
    fun next(remote: String? = null): String {
        val (remotePhysical, remoteCounter) = remote?.let(::parse) ?: (0L to -1)
        val observed = now()

        val maxPhysical = maxOf(observed, remotePhysical)
        when {
            maxPhysical > physical -> {
                physical = maxPhysical
                // When the jump came from a *remote* value landing exactly on our
                // physical component, resetting the counter to 0 would make this event
                // sort BEFORE the value we just observed — the exact causality loss
                // rule 2 promises to prevent. Continue past the remote counter instead.
                counter = if (remotePhysical == maxPhysical && remoteCounter >= 0) {
                    remoteCounter + 1
                } else {
                    0
                }
                if (counter > MAX_COUNTER) {
                    physical += 1
                    counter = 0
                }
            }

            maxPhysical == physical -> {
                // Same millisecond as the latest event: advance the counter past any
                // remote one so this event is strictly newer than what we observed.
                counter = maxOf(counter, maxOf(remoteCounter, -1)) + 1
                if (counter > MAX_COUNTER) {
                    // Burn a millisecond rather than wrap (a wrap would sort backwards).
                    physical += 1
                    counter = 0
                }
            }

            else -> {
                // Local wall clock moved backwards (NTP step). Keep the logical time
                // monotonic and keep counting.
                counter += 1
                if (counter > MAX_COUNTER) {
                    physical += 1
                    counter = 0
                }
            }
        }

        return format(physical, counter)
    }

    /** Local clock state as if we had observed [remote]; used after a server round-trip. */
    @Synchronized
    fun observe(remote: String) {
        next(remote)
    }

    @Synchronized
    fun current(): String = format(physical, counter)

    companion object {
        /** 13 digits covers epoch millis into the year 2286. */
        private const val PHYSICAL_WIDTH = 13
        private const val COUNTER_WIDTH = 5
        private const val MAX_COUNTER = 99_999

        fun format(physical: Long, counter: Int): String =
            physical.toString().padStart(PHYSICAL_WIDTH, '0') +
                ":" +
                counter.toString().padStart(COUNTER_WIDTH, '0')

        fun parse(value: String): Pair<Long, Int> {
            val separator = value.lastIndexOf(':')
            require(separator > 0 && value.length == PHYSICAL_WIDTH + 1 + COUNTER_WIDTH) {
                "Malformed HLC value: $value"
            }
            val physical = value.substring(0, separator).toLongOrNull()
                ?: throw IllegalArgumentException("Malformed HLC value: $value")
            val counter = value.substring(separator + 1).toIntOrNull()
                ?: throw IllegalArgumentException("Malformed HLC value: $value")
            return physical to counter
        }

        /** Lexicographic compare is causal order — this exists for call-site clarity. */
        fun compare(a: String, b: String): Int = a.compareTo(b)
    }
}
