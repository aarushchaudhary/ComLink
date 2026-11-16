package com.aarushchaudhary.comlink.crypto

import java.security.MessageDigest

/**
 * Fingerprint of one public key: SHA-256, as upper-case hex in groups of 4 ("A1B2-C3D4-…").
 *
 * One key per fingerprint, so each phone can show its own under its QR code, and the person
 * scanning compares it with what their scan dialog shows for the scanned key. No Android imports.
 */
object Fingerprint {
    fun of(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return hash.joinToString("") { "%02X".format(it) }.chunked(4).joinToString("-")
    }
}
