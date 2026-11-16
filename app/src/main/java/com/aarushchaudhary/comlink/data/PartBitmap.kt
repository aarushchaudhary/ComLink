package com.aarushchaudhary.comlink.data

/**
 * The "which parts do you hold" bitmap carried in FileStatus.received:
 * bit i (byte i / 8, bit i % 8) set means part i is held. 13 bytes cover 100 parts.
 * No Android imports.
 */
object PartBitmap {

    fun encode(partCount: Int, held: Iterable<Int>): ByteArray {
        val bits = ByteArray((partCount + 7) / 8)
        for (i in held) {
            if (i in 0 until partCount) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
        }
        return bits
    }

    fun isHeld(bits: ByteArray, index: Int): Boolean =
        index >= 0 && index / 8 < bits.size && (bits[index / 8].toInt() shr (index % 8)) and 1 == 1

    /** Parts in 0 until [partCount] that [bits] doesn't mark as held (a short bitmap means missing). */
    fun missing(bits: ByteArray, partCount: Int): List<Int> =
        (0 until partCount).filterNot { isHeld(bits, it) }
}
