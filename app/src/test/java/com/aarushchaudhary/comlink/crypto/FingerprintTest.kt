package com.aarushchaudhary.comlink.crypto

import com.aarushchaudhary.comlink.proto.ContactPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FingerprintTest {

    private val key = ByteArray(32) { (it * 5 + 1).toByte() }
    private val keyB64 = Base64.getEncoder().encodeToString(key)
    private val deviceId = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    @Test
    fun formatIsSixteenGroupsOfFourHex() {
        val fp = Fingerprint.of(key)
        val groups = fp.split("-")
        assertEquals(16, groups.size)
        assertTrue(groups.all { it.length == 4 && it.all { c -> c in "0123456789ABCDEF" } })
    }

    @Test
    fun knownValue() {
        // SHA-256 of 32 zero bytes
        assertEquals(
            "66687AADF862BD776C8FC18B8E9F8E20089714856EE233B3902A591D0D5F2925".chunked(4).joinToString("-"),
            Fingerprint.of(ByteArray(32))
        )
    }

    @Test
    fun scannerAndOwnerSeeTheSameFingerprint() {
        // The owner shows Fingerprint.of(their key) under their QR...
        val shownUnderQr = Fingerprint.of(key)
        // ...and the scanner's dialog derives the key from what it scanned, in either format
        val currentQr = Base64.getEncoder().encodeToString(
            ContactPayload.ADAPTER.encode(ContactPayload(device_id = deviceId, public_key = keyB64, display_name = "Bob"))
        )
        val fromCurrent = Fingerprint.of(ContactQr.publicKeyFrom(currentQr)!!)
        val fromOld = Fingerprint.of(ContactQr.publicKeyFrom("$deviceId:$keyB64".substringAfter(":"))!!)
        assertEquals(shownUnderQr, fromCurrent)
        assertEquals(shownUnderQr, fromOld)
    }

    @Test
    fun differentKeysDiffer() {
        assertNotEquals(Fingerprint.of(key), Fingerprint.of(key.copyOf().also { it[0] = (it[0] + 1).toByte() }))
    }
}
