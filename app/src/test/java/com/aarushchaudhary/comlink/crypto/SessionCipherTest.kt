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

    private val header = SessionCipher.envelopeAad("alice", "bob", "env-1", 0, 1_700_000_000_000L)

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
            "sender" to SessionCipher.envelopeAad("mallory", "bob", "env-1", 0, 1_700_000_000_000L),
            "recipient" to SessionCipher.envelopeAad("alice", "carol", "env-1", 0, 1_700_000_000_000L),
            "envelope_id" to SessionCipher.envelopeAad("alice", "bob", "env-2", 0, 1_700_000_000_000L),
            "content_type" to SessionCipher.envelopeAad("alice", "bob", "env-1", 2, 1_700_000_000_000L),
            "timestamp" to SessionCipher.envelopeAad("alice", "bob", "env-1", 0, 1_600_000_000_000L)
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
        // "ab"+"c" and "a"+"bc" must give different AAD, thanks to the length prefixes
        assertNotEquals(
            SessionCipher.envelopeAad("ab", "c", "x", 0, 0).toList(),
            SessionCipher.envelopeAad("a", "bc", "x", 0, 0).toList()
        )
    }

    @Test
    fun differentCountersGiveDifferentNonces() {
        val nonces = (0 until 1000).map { aliceToBob.constructNonce(it).toList() }.toSet()
        assertEquals(1000, nonces.size)
        assertNotEquals(aliceToBob.constructNonce(5).toList(), bobFromAlice.constructNonce(5).toList())
    }
}
