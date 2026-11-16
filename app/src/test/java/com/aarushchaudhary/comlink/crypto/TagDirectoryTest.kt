package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagDirectoryTest {

    private class Party {
        val priv: ByteArray = X25519.generatePrivateKey()
        val pub: ByteArray = X25519.publicFromPrivate(priv)
        fun cipherWith(other: Party) = SessionCipher(priv, pub, other.pub)
    }

    private val me = Party()
    private val bob = Party()
    private val carol = Party()
    private val stranger = Party()
    private val window = SessionCipher.TAG_WINDOW_MS

    private var now = 1_000 * window + window / 2 // middle of window 1000
    private val contacts = mutableMapOf("bob" to me.cipherWith(bob), "carol" to me.cipherWith(carol))
    private val directory = TagDirectory(contacts = { contacts.toMap() }, clock = { now })

    private fun tagFrom(sender: Party, w: Long) = sender.cipherWith(me).outgoingTag(w)

    @Test
    fun eachContactIsFoundByTheirTag() {
        assertEquals("bob", directory.senderOf(tagFrom(bob, 1000)))
        assertEquals("carol", directory.senderOf(tagFrom(carol, 1000)))
    }

    @Test
    fun clocksUpToOneWindowApartStillMatch() {
        assertEquals("bob", directory.senderOf(tagFrom(bob, 999)))  // sender's clock is behind
        assertEquals("bob", directory.senderOf(tagFrom(bob, 1001))) // sender's clock is ahead
        assertNull(directory.senderOf(tagFrom(bob, 998)))           // too far off
        assertNull(directory.senderOf(tagFrom(bob, 1002)))
    }

    @Test
    fun relayTrafficAndOwnEchoesAreNotOurs() {
        // Someone else's chat passing through, and the envelope we sent to bob coming back
        assertNull(directory.senderOf(stranger.cipherWith(bob).outgoingTag(1000)))
        assertNull(directory.senderOf(me.cipherWith(bob).outgoingTag(1000)))
        assertNull(directory.senderOf(ByteArray(0)))
        assertNull(directory.senderOf(ByteArray(SessionCipher.TAG_SIZE)))
    }

    @Test
    fun followsTheClockIntoTheNextWindow() {
        assertEquals("bob", directory.senderOf(tagFrom(bob, 1000)))
        now += 2 * window // now in window 1002
        assertNull(directory.senderOf(tagFrom(bob, 1000)))
        assertEquals("bob", directory.senderOf(tagFrom(bob, 1002)))
    }

    @Test
    fun newContactIsFoundAfterInvalidate() {
        val dave = Party()
        assertEquals("bob", directory.senderOf(tagFrom(bob, 1000))) // index built without dave
        contacts["dave"] = me.cipherWith(dave)
        assertNull(directory.senderOf(tagFrom(dave, 1000)))
        directory.invalidate()
        assertEquals("dave", directory.senderOf(tagFrom(dave, 1000)))
    }
}
