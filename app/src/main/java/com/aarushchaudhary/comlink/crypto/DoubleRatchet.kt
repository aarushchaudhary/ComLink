package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One contact's Double Ratchet state (Signal's published algorithm). Immutable: every operation
 * returns a new state, so a failed decryption never changes anything.
 */
class RatchetState(
    val dhsPrivate: ByteArray,
    val dhsPublic: ByteArray,
    /** The contact's current ratchet key; null until they've sent one (the "Bob" side at first). */
    val dhr: ByteArray?,
    val rootKey: ByteArray,
    val sendChain: ByteArray,
    val receiveChain: ByteArray?,
    val sendCount: Int,
    val receiveCount: Int,
    val previousSendCount: Int
) {
    fun toBytes(): ByteArray {
        val fields = listOf(dhsPrivate, dhsPublic, dhr, rootKey, sendChain, receiveChain)
        val buffer = ByteBuffer.allocate(1 + fields.sumOf { 4 + (it?.size ?: 0) } + 12)
        buffer.put(FORMAT)
        fields.forEach { field -> if (field == null) buffer.putInt(-1) else buffer.putInt(field.size).put(field) }
        buffer.putInt(sendCount).putInt(receiveCount).putInt(previousSendCount)
        return buffer.array()
    }

    fun copy(
        dhsPrivate: ByteArray = this.dhsPrivate,
        dhsPublic: ByteArray = this.dhsPublic,
        dhr: ByteArray? = this.dhr,
        rootKey: ByteArray = this.rootKey,
        sendChain: ByteArray = this.sendChain,
        receiveChain: ByteArray? = this.receiveChain,
        sendCount: Int = this.sendCount,
        receiveCount: Int = this.receiveCount,
        previousSendCount: Int = this.previousSendCount
    ) = RatchetState(dhsPrivate, dhsPublic, dhr, rootKey, sendChain, receiveChain, sendCount, receiveCount, previousSendCount)

    companion object {
        private const val FORMAT: Byte = 1

        /** Null for anything that isn't a state written by [toBytes]. */
        fun fromBytes(bytes: ByteArray): RatchetState? = try {
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.get() == FORMAT)
            fun field(): ByteArray? {
                val size = buffer.int
                if (size == -1) return null
                require(size in 0..64)
                return ByteArray(size).also { buffer.get(it) }
            }
            val dhsPrivate = field()!!
            val dhsPublic = field()!!
            val dhr = field()
            val rootKey = field()!!
            val sendChain = field()!!
            val receiveChain = field()
            RatchetState(dhsPrivate, dhsPublic, dhr, rootKey, sendChain, receiveChain, buffer.int, buffer.int, buffer.int)
        } catch (e: Exception) {
            null
        }
    }
}

/** A message key from a chain the receiver skipped over (lost or late message), kept for a while. */
class SkippedKey(val ratchetKey: ByteArray, val messageNumber: Int, val messageKey: ByteArray)

/** A one-use key for sending, handed out in advance (e.g. for every part of a file). */
class SendingKey(private val header: ByteArray, private val messageKey: ByteArray) {
    fun seal(plaintext: ByteArray): ByteArray = header + DoubleRatchet.aead(messageKey, plaintext, header, Cipher.ENCRYPT_MODE)
}

/**
 * The Double Ratchet: every message gets its own key, and used keys are deleted, so a key stolen
 * later can't open messages from before (forward secrecy). Each reply mixes in a fresh X25519
 * exchange, so a thief also loses access again (break-in recovery).
 *
 * Setup without a server: both phones start from the X25519 keys they swapped by QR code. The
 * phone with the lower public key plays "Alice" (starts the first DH step with a fresh key); the
 * other plays "Bob". So either side can send first, Bob's first messages use a starting chain
 * both sides derive from the shared secret. Forward secrecy therefore starts once each side has
 * received one message from the other (an automatic ACK is enough); the very first messages
 * before that are only as safe as before.
 *
 * Message format: [version][sender's ratchet key, 32][previous chain length, 4][number, 4]
 * then AES-256-GCM with the header as associated data. It travels inside the normal envelope
 * encryption, so relays never see the ratchet key (which would link messages over time).
 *
 * Pure Kotlin + Tink, no Android: unit-tested on the JVM.
 */
