package com.aarushchaudhary.comlink.messaging

import java.nio.ByteBuffer

/**
 * Pads every plaintext up to a fixed size bucket before encryption, so relays can't tell a short
 * text from a read receipt by its size. Format: [4-byte big-endian real length][data][zeros].
 * The padding is inside the encryption, so it is authenticated. No Android imports.
 */
object Padding {
    private const val LENGTH_PREFIX = 4

    /** Padded sizes. The largest (100 KiB) holds a 100 KB file part plus its protobuf fields. */
    val BUCKETS = intArrayOf(256, 1024, 4096, 16 * 1024, 32 * 1024, 64 * 1024, 100 * 1024)

    /** Above the largest bucket (not expected), round up to a multiple of 4 KiB. */
    private const val OVERFLOW_STEP = 4096

    fun paddedSize(dataLength: Int): Int {
        val needed = LENGTH_PREFIX + dataLength
        BUCKETS.firstOrNull { it >= needed }?.let { return it }
        return (needed + OVERFLOW_STEP - 1) / OVERFLOW_STEP * OVERFLOW_STEP
    }

    fun pad(data: ByteArray): ByteArray =
        ByteBuffer.allocate(paddedSize(data.size)).putInt(data.size).put(data).array()

    /** The original bytes, or null when the length prefix doesn't fit (malformed input). */
    fun unpad(padded: ByteArray): ByteArray? {
        if (padded.size < LENGTH_PREFIX) return null
        val length = ByteBuffer.wrap(padded).int
        if (length < 0 || length > padded.size - LENGTH_PREFIX) return null
        return padded.copyOfRange(LENGTH_PREFIX, LENGTH_PREFIX + length)
    }
}
