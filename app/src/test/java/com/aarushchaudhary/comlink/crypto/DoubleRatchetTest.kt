package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleRatchetTest {

    /** One phone's side of one chat: its ratchet state plus its stored skipped keys, like the database. */
    private class Side(myPrivate: ByteArray, myPublic: ByteArray, peerPublic: ByteArray) {
        var state = DoubleRatchet.initial(myPrivate, myPublic, peerPublic)
        val skipped = mutableMapOf<Pair<List<Byte>, Int>, ByteArray>()

        fun send(text: String): ByteArray {
            val (next, keys) = DoubleRatchet.reserve(state, 1)
            state = next
            return keys.single().seal(text.toByteArray())
        }

        fun receive(message: ByteArray): String? {
            val header = DoubleRatchet.parseHeader(message) ?: return null
            val key = header.ratchetKey.toList() to header.number
            val opened = DoubleRatchet.open(state, message, skipped[key]) ?: return null
            state = opened.state
            opened.newSkippedKeys.forEach { skipped[it.ratchetKey.toList() to it.messageNumber] = it.messageKey }
            if (opened.usedSkippedKey) skipped.remove(key)
            return String(opened.plaintext)
        }

        /** What the database holds, after a restart. */
        fun restart() {
            state = RatchetState.fromBytes(state.toBytes())!!
        }
    }

    private val alicePrivate = X25519.generatePrivateKey()
    private val bobPrivate = X25519.generatePrivateKey()
    private val alicePublic = X25519.publicFromPrivate(alicePrivate)
    private val bobPublic = X25519.publicFromPrivate(bobPrivate)
    private val alice = Side(alicePrivate, alicePublic, bobPublic)
    private val bob = Side(bobPrivate, bobPublic, alicePublic)

    @Test
    fun eitherSideCanSendFirst() {
        assertEquals("hi from a", bob.receive(alice.send("hi from a")))
        val other = Side(bobPrivate, bobPublic, alicePublic)
        val otherAlice = Side(alicePrivate, alicePublic, bobPublic)
        assertEquals("hi from b", otherAlice.receive(other.send("hi from b")))
    }

    @Test
    fun bothSendingFirstAtOnceStillWorks() {
        val a1 = alice.send("a1")
        val b1 = bob.send("b1")
        assertEquals("a1", bob.receive(a1))
        assertEquals("b1", alice.receive(b1))
        // And the conversation carries on through several turns
        repeat(5) { turn ->
            assertEquals("a$turn", bob.receive(alice.send("a$turn")))
            assertEquals("b$turn", alice.receive(bob.send("b$turn")))
        }
    }

    @Test
    fun outOfOrderAndAcrossRatchetSteps() {
        val first = (1..5).map { alice.send("m$it") }
        assertEquals("m3", bob.receive(first[2]))        // 1 and 2 skipped (stored)
        assertEquals("r1", alice.receive(bob.send("r1"))) // a DH step on both sides
        val second = (6..8).map { alice.send("m$it") }
        assertEquals("m8", bob.receive(second[2]))
        // Late ones from both chains still open
        assertEquals("m1", bob.receive(first[0]))
        assertEquals("m7", bob.receive(second[1]))
        assertEquals("m5", bob.receive(first[4]))
        assertEquals("m2", bob.receive(first[1]))
        assertEquals("m4", bob.receive(first[3]))
        assertEquals("m6", bob.receive(second[0]))
        assertTrue(bob.skipped.isEmpty())
    }

    @Test
    fun lostMessagesDontBreakTheChat() {
        alice.send("lost 1"); alice.send("lost 2")
        assertEquals("arrives", bob.receive(alice.send("arrives")))
        assertEquals("reply", alice.receive(bob.send("reply")))
        assertEquals(2, bob.skipped.size) // kept in case they turn up
    }

    @Test
    fun duplicatesAndTamperingAreRejectedWithoutHarm() {
        val m = alice.send("once")
        assertEquals("once", bob.receive(m))
        assertNull(bob.receive(m)) // a duplicate
        val tampered = m.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(bob.receive(tampered))
        assertNull(bob.receive(ByteArray(10)))
        // Nothing above changed bob's state
        assertEquals("still fine", bob.receive(alice.send("still fine")))
    }

    @Test
    fun tooManySkippedMessagesAreRefused() {
        repeat(DoubleRatchet.MAX_SKIP + 1) { alice.send("x") }
        assertNull(bob.receive(alice.send("too far")))
    }

    @Test
    fun reservedKeysForFilePartsOpenInAnyOrder() {
        val (next, keys) = DoubleRatchet.reserve(alice.state, 20)
        alice.state = next
        val parts = keys.mapIndexed { i, key -> key.seal("part $i".toByteArray()) }
        parts.indices.shuffled().forEach { i -> assertEquals("part $i", bob.receive(parts[i])) }
        assertEquals("after", bob.receive(alice.send("after")))
    }

    @Test
    fun stateSurvivesARestartMidChat() {
        assertEquals("a", bob.receive(alice.send("a")))
        val pending = alice.send("in flight")
        alice.restart(); bob.restart()
        assertEquals("in flight", bob.receive(pending))
        assertEquals("b", alice.receive(bob.send("b")))
        assertNull(RatchetState.fromBytes(byteArrayOf(9, 9, 9)))
    }

    @Test
    fun stolenKeysCantOpenEarlierMessages() {
        // A relay records everything once the chat is under way (each side has heard from the other)
        assertEquals("hello", bob.receive(alice.send("hello")))
        assertEquals("ack", alice.receive(bob.send("ack")))
        val recorded = alice.send("secret")
        assertEquals("secret", bob.receive(recorded))
        assertEquals("more", alice.receive(bob.send("more")))

        // Later the thief takes both phones' identity keys and current ratchet states
        val thiefAsBob = Side(bobPrivate, bobPublic, alicePublic)
        assertNull("a fresh start from the identity key can't open it", thiefAsBob.receive(recorded))
        val stolenBob = Side(bobPrivate, bobPublic, alicePublic).also { it.state = RatchetState.fromBytes(bob.state.toBytes())!! }
        assertNull("the current state no longer holds its key", stolenBob.receive(recorded))
        assertNotNull(bob.state)
    }

    @Test
    fun aRatchetMessageAddsAFixedOverhead() {
        assertEquals(DoubleRatchet.OVERHEAD + 5, alice.send("hello").size)
    }
}
