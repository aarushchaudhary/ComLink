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

    private fun newSender(hasSession: Boolean = true) = EncryptedSender(
        localDeviceId = "alice",
        loadCounter = { id ->
            // Yield between read and write, the way a real database call would, to invite races
            yield()
            if (hasSession) counters[id] ?: 0 else null
        },
        saveCounter = { id, next -> yield(); counters[id] = next },
        cipherFor = { cipher },
        transmit = { envelope, _, _ -> yield(); sent.add(envelope); true }
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
        val reservedCounters = reserved.awaitAll().flatMap { it.toList() }
        reservedCounters.forEach { sender.sendReserved(peer, "part".toByteArray(), it, contentType = 2) }

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
        assertEquals("alice", e.sender_id)
        assertEquals(peer, e.recipient_id)
        assertEquals("id-1", e.envelope_id)
        assertEquals(123L, e.timestamp)
        assertEquals(1, e.content_type)
        assertEquals(EncryptedSender.DEFAULT_TTL, e.ttl)
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
            localDeviceId = "alice",
            loadCounter = { 0 },
            saveCounter = { _, _ -> },
            cipherFor = { aliceToBob },
            transmit = { envelope, _, _ -> sent.add(envelope); true }
        )
        assertTrue(sender.send(peer, "hi bob".toByteArray(), contentType = 0))
        val e = sent.single()

        fun open(env: Envelope) = bobFromAlice.decrypt(
            env.ciphertext.toByteArray(), env.nonce.toByteArray(),
            SessionCipher.envelopeAad(env.sender_id, env.recipient_id, env.envelope_id, env.content_type, env.timestamp)
        ).first

        assertEquals("hi bob", String(open(e)))
        // A relay may lower ttl: still fine
        assertEquals("hi bob", String(open(e.copy(ttl = 3))))
        // A relay flipping the content type (text -> file part) is caught
        try {
            open(e.copy(content_type = 2))
            org.junit.Assert.fail("tampered content_type must not decrypt")
        } catch (expected: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun noSessionMeansNothingIsSent() = runBlocking {
        val sender = newSender(hasSession = false)
        assertFalse(sender.send(peer, "hi".toByteArray()))
        assertNull(sender.reserveCounters(peer, 3))
        assertTrue(sent.isEmpty())
    }
}
