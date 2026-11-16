package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place where outgoing envelopes are encrypted and built.
 *
 * Every counter is taken and saved under the contact's lock, so two sends can never
 * use the same counter, and with it the same AES-GCM nonce.
 *
 * Storage, ciphers and transport are passed in as functions, so this class has no Android
 * dependencies and can be unit-tested.
 */
class EncryptedSender(
    private val localDeviceId: String,
    /** The next unused counter for this contact, or null when there's no session. */
    private val loadCounter: suspend (peerId: String) -> Int?,
    /** Persists the next unused counter for this contact. */
    private val saveCounter: suspend (peerId: String, nextCounter: Int) -> Unit,
    private val cipherFor: suspend (peerId: String) -> SessionCipher?,
    /** Hands the envelope to the mesh; [direct] targets the peer's own connection. */
    private val transmit: suspend (envelope: Envelope, direct: Boolean, noResponse: Boolean) -> Boolean
) {
    companion object {
        const val DEFAULT_TTL = 10
    }

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The per-contact lock. Not re-entrant: never call [send] or [reserveCounters] while holding it. */
    fun lockFor(peerId: String): Mutex = locks.getOrPut(peerId) { Mutex() }

    /**
     * Encrypts [plaintext] for [peerId] and sends it. The counter is saved before sending,
     * so even a crash mid-send never reuses it. Returns false when there's no session
     * or the mesh didn't accept the envelope.
     */
    suspend fun send(
        peerId: String,
        plaintext: ByteArray,
        contentType: Int = 0,
        envelopeId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        direct: Boolean = false,
        noResponse: Boolean = false
    ): Boolean = lockFor(peerId).withLock {
        val counter = loadCounter(peerId) ?: return@withLock false
        val cipher = cipherFor(peerId) ?: return@withLock false
        saveCounter(peerId, counter + 1)
        // Sent while holding the lock, so envelopes leave in counter order
        transmit(seal(peerId, cipher, plaintext, counter, contentType, envelopeId, timestamp), direct, noResponse)
    }

    /**
     * Reserves [count] consecutive counters in one lock acquisition (used for file parts).
     * Returns null when there's no session.
     */
    suspend fun reserveCounters(peerId: String, count: Int): IntArray? = lockFor(peerId).withLock {
        val start = loadCounter(peerId) ?: return@withLock null
        saveCounter(peerId, start + count)
        IntArray(count) { start + it }
    }

    /** Sends with a counter that [reserveCounters] already handed out. No lock needed. */
    suspend fun sendReserved(
        peerId: String,
        plaintext: ByteArray,
        counter: Int,
        contentType: Int,
        direct: Boolean = true,
        noResponse: Boolean = true
    ): Boolean {
        val cipher = cipherFor(peerId) ?: return false
        val envelope = seal(peerId, cipher, plaintext, counter, contentType, UUID.randomUUID().toString(), System.currentTimeMillis())
        return transmit(envelope, direct, noResponse)
    }

    private fun seal(
        peerId: String,
        cipher: SessionCipher,
        plaintext: ByteArray,
        counter: Int,
        contentType: Int,
        envelopeId: String,
        timestamp: Long
    ): Envelope {
        // Header fields are bound to the ciphertext: a relay that changes any of them breaks decryption
        val aad = SessionCipher.envelopeAad(localDeviceId, peerId, envelopeId, contentType, timestamp)
        return Envelope(
            sender_id = localDeviceId,
            recipient_id = peerId,
            ciphertext = cipher.encrypt(plaintext, counter, aad).toByteString(),
            nonce = cipher.constructNonce(counter).toByteString(),
            timestamp = timestamp,
            envelope_id = envelopeId,
            ttl = DEFAULT_TTL,
            content_type = contentType
        )
    }
}
