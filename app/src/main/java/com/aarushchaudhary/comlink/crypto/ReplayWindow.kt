package com.aarushchaudhary.comlink.crypto

/**
 * Sliding-window replay protection for one contact's incoming counters.
 *
 * Accepts a counter if it is above the highest seen, or within the last [SIZE] counters and
 * not seen yet. Rejects anything older than the window or already seen. This lets messages
 * that arrive out of order through the mesh in, while still blocking every replay.
 *
 * Bit i of the window means "counter (highest - i) was seen". No Android imports.
 */
class ReplayWindow private constructor(
    highest: Int,
    private val bits: ByteArray
) {
    companion object {
        const val SIZE = 256
        const val BYTES = SIZE / 8

        /**
         * Restores a window from storage. [stored] is null for sessions saved before the window
         * existed: then everything up to [highest] counts as seen, which is exactly the old
         * "strictly increasing" rule, so no replay can slip in during the upgrade.
         */
        fun restore(highest: Int, stored: ByteArray?): ReplayWindow = when {
            highest < 0 -> ReplayWindow(-1, ByteArray(BYTES))
            stored == null || stored.size != BYTES -> ReplayWindow(highest, ByteArray(BYTES) { 0xFF.toByte() })
            else -> ReplayWindow(highest, stored.copyOf())
        }
    }

    /** Highest counter accepted so far, or -1 when none yet. */
    var highest: Int = highest
        private set

    /** Records [counter] if it is new and inside the window. Returns false for a replay or a too-old counter. */
    fun accept(counter: Int): Boolean {
        if (counter < 0) return false
        if (counter > highest) {
            shiftUp(if (highest < 0) SIZE else counter - highest)
            highest = counter
            setBit(0)
            return true
        }
        val age = highest - counter
        if (age >= SIZE || isSet(age)) return false
        setBit(age)
        return true
    }

    /** A copy of the window bits, for saving. */
    fun toBytes(): ByteArray = bits.copyOf()

    private fun isSet(i: Int) = (bits[i / 8].toInt() shr (i % 8)) and 1 == 1

    private fun setBit(i: Int) {
        bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
    }

    /** Moves every mark [by] places older; marks that fall past the window are dropped. */
    private fun shiftUp(by: Int) {
        if (by >= SIZE) {
            bits.fill(0)
            return
        }
        for (i in SIZE - 1 downTo 0) {
            val from = i - by
            val set = from >= 0 && isSet(from)
            val mask = 1 shl (i % 8)
            bits[i / 8] = if (set) (bits[i / 8].toInt() or mask).toByte() else (bits[i / 8].toInt() and mask.inv()).toByte()
        }
    }
}