object DoubleRatchet {
    private const val VERSION: Byte = 1
    private const val KEY_SIZE = 32
    const val HEADER_SIZE = 1 + KEY_SIZE + 4 + 4
    private const val TAG_SIZE = 16
    /** Bytes the ratchet adds to every message. */
    const val OVERHEAD = HEADER_SIZE + TAG_SIZE

    /** Most messages one step may skip over (lost or still on their way). */
    const val MAX_SKIP = 1000

    private val ROOT_INFO = "ComLinkRatchetRoot".toByteArray(Charsets.UTF_8)
    private val FIRST_CHAIN_INFO = "ComLinkRatchetFirstChain".toByteArray(Charsets.UTF_8)
    private val STEP_INFO = "ComLinkRatchetStep".toByteArray(Charsets.UTF_8)
    private val MESSAGE_KEY_INFO = "ComLinkRatchetMessageKey".toByteArray(Charsets.UTF_8)

    /** The starting state with a contact. Throws for an invalid key (callers treat that as "no session"). */
    fun initial(myPrivate: ByteArray, myPublic: ByteArray, peerPublic: ByteArray): RatchetState {
        val shared = X25519.computeSharedSecret(myPrivate, peerPublic)
        val root = Hkdf.computeHkdf("HMACSHA256", shared, null, ROOT_INFO, KEY_SIZE)
        // Lets "Bob" send before he has heard from "Alice"
        val firstChain = Hkdf.computeHkdf("HMACSHA256", shared, null, FIRST_CHAIN_INFO, KEY_SIZE)
        val isAlice = SessionCipher.compareByteArrays(myPublic, peerPublic) < 0
        return if (isAlice) {
            val fresh = X25519.generatePrivateKey()
            val (rootKey, sendChain) = rootStep(root, X25519.computeSharedSecret(fresh, peerPublic))
            RatchetState(fresh, X25519.publicFromPrivate(fresh), peerPublic, rootKey, sendChain, firstChain, 0, 0, 0)
        } else {
            RatchetState(myPrivate, myPublic, null, root, firstChain, null, 0, 0, 0)
        }
    }

    /** Hands out [count] one-use sending keys and the state to save before using them. */
    fun reserve(state: RatchetState, count: Int): Pair<RatchetState, List<SendingKey>> {
        var chain = state.sendChain
        val keys = List(count) { i ->
            val (next, messageKey) = chainStep(chain)
            chain = next
            SendingKey(header(state.dhsPublic, state.previousSendCount, state.sendCount + i), messageKey)
        }
        return state.copy(sendChain = chain, sendCount = state.sendCount + count) to keys
    }

    class Header(val ratchetKey: ByteArray, val previousCount: Int, val number: Int)

    /** The header of a ratchet message, or null when it isn't one. */
    fun parseHeader(message: ByteArray): Header? {
        if (message.size < OVERHEAD || message[0] != VERSION) return null
        val buffer = ByteBuffer.wrap(message, 1, HEADER_SIZE - 1)
        val key = ByteArray(KEY_SIZE).also { buffer.get(it) }
        val previous = buffer.int
        val number = buffer.int
        if (previous < 0 || number < 0) return null
        return Header(key, previous, number)
    }

    /**
     * Result of a successful [open]: the plaintext, the state to save, the skipped keys to store,
     * and whether the stored key that was passed in got used (then delete it).
     */
    class Opened(
        val plaintext: ByteArray,
        val state: RatchetState,
        val newSkippedKeys: List<SkippedKey>,
        val usedSkippedKey: Boolean
    )

