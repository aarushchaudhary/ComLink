package com.aarushchaudhary.comlink.crypto

import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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

    @Test
    fun roundTripBetweenTwoParties() {
        val message = "hello over the mesh".toByteArray()
        val ciphertext = aliceToBob.encrypt(message, 42)
        val (plaintext, counter) = bobFromAlice.decrypt(ciphertext, aliceToBob.constructNonce(42))
        assertArrayEquals(message, plaintext)
        assertEquals(42, counter)
    }

    @Test
    fun bothDirectionsWork() {
        val reply = "reply".toByteArray()
        val ciphertext = bobFromAlice.encrypt(reply, 7)
        val (plaintext, _) = aliceToBob.decrypt(ciphertext, bobFromAlice.constructNonce(7))
        assertArrayEquals(reply, plaintext)
    }

    @Test(expected = SecurityException::class)
    fun ownMessageReflectedBackIsRejected() {
        // Same direction byte as our own sends: a reflected message must not decrypt
        val ciphertext = aliceToBob.encrypt("x".toByteArray(), 1)
        aliceToBob.decrypt(ciphertext, aliceToBob.constructNonce(1))
    }

    @Test(expected = SecurityException::class)
    fun messageFromAnotherSessionIsRejected() {
        val mallory = Party()
        val malloryToBob = mallory.cipherWith(bob)
        val ciphertext = malloryToBob.encrypt("x".toByteArray(), 1)
        // Bob tries it against his session with Alice: the salt doesn't match
        bobFromAlice.decrypt(ciphertext, malloryToBob.constructNonce(1))
    }

    @Test(expected = javax.crypto.AEADBadTagException::class)
    fun tamperedCiphertextIsRejected() {
        val ciphertext = aliceToBob.encrypt("pay 10".toByteArray(), 3)
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        bobFromAlice.decrypt(ciphertext, aliceToBob.constructNonce(3))
    }

    @Test
    fun differentCountersGiveDifferentNonces() {
        val nonces = (0 until 1000).map { aliceToBob.constructNonce(it).toList() }.toSet()
        assertEquals(1000, nonces.size)
        assertNotEquals(aliceToBob.constructNonce(5).toList(), bobFromAlice.constructNonce(5).toList())
    }
}
