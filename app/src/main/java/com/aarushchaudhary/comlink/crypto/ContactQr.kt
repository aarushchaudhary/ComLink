package com.aarushchaudhary.comlink.crypto

import com.aarushchaudhary.comlink.proto.ContactPayload
import java.util.Base64

/**
 * Reads the contact QR code. Two formats exist:
 *  - current: base64 of a [ContactPayload] protobuf (device ID, public key, display name)
 *  - old:     "deviceId:publicKeyBase64", from earlier app versions
 *
 * Anything else, or a key that isn't exactly 32 bytes (X25519), is rejected, so a random QR
 * (a URL, say) never becomes a contact. Uses java.util.Base64 (API 26+), no Android imports.
 */
object ContactQr {

    const val KEY_SIZE = 32
    private const val RESERVED_ID = "__SAVED__" // the local Saved Messages pseudo-contact
    private val DEVICE_ID = Regex("^[A-Za-z0-9+/=_-]{8,64}$")

    class Contact(
        val deviceId: String,
        val publicKeyBase64: String,
        val publicKey: ByteArray,
        val displayName: String
    )

    /** Parses a whole scanned QR text, in either format. */
    fun parse(qr: String): Contact? {
        val text = qr.trim()
        if (':' in text) {
            val (deviceId, keyB64) = text.split(':', limit = 2)
            return contactOrNull(deviceId, keyB64, "")
        }
        val payload = try {
            ContactPayload.ADAPTER.decode(Base64.getDecoder().decode(text))
        } catch (e: Exception) {
            return null
        }
        return contactOrNull(payload.device_id, payload.public_key, payload.display_name)
    }

    /**
     * The public key in [text], which is either a whole QR (either format) or, as the scan dialog
     * passes for the old format, just the key part. Null when there's no valid key.
     */
    fun publicKeyFrom(text: String): ByteArray? {
        decodeKey(text.trim())?.let { return it }
        return parse(text)?.publicKey
    }

    private fun contactOrNull(deviceId: String, keyB64: String, displayName: String): Contact? {
        if (!DEVICE_ID.matches(deviceId) || deviceId == RESERVED_ID) return null
        val key = decodeKey(keyB64) ?: return null
        return Contact(deviceId, keyB64, key, displayName)
    }

    private fun decodeKey(b64: String): ByteArray? = try {
        Base64.getDecoder().decode(b64).takeIf { it.size == KEY_SIZE }
    } catch (e: IllegalArgumentException) {
        null
    }
}
