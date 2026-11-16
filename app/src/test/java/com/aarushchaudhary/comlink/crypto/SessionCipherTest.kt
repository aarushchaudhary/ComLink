package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.AEADBadTagException

class SessionCipherTest {

    private class Party {
        val priv: ByteArray = X25519.generatePrivateKey()
        val pub: ByteArray = X25519.publicFromPrivate(priv)
        fun cipherWith(other: Party) = SessionCipher(priv, pub, other.pub)
    }

    private val alice = Party()
    private val bob = Party()
    private val aliceToBob = alice.cipherWith(bob)
    private val bobFromAlice = bob.cipherWith(alice)

    private val tag = aliceToBob.outgoingTag(1L)
    private val header = SessionCipher.envelopeAad(tag, "env-1", 0, 1_700_000_000_000L)

    @Test
    fun roundTripBetweenTwoParties() {
        val message = "hello over the mesh".toByteArray()
        val ciphertext = aliceToBob.encrypt(message, 42, header)
        val (plaintext, counter) = bobFromAlice.decrypt(ciphertext, aliceToBob.constructNonce(42), header)
        assertArrayEquals(message, plaintext)
        assertEquals(42, counter)
    }

    @Test
    fun bothDirectionsWork() {
        val reply = "reply".toByteArray()
        val ciphertext = bobFromAlice.encrypt(reply, 7, header)
        val (plaintext, _) = aliceToBob.decrypt(ciphertext, bobFromAlice.constructNonce(7), header)
        assertArrayEquals(reply, plaintext)
    }

    @Test(expected = SecurityException::class)
    fun ownMessageReflectedBackIsRejected() {
        // Same direction byte as our own sends: a reflected message must not decrypt
        val ciphertext = aliceToBob.encrypt("x".toByteArray(), 1, header)
        aliceToBob.decrypt(ciphertext, aliceToBob.constructNonce(1), header)
    }

    @Test(expected = SecurityException::class)
    fun messageFromAnotherSessionIsRejected() {
        val mallory = Party()
        val malloryToBob = mallory.cipherWith(bob)
        val ciphertext = malloryToBob.encrypt("x".toByteArray(), 1, header)
        // Bob tries it against his session with Alice: the salt doesn't match
        bobFromAlice.decrypt(ciphertext, malloryToBob.constructNonce(1), header)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedCiphertextIsRejected() {
        val ciphertext = aliceToBob.encrypt("pay 10".toByteArray(), 3, header)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        bobFromAlice.decrypt(ciphertext, aliceToBob.constructNonce(3), header)
    }

    @Test
    fun anyChangedHeaderFieldIsRejected() {
        val ciphertext = aliceToBob.encrypt("meet at 6".toByteArray(), 9, header)
        val nonce = aliceToBob.constructNonce(9)
        val tampered = mapOf(
            "tag" to SessionCipher.envelopeAad(aliceToBob.outgoingTag(2L), "env-1", 0, 1_700_000_000_000L),
            "envelope_id" to SessionCipher.envelopeAad(tag, "env-2", 0, 1_700_000_000_000L),
            "content_type" to SessionCipher.envelopeAad(tag, "env-1", 2, 1_700_000_000_000L),
            "timestamp" to SessionCipher.envelopeAad(tag, "env-1", 0, 1_600_000_000_000L)
        )
        for ((field, aad) in tampered) {
            try {
                bobFromAlice.decrypt(ciphertext, nonce, aad)
                fail("changing $field should break decryption")
            } catch (expected: AEADBadTagException) {
            }
        }
        // The untouched header still works
        assertArrayEquals("meet at 6".toByteArray(), bobFromAlice.decrypt(ciphertext, nonce, header).first)
    }

    @Test
    fun headerFieldBoundariesCannotBeShifted() {
        // tag "ab" + id "c" and tag "a" + id "bc" must give different AAD, thanks to the length prefixes
        assertNotEquals(
            SessionCipher.envelopeAad("ab".toByteArray(), "c", 0, 0).toList(),
            SessionCipher.envelopeAad("a".toByteArray(), "bc", 0, 0).toList()
        )
    }

    @Test
    fun differentCountersGiveDifferentNonces() {
        val nonces = (0 until 1000).map { aliceToBob.constructNonce(it).toList() }.toSet()
        assertEquals(1000, nonces.size)
        assertNotEquals(aliceToBob.constructNonce(5).toList(), bobFromAlice.constructNonce(5).toList())
    }

    @Test
    fun tagsMatchAcrossTheTwoPhonesAndDifferByDirection() {
        // What alice puts on envelopes to bob is exactly what bob expects from alice
        assertArrayEquals(aliceToBob.outgoingTag(7L), bobFromAlice.incomingTag(7L))
        assertArrayEquals(bobFromAlice.outgoingTag(7L), aliceToBob.incomingTag(7L))
        // The two directions of one chat don't share a tag, so they can't be linked to each other
        assertNotEquals(aliceToBob.outgoingTag(7L).toList(), aliceToBob.incomingTag(7L).toList())
        assertEquals(SessionCipher.TAG_SIZE, aliceToBob.outgoingTag(7L).size)
    }

    @Test
    fun tagChangesEveryWindowAndPerContact() {
        val tags = (0L until 100L).map { aliceToBob.outgoingTag(it).toList() }.toSet()
        assertEquals(100, tags.size)
        // Another contact's tags for the same window are unrelated
        val carol = Party()
        assertNotEquals(aliceToBob.outgoingTag(7L).toList(), alice.cipherWith(carol).outgoingTag(7L).toList())
    }

    @Test
    fun tagWindowIsFifteenMinutes() {
        assertEquals(0L, SessionCipher.tagWindow(0L))
        assertEquals(0L, SessionCipher.tagWindow(15 * 60_000L - 1))
        assertEquals(1L, SessionCipher.tagWindow(15 * 60_000L))
        assertEquals(-1L, SessionCipher.tagWindow(-1L)) // a clock before 1970 still gets a window
    }
}
