package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.proto.Envelope
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class EncryptedSenderTest {

    private val peer = "bob"
    private val cipher: SessionCipher = run {
        val myPriv = X25519.generatePrivateKey()
        val peerPub = X25519.publicFromPrivate(X25519.generatePrivateKey())
        SessionCipher(myPriv, X25519.publicFromPrivate(myPriv), peerPub)
    }

    private val counters = ConcurrentHashMap<String, Int>()
    private val sent = Collections.synchronizedList(mutableListOf<Envelope>())

    private var now = 1_700_000_000_000L

    private fun newSender(hasSession: Boolean = true) = EncryptedSender(
        loadCounter = { id ->
            // Yield between read and write, the way a real database call would, to invite races
            yield()
            if (hasSession) counters[id] ?: 0 else null
        },
        saveCounter = { id, next -> yield(); counters[id] = next },
        cipherFor = { cipher },
        transmit = { _, envelope, _, _ -> yield(); sent.add(envelope); true },
        clock = { now }
    )

    private fun nonceOf(e: Envelope) = e.nonce.toByteArray().toList()

    @Test
    fun thousandConcurrentSendsNeverReuseANonce() = runBlocking {
        val sender = newSender()
        (0 until 1000).map {
            async(Dispatchers.Default) { sender.send(peer, "msg $it".toByteArray()) }
        }.awaitAll()

        assertEquals(1000, sent.size)
        assertEquals(1000, sent.map(::nonceOf).toSet().size)
        assertEquals(1000, counters[peer])
    }

    @Test
    fun reservedCountersNeverOverlapWithNormalSends() = runBlocking {
        val sender = newSender()
        val reserved = (0 until 20).map {
            async(Dispatchers.Default) { sender.reserveCounters(peer, 50)!! }
        }
        val normal = (0 until 200).map {
            async(Dispatchers.Default) { sender.send(peer, "x".toByteArray()) }
        }
        normal.awaitAll()
        val reservedSlots = reserved.awaitAll().flatten()
        reservedSlots.forEach { sender.sendReserved(peer, "part".toByteArray(), it, contentType = 2) }

        // 200 normal + 20 x 50 reserved = 1200 envelopes, every nonce different
        assertEquals(1200, sent.size)
        assertEquals(1200, sent.map(::nonceOf).toSet().size)
        assertEquals(1200, counters[peer])
    }

    @Test
    fun envelopeCarriesTheGivenFields() = runBlocking {
        val sender = newSender()
        assertTrue(sender.send(peer, "hi".toByteArray(), contentType = 1, envelopeId = "id-1", timestamp = 123L))
        val e = sent.single()
        // No IDs on the wire, only the rotating tag for this window
        assertEquals(cipher.outgoingTag(SessionCipher.tagWindow(now)).toList(), e.tag.toByteArray().toList())
        assertEquals("id-1", e.envelope_id)
        assertEquals(123L, e.timestamp)
        assertEquals(1, e.content_type)
        assertTrue("ttl ${e.ttl}", e.ttl in EncryptedSender.MIN_START_TTL..EncryptedSender.MAX_TTL)
        // Padded to a bucket: 256 bytes + 16-byte GCM tag
        assertEquals(256 + 16, e.ciphertext.size)
    }

    @Test
    fun startTtlIsRandomWithinRangeAndPresenceUsesOne() = runBlocking {
        val sender = newSender()
        repeat(200) { sender.send(peer, "x".toByteArray()) }
        val ttls = sent.map { it.ttl }.toSet()
        assertEquals(setOf(8, 9, 10), ttls)
        sent.clear()
        sender.send(peer, "PRESENCE".toByteArray(), contentType = 3, ttl = 1)
        assertEquals(1, sent.single().ttl)
    }

    @Test
    fun counterLimitStopsSendingInsteadOfWrapping() = runBlocking {
        counters[peer] = EncryptedSender.MAX_COUNTER
        val sender = newSender()
        assertFalse(sender.send(peer, "x".toByteArray()))
        assertNull(sender.reserveCounters(peer, 1))
        counters[peer] = EncryptedSender.MAX_COUNTER - 5
        assertNull(sender.reserveCounters(peer, 6))        // would pass the limit
        assertTrue(sender.reserveCounters(peer, 5) != null) // exactly up to it is fine
        assertTrue(sent.isEmpty())
    }

    @Test
    fun receiverDecryptsAndDetectsATamperedHeader() = runBlocking {
        val alicePriv = X25519.generatePrivateKey()
        val bobPriv = X25519.generatePrivateKey()
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPub = X25519.publicFromPrivate(bobPriv)
        val aliceToBob = SessionCipher(alicePriv, alicePub, bobPub)
        val bobFromAlice = SessionCipher(bobPriv, bobPub, alicePub)

        val sender = EncryptedSender(
            loadCounter = { 0 },
            saveCounter = { _, _ -> },
            cipherFor = { aliceToBob },
            transmit = { _, envelope, _, _ -> sent.add(envelope); true }
        )
        assertTrue(sender.send(peer, "hi bob".toByteArray(), contentType = 0))
        val e = sent.single()

        fun open(env: Envelope) = Padding.unpad(bobFromAlice.decrypt(
            env.ciphertext.toByteArray(), env.nonce.toByteArray(),
            SessionCipher.envelopeAad(env.tag.toByteArray(), env.envelope_id, env.content_type, env.timestamp)
        ).first)!!

        assertEquals("hi bob", String(open(e)))
        // A relay may lower ttl: still fine
        assertEquals("hi bob", String(open(e.copy(ttl = 3))))
        // A relay flipping the content type (text -> file part) or swapping the tag is caught
        for (tampered in listOf(e.copy(content_type = 2), e.copy(tag = okio.ByteString.of(*ByteArray(16))))) {
            try {
                open(tampered)
                org.junit.Assert.fail("tampered header must not decrypt")
            } catch (expected: javax.crypto.AEADBadTagException) {
            }
        }
        // And bob recognises the tag as alice's
        assertEquals(bobFromAlice.incomingTag(SessionCipher.tagWindow(System.currentTimeMillis())).toList(), e.tag.toByteArray().toList())
    }

    @Test
    fun paddedLargestFilePartStillFitsOneBlePacketSet() = runBlocking {
        val sender = newSender()
        val part = com.aarushchaudhary.comlink.proto.FilePart(
            transfer_id = java.util.UUID.randomUUID().toString(),
            part_index = 99,
            data_ = okio.ByteString.of(*ByteArray(com.aarushchaudhary.comlink.data.TransferChecks.PART_SIZE))
        )
        val slot = sender.reserveCounters(peer, 1)!!.single()
        assertTrue(sender.sendReserved(peer, part.encode(), slot, contentType = 2))
        val wireSize = sent.single().encode().size
        // BluetoothService carries at most 255 pieces of 500 data bytes per payload
        assertTrue("file part envelope is $wireSize bytes", wireSize <= 255 * 500)
        // With the ratchet's overhead it still fits the largest padding bucket (no odd size)
        val withRatchet = part.encode().size + com.aarushchaudhary.comlink.crypto.DoubleRatchet.OVERHEAD
        assertEquals(Padding.BUCKETS.last(), Padding.paddedSize(withRatchet))
    }

    @Test
    fun noSessionMeansNothingIsSent() = runBlocking {
        val sender = newSender(hasSession = false)
        assertFalse(sender.send(peer, "hi".toByteArray()))
        assertNull(sender.reserveCounters(peer, 3))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun tagFollowsTheSendTimeNotTheMessageTimestamp() = runBlocking {
        val sender = newSender()
        // A retry of a message written two hours ago keeps its old timestamp...
        sender.send(peer, "x".toByteArray(), envelopeId = "m1", timestamp = now - 2 * 3_600_000L)
        // ...but is tagged for the window it's sent in, so the receiver still recognises it
        assertEquals(cipher.outgoingTag(SessionCipher.tagWindow(now)).toList(), sent.single().tag.toByteArray().toList())
        now += SessionCipher.TAG_WINDOW_MS
        sender.send(peer, "x".toByteArray(), envelopeId = "m1", timestamp = now - 2 * 3_600_000L)
        assertEquals(cipher.outgoingTag(SessionCipher.tagWindow(now)).toList(), sent.last().tag.toByteArray().toList())
    }

    @Test
    fun innerLayerSealsEverythingButPresenceAndFileParts() = runBlocking {
        val innerCalls = mutableListOf<Int>()
        val sender = EncryptedSender(
            loadCounter = { 0 }, saveCounter = { _, _ -> },
            cipherFor = { cipher },
            inner = object : InnerLayer {
                override suspend fun reserve(peerId: String, count: Int): List<(ByteArray) -> ByteArray> {
                    innerCalls.add(count)
                    return List(count) { { p: ByteArray -> "R".toByteArray() + p } }
                }
            },
            transmit = { _, envelope, _, _ -> sent.add(envelope); true }
        )
        sender.send(peer, "text".toByteArray(), contentType = ContentTypes.MESSAGE)
        sender.send(peer, "PRESENCE".toByteArray(), contentType = ContentTypes.PRESENCE, ttl = 1)
        sender.reserveCounters(peer, 3)
        assertEquals(listOf(1, 3), innerCalls) // text: 1 key, presence: none, file parts: 3 at once
    }

    @Test
    fun noRatchetSessionMeansNothingIsSent() = runBlocking {
        val sender = EncryptedSender(
            loadCounter = { 0 }, saveCounter = { _, _ -> error("counter must not move") },
            cipherFor = { cipher },
            inner = object : InnerLayer {
                override suspend fun reserve(peerId: String, count: Int): List<(ByteArray) -> ByteArray>? = null
            },
            transmit = { _, envelope, _, _ -> sent.add(envelope); true }
        )
        assertFalse(sender.send(peer, "x".toByteArray()))
        assertNull(sender.reserveCounters(peer, 2))
        assertTrue(sent.isEmpty())
    }
}
