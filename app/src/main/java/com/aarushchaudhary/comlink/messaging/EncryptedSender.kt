package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
    /** The next unused counter for this contact, or null when there's no session. */
    private val loadCounter: suspend (peerId: String) -> Int?,
    /** Persists the next unused counter for this contact. */
    private val saveCounter: suspend (peerId: String, nextCounter: Int) -> Unit,
    private val cipherFor: suspend (peerId: String) -> SessionCipher?,
    /** Hands the envelope for [peerId] to the mesh; [direct] targets the peer's own connection. */
    private val transmit: suspend (peerId: String, envelope: Envelope, direct: Boolean, noResponse: Boolean) -> Boolean,
    /** Picks the tag window; the send time, not the envelope timestamp (a retry keeps the old one). */
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** Hops a new envelope may make at most (relays also cap at this, see MeshRouter). */
        const val MAX_TTL = 10

        /** Fresh envelopes start at a random 8..10 hops, so the hops left don't reveal the sender's distance. */
        const val MIN_START_TTL = 8

        /**
         * Highest counter ever used. The nonce holds 24 bits (16,777,216 values); stopping a little
         * short means a counter, and with it a nonce, is never reused. Years of normal use.
         */
        const val MAX_COUNTER = 16_700_000

        fun randomStartTtl(): Int = Random.nextInt(MIN_START_TTL, MAX_TTL + 1)
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
        noResponse: Boolean = false,
        ttl: Int = randomStartTtl()
    ): Boolean = lockFor(peerId).withLock {
        val counter = loadCounter(peerId) ?: return@withLock false
        if (counter >= MAX_COUNTER) return@withLock false // never wrap the 24-bit nonce counter
        val cipher = cipherFor(peerId) ?: return@withLock false
        saveCounter(peerId, counter + 1)
        // Sent while holding the lock, so envelopes leave in counter order
        transmit(peerId, seal(cipher, plaintext, counter, contentType, envelopeId, timestamp, ttl), direct, noResponse)
    }

    /**
     * Reserves [count] consecutive counters in one lock acquisition (used for file parts).
     * Returns null when there's no session, or when the counters would pass [MAX_COUNTER].
     */
    suspend fun reserveCounters(peerId: String, count: Int): IntArray? = lockFor(peerId).withLock {
        val start = loadCounter(peerId) ?: return@withLock null
        if (start.toLong() + count > MAX_COUNTER) return@withLock null
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
        val envelope = seal(cipher, plaintext, counter, contentType, UUID.randomUUID().toString(), System.currentTimeMillis(), randomStartTtl())
        return transmit(peerId, envelope, direct, noResponse)
    }

    private fun seal(
        cipher: SessionCipher,
        plaintext: ByteArray,
        counter: Int,
        contentType: Int,
        envelopeId: String,
        timestamp: Long,
        ttl: Int
    ): Envelope {
        // No sender or recipient ID on the wire: only this tag, which only the recipient can match
        val tag = cipher.outgoingTag(SessionCipher.tagWindow(clock()))
        // Header fields are bound to the ciphertext: a relay that changes any of them breaks decryption
        val aad = SessionCipher.envelopeAad(tag, envelopeId, contentType, timestamp)
        // Padded to a size bucket first, so relays can't tell messages apart by length
        return Envelope(
            tag = tag.toByteString(),
            ciphertext = cipher.encrypt(Padding.pad(plaintext), counter, aad).toByteString(),
            nonce = cipher.constructNonce(counter).toByteString(),
            timestamp = timestamp,
            envelope_id = envelopeId,
            ttl = ttl,
            content_type = contentType
        )
    }
}