    /**
     * Decrypts [message]. [storedKey] is the skipped key saved earlier for this header's ratchet key
     * and number, if any. Returns null (and changes nothing) for anything that doesn't decrypt:
     * a duplicate, a too-old message, or a broken one.
     */
    fun open(state: RatchetState, message: ByteArray, storedKey: ByteArray?): Opened? {
        val header = parseHeader(message) ?: return null
        val headerBytes = message.copyOfRange(0, HEADER_SIZE)
        val body = message.copyOfRange(HEADER_SIZE, message.size)

        if (storedKey != null) {
            val plaintext = tryAead(storedKey, body, headerBytes) ?: return null
            return Opened(plaintext, state, emptyList(), usedSkippedKey = true)
        }

        return try {
            var s = state
            val skipped = mutableListOf<SkippedKey>()
            if (s.dhr == null || !s.dhr!!.contentEquals(header.ratchetKey)) {
                // A new ratchet key: keep the keys of the old chain's messages we haven't seen yet...
                s = skipTo(s, header.previousCount, skipped) ?: return null
                // ...then the DH step: a fresh receive chain, a fresh key pair and send chain
                val received = header.ratchetKey
                val (root1, receiveChain) = rootStep(s.rootKey, X25519.computeSharedSecret(s.dhsPrivate, received))
                val fresh = X25519.generatePrivateKey()
                val (root2, sendChain) = rootStep(root1, X25519.computeSharedSecret(fresh, received))
                s = RatchetState(fresh, X25519.publicFromPrivate(fresh), received, root2, sendChain, receiveChain, 0, 0, s.sendCount)
            }
            if (header.number < s.receiveCount) return null // already used (or never stored): a duplicate
            s = skipTo(s, header.number, skipped) ?: return null
            val (nextChain, messageKey) = chainStep(s.receiveChain!!)
            val plaintext = tryAead(messageKey, body, headerBytes) ?: return null
            Opened(plaintext, s.copy(receiveChain = nextChain, receiveCount = header.number + 1), skipped, usedSkippedKey = false)
        } catch (e: java.security.GeneralSecurityException) {
            null // e.g. a ratchet key that isn't a valid X25519 point
        }
    }

    /** Advances the receive chain to [until], keeping each skipped message key. Null if too far. */
    private fun skipTo(state: RatchetState, until: Int, out: MutableList<SkippedKey>): RatchetState? {
        val chain = state.receiveChain ?: return state
        val dhr = state.dhr ?: return state
        if (until - state.receiveCount > MAX_SKIP) return null
        var c = chain
        for (n in state.receiveCount until until) {
            val (next, messageKey) = chainStep(c)
            out.add(SkippedKey(dhr, n, messageKey))
            c = next
        }
        return state.copy(receiveChain = c, receiveCount = maxOf(state.receiveCount, until))
    }

    private fun header(ratchetKey: ByteArray, previousCount: Int, number: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).put(VERSION).put(ratchetKey).putInt(previousCount).putInt(number).array()

    /** KDF_RK: new root key and chain key from the old root key and a DH output. */
    private fun rootStep(rootKey: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.computeHkdf("HMACSHA256", dhOutput, rootKey, STEP_INFO, 2 * KEY_SIZE)
        return out.copyOfRange(0, KEY_SIZE) to out.copyOfRange(KEY_SIZE, 2 * KEY_SIZE)
    }

    /** KDF_CK: next chain key and this message's key. */
    private fun chainStep(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        fun hmac(input: Byte): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(chainKey, "HmacSHA256"))
            return mac.doFinal(byteArrayOf(input))
        }
        return hmac(0x02) to hmac(0x01)
    }

    private fun tryAead(messageKey: ByteArray, body: ByteArray, headerBytes: ByteArray): ByteArray? = try {
        aead(messageKey, body, headerBytes, Cipher.DECRYPT_MODE)
    } catch (e: javax.crypto.AEADBadTagException) {
        null
    }

    /** AES-256-GCM with a key and nonce derived from the one-use message key (so the fixed-per-key nonce is safe). */
    internal fun aead(messageKey: ByteArray, input: ByteArray, associatedData: ByteArray, mode: Int): ByteArray {
        val material = Hkdf.computeHkdf("HMACSHA256", messageKey, null, MESSAGE_KEY_INFO, KEY_SIZE + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(material, 0, KEY_SIZE, "AES"), GCMParameterSpec(128, material, KEY_SIZE, 12))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(input)
    }
}
