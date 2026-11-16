package com.aarushchaudhary.comlink.crypto

import com.aarushchaudhary.comlink.proto.ContactPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ContactQrTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val keyB64 = Base64.getEncoder().encodeToString(key)
    private val deviceId = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })

    private fun currentQr(id: String = deviceId, k: String = keyB64, name: String = "Bob") =
        Base64.getEncoder().encodeToString(
            ContactPayload.ADAPTER.encode(ContactPayload(device_id = id, public_key = k, display_name = name))
        )

    @Test
    fun parsesCurrentFormat() {
        val c = ContactQr.parse(currentQr())!!
        assertEquals(deviceId, c.deviceId)
        assertEquals("Bob", c.displayName)
        assertArrayEquals(key, c.publicKey)
    }

    @Test
    fun parsesOldFormat() {
        val c = ContactQr.parse("$deviceId:$keyB64")!!
        assertEquals(deviceId, c.deviceId)
        assertEquals("", c.displayName)
        assertArrayEquals(key, c.publicKey)
    }

    @Test
    fun bothFormatsGiveTheSameKeyForTheFingerprint() {
        // The scan dialog passes the whole QR for the current format,
        // and only the part after ':' for the old one
        val fromCurrent = ContactQr.publicKeyFrom(currentQr())
        val fromOldKeyPart = ContactQr.publicKeyFrom("$deviceId:$keyB64".substringAfter(":"))
        val fromOldWhole = ContactQr.publicKeyFrom("$deviceId:$keyB64")
        assertArrayEquals(key, fromCurrent)
        assertArrayEquals(key, fromOldKeyPart)
        assertArrayEquals(key, fromOldWhole)
    }

    @Test
    fun junkIsRejected() {
        assertNull(ContactQr.parse("https://example.com/page"))      // URLs used to become contacts
        assertNull(ContactQr.parse("hello world"))
        assertNull(ContactQr.parse(""))
        assertNull(ContactQr.publicKeyFrom("https://example.com/page"))
        assertNull(ContactQr.publicKeyFrom("not base64 !!"))
    }

    @Test
    fun wrongKeySizeIsRejected() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(31))
        val longKey = Base64.getEncoder().encodeToString(ByteArray(33))
        assertNull(ContactQr.parse(currentQr(k = shortKey)))
        assertNull(ContactQr.parse("$deviceId:$longKey"))
        assertNull(ContactQr.publicKeyFrom(shortKey))
    }

    @Test
    fun badDeviceIdsAreRejected() {
        assertNull(ContactQr.parse(currentQr(id = "__SAVED__")))   // the local Saved Messages chat
        assertNull(ContactQr.parse(currentQr(id = "")))
        assertNull(ContactQr.parse(currentQr(id = "../../x")))
        assertNotNull(ContactQr.parse(currentQr()))
    }
}
