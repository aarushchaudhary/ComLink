package com.aarushchaudhary.comlink.data

import java.util.UUID

/**
 * Checks on file-transfer fields that come from the other phone. Those fields end up in file
 * paths and database rows, so anything unexpected is rejected. No Android imports.
 */
object TransferChecks {
    const val PART_SIZE = 100_000               // ~100 KB per part (fits in one BLE packet set)
    const val MAX_FILE_SIZE = 10_000_000L       // 10 MB
    const val MAX_PARTS = ((MAX_FILE_SIZE + PART_SIZE - 1) / PART_SIZE).toInt()  // 100
    const val MAX_THUMBNAIL_BYTES = 16 * 1024

    /** True only for a UUID in the exact form `UUID.toString()` produces, e.g. no `../` tricks. */
    fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (e: IllegalArgumentException) {
        false
    }

    /** The part count a sender computes for [fileSize] bytes. */
    fun expectedPartCount(fileSize: Long): Int =
        ((fileSize + PART_SIZE - 1) / PART_SIZE).toInt().coerceAtLeast(1)

    fun isValidHeader(transferId: String, messageId: String, fileSize: Long, partCount: Int): Boolean =
        isCanonicalUuid(transferId) &&
            isCanonicalUuid(messageId) &&
            fileSize in 0..MAX_FILE_SIZE &&
            partCount in 1..MAX_PARTS &&
            partCount == expectedPartCount(fileSize)

    fun isValidPart(transferId: String, partIndex: Int, partCount: Int, dataSize: Int): Boolean =
        isCanonicalUuid(transferId) &&
            partIndex in 0 until partCount &&
            dataSize in 1..PART_SIZE
}
