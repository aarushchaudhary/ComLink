package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SessionCipher(
    private val myPrivateKey: ByteArray,
    private val myPublicKey: ByteArray,
    private val peerPublicKey: ByteArray
) {

    private val sessionKey: ByteArray
    private val sessionSalt: ByteArray
    private val myDirectionByte: Byte
    private val peerDirectionByte: Byte

    init {
        // 1. ECDH: Compute Shared Secret using Google Tink's X25519
        val sharedSecret = X25519.computeSharedSecret(myPrivateKey, peerPublicKey)
        
        // 2. HKDF: Derive Session Key and Salt
        // We derive 40 bytes: 32 for AES-256-GCM key, 8 for the shared nonce salt
        val keyMaterial = Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            null,
            "ComLinkSession".toByteArray(Charsets.UTF_8),
            40
        )
        
        sessionKey = keyMaterial.copyOfRange(0, 32)
        sessionSalt = keyMaterial.copyOfRange(32, 40)
        
        // 3. Direction Byte Assignment to prevent Nonce Reuse
        // To guarantee nonces never collide, we assign direction bytes based on lexicographical order of public keys.
        val isMyKeyFirst = compareByteArrays(myPublicKey, peerPublicKey) <= 0
        if (isMyKeyFirst) {
            myDirectionByte = 0x01
            peerDirectionByte = 0x02
        } else {
            myDirectionByte = 0x02
            peerDirectionByte = 0x01
        }
    }

    companion object {
        /**
         * The envelope header fields bound to the ciphertext as AES-GCM AAD (additional authenticated
         * data): changing any of them makes decryption fail. `ttl` is left out because relays change it.
         * Each string is length-prefixed, so field boundaries can't be shifted.
         */
        fun envelopeAad(
            senderId: String,
            recipientId: String,
            envelopeId: String,
            contentType: Int,
            timestamp: Long
        ): ByteArray {
            val fields = listOf(senderId, recipientId, envelopeId).map { it.toByteArray(Charsets.UTF_8) }
            val buffer = ByteBuffer.allocate(AAD_LABEL.size + fields.sumOf { 4 + it.size } + 4 + 8)
            buffer.put(AAD_LABEL)
            fields.forEach { buffer.putInt(it.size).put(it) }
            buffer.putInt(contentType).putLong(timestamp)
            return buffer.array()
        }

        private val AAD_LABEL = "ComLink-envelope-v1".toByteArray(Charsets.UTF_8)

        /**
         * Compares byte arrays as unsigned bytes, then by length. Both phones use it to order their
         * two public keys the same way, which gives each side a different nonce direction byte.
         */
        internal fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val aByte = a[i].toInt() and 0xFF
                val bByte = b[i].toInt() and 0xFF
                if (aByte != bByte) return aByte.compareTo(bByte)
            }
            return a.size.compareTo(b.size)
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a strict 96-bit deterministic nonce.
     * @param plaintext The message to encrypt
     * @param counter A 24-bit (3-byte) strictly monotonically increasing counter for this session
     * @param aad The envelope header from [envelopeAad]; the receiver must pass the same bytes
     */
    fun encrypt(plaintext: ByteArray, counter: Int, aad: ByteArray): ByteArray {
        val nonce = constructNonce(counter)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce) // 128-bit authentication tag
        val keySpec = SecretKeySpec(sessionKey, "AES")

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts ciphertext using AES-256-GCM.
     * @param ciphertext The encrypted payload
     * @param nonce The 12-byte (96-bit) nonce received over the network
     * @param aad The received envelope's header from [envelopeAad]; any change fails the tag check
     * @return A Pair containing the decrypted plaintext and the extracted counter
     */
    fun decrypt(ciphertext: ByteArray, nonce: ByteArray, aad: ByteArray): Pair<ByteArray, Int> {
        if (nonce.size != 12) {
            throw IllegalArgumentException("Nonce must be exactly 12 bytes")
        }
        
        // Validate Session Salt (first 8 bytes)
        for (i in 0..7) {
            if (nonce[i] != sessionSalt[i]) throw SecurityException("Invalid session salt in nonce")
        }
        
        // Validate Direction Byte (9th byte)
        if (nonce[8] != peerDirectionByte) {
            throw SecurityException("Invalid direction byte in nonce")
        }
        
        // Extract Counter (last 3 bytes)
        val counter = ((nonce[9].toInt() and 0xFF) shl 16) or
                      ((nonce[10].toInt() and 0xFF) shl 8) or
                      (nonce[11].toInt() and 0xFF)
        
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        val keySpec = SecretKeySpec(sessionKey, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        cipher.updateAAD(aad)
        return Pair(cipher.doFinal(ciphertext), counter)
    }

    /**
     * Constructs the strict 96-bit (12-byte) deterministic nonce.
     * Format: [8 bytes Session Salt] + [1 byte Direction] + [3 bytes Counter]
     */
    fun constructNonce(counter: Int): ByteArray {
        val buffer = ByteBuffer.allocate(12)
        buffer.put(sessionSalt) // 8 bytes
        buffer.put(myDirectionByte) // 1 byte
        
        // 3 bytes counter (extract lower 24 bits)
        buffer.put((counter shr 16 and 0xFF).toByte())
        buffer.put((counter shr 8 and 0xFF).toByte())
        buffer.put((counter and 0xFF).toByte())

        return buffer.array()
    }
}
