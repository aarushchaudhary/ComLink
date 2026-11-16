package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.crypto.DoubleRatchet
import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.proto.Envelope
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class RatchetSessionsTest {

    private class Party(val id: String) {
        val priv: ByteArray = X25519.generatePrivateKey()
        val pub: ByteArray = X25519.publicFromPrivate(priv)
    }

    /** One phone: its ratchet sessions over an in-memory "database" (with the yields a real one has). */
    private class Phone(val me: Party, val peer: Party) {
        val states = ConcurrentHashMap<String, ByteArray>()
        val skipped = ConcurrentHashMap<Triple<String, String, Int>, ByteArray>()
        var initialCalls = 0

        val sessions = RatchetSessions(
            loadState = { yield(); states[it] },
            initialState = { initialCalls++; DoubleRatchet.initial(me.priv, me.pub, peer.pub) },
            saveState = { id, state -> yield(); states[id] = state },
            storedKey = { id, key, n -> skipped[Triple(id, key, n)] },
            commitOpened = { id, state, newSkipped, used ->
                yield()
                states[id] = state
                used?.let { skipped.remove(Triple(id, it.first, it.second)) }
                newSkipped.forEach { skipped[Triple(id, RatchetSessions.toHex(it.ratchetKey), it.messageNumber)] = it.messageKey }
            }
        )

        suspend fun seal(text: String) = sessions.reserve(peer.id, 1)!!.single()(text.toByteArray())
        suspend fun open(message: ByteArray) = sessions.open(peer.id, message)?.let { String(it) }
    }

    private val alice = Party("alice")
    private val bob = Party("bob")
    private val alicePhone = Phone(alice, bob)
    private val bobPhone = Phone(bob, alice)

    @Test
    fun aFailedOpenSavesNothing() = runBlocking {
        assertNull(bobPhone.open(ByteArray(80)))
        assertTrue(bobPhone.states.isEmpty())
        // The first real message creates and saves the state
        assertEquals("hi", bobPhone.open(alicePhone.seal("hi")))
        assertTrue(bobPhone.states.containsKey("alice"))
    }

    @Test
    fun skippedKeysAreStoredAndUsedOnce() = runBlocking {
        val late = alicePhone.seal("late")
        assertEquals("now", bobPhone.open(alicePhone.seal("now")))
        assertEquals(1, bobPhone.skipped.size)
        assertEquals("late", bobPhone.open(late))
        assertTrue(bobPhone.skipped.isEmpty())
        assertNull(bobPhone.open(late)) // its key is gone: a replay can't open it again
    }

    @Test
    fun concurrentSendsAndReceivesKeepBothSidesInStep() = runBlocking {
        // Both phones send 200 messages at once while receiving the other's, in shuffled order
        val fromAlice = (0 until 200).map { async(Dispatchers.Default) { alicePhone.seal("a$it") } }.awaitAll()
        val fromBob = (0 until 200).map { async(Dispatchers.Default) { bobPhone.seal("b$it") } }.awaitAll()
        val opened = Collections.synchronizedList(mutableListOf<String?>())
        listOf(
            async(Dispatchers.Default) { fromAlice.shuffled().forEach { opened.add(bobPhone.open(it)) } },
            async(Dispatchers.Default) { fromBob.shuffled().forEach { opened.add(alicePhone.open(it)) } },
            async(Dispatchers.Default) { repeat(50) { opened.add(bobPhone.open(alicePhone.seal("extra a"))) } },
            async(Dispatchers.Default) { repeat(50) { opened.add(alicePhone.open(bobPhone.seal("extra b"))) } }
        ).awaitAll()
        assertEquals(500, opened.size)
        assertTrue("every message opened", opened.all { it != null })
        assertEquals((0 until 200).map { "a$it" }.toSet() + "extra a" + "extra b" + (0 until 200).map { "b$it" },
            opened.filterNotNull().toSet())
    }

    @Test
    fun bothLayersEndToEnd() = runBlocking {
        val aliceToBob = SessionCipher(alice.priv, alice.pub, bob.pub)
        val bobFromAlice = SessionCipher(bob.priv, bob.pub, alice.pub)
        val wire = mutableListOf<Envelope>()
        var counter = 0
        val sender = EncryptedSender(
            loadCounter = { counter }, saveCounter = { _, next -> counter = next },
            cipherFor = { aliceToBob },
            inner = alicePhone.sessions,
            transmit = { _, envelope, _, _ -> wire.add(envelope); true }
        )
        sender.send("bob", "through both layers".toByteArray())
        val slots = sender.reserveCounters("bob", 3)!!
        slots.forEachIndexed { i, slot -> sender.sendReserved("bob", "part $i".toByteArray(), slot, ContentTypes.FILE_PART) }
        sender.send("bob", "PRESENCE".toByteArray(), contentType = ContentTypes.PRESENCE, ttl = 1)

        fun outer(e: Envelope): ByteArray = Padding.unpad(bobFromAlice.decrypt(
            e.ciphertext.toByteArray(), e.nonce.toByteArray(),
            SessionCipher.envelopeAad(e.tag.toByteArray(), e.envelope_id, e.content_type, e.timestamp)
        ).first)!!

        val received = wire.map { e ->
            val inner = outer(e)
            if (ContentTypes.usesRatchet(e.content_type)) bobPhone.open(inner) else String(inner)
        }
        assertEquals(listOf("through both layers", "part 0", "part 1", "part 2", "PRESENCE"), received)
        // A relay that strips nothing still sees only padded ciphertext: no ratchet key in clear
        val ratchetKey = DoubleRatchet.parseHeader(outer(wire[0]))!!.ratchetKey
        assertTrue(wire.none { e -> e.encode().toList().windowed(32).any { it == ratchetKey.toList() } })
    }
}
