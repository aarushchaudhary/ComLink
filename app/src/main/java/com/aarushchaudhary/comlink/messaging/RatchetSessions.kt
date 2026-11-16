package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.crypto.DoubleRatchet
import com.aarushchaudhary.comlink.crypto.RatchetState
import com.aarushchaudhary.comlink.crypto.SkippedKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** A second encryption layer inside the envelope encryption. */
interface InnerLayer {
    /** [count] one-use sealers for [peerId], with the state already saved; null when there's no session. */
    suspend fun reserve(peerId: String, count: Int): List<(ByteArray) -> ByteArray>?
}

/**
 * Every contact's Double Ratchet: loads the state, runs [DoubleRatchet], saves the result.
 * Sending and receiving both change a contact's state, so each contact has one lock here (held
 * only for the state change, never while sending over Bluetooth).
 *
 * Storage is passed in as functions, so this has no Android dependencies and can be unit-tested.
 */
class RatchetSessions(
    private val loadState: suspend (peerId: String) -> ByteArray?,
    /** A fresh state from the contact's QR key, or null when there's no usable key. */
    private val initialState: suspend (peerId: String) -> RatchetState?,
    private val saveState: suspend (peerId: String, state: ByteArray) -> Unit,
    private val storedKey: suspend (peerId: String, ratchetKey: String, number: Int) -> ByteArray?,
    /** Saves everything one opened message changed, all at once (a database transaction). */
    private val commitOpened: suspend (peerId: String, state: ByteArray, newSkipped: List<SkippedKey>, usedKey: Pair<String, Int>?) -> Unit
) : InnerLayer {

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(peerId: String) = locks.getOrPut(peerId) { Mutex() }

    private suspend fun current(peerId: String): RatchetState? =
        loadState(peerId)?.let { RatchetState.fromBytes(it) } ?: initialState(peerId)

    override suspend fun reserve(peerId: String, count: Int): List<(ByteArray) -> ByteArray>? = lockFor(peerId).withLock {
        val state = current(peerId) ?: return@withLock null
        val (next, keys) = DoubleRatchet.reserve(state, count)
        // Saved before any key is used, so a crash can never reuse one
        saveState(peerId, next.toBytes())
        keys.map { key -> { plaintext: ByteArray -> key.seal(plaintext) } }
    }

    /** The plaintext, or null when it doesn't open (nothing is saved then). */
    suspend fun open(peerId: String, message: ByteArray): ByteArray? = lockFor(peerId).withLock {
        val state = current(peerId) ?: return@withLock null
        val header = DoubleRatchet.parseHeader(message) ?: return@withLock null
        val keyHex = toHex(header.ratchetKey)
        val stored = storedKey(peerId, keyHex, header.number)
        val opened = DoubleRatchet.open(state, message, stored) ?: return@withLock null
        commitOpened(
            peerId,
            opened.state.toBytes(),
            opened.newSkippedKeys,
            if (opened.usedSkippedKey) keyHex to header.number else null
        )
        opened.plaintext
    }

    companion object {
        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
