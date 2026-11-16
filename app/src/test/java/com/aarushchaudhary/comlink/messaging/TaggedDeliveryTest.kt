package com.aarushchaudhary.comlink.messaging

import com.aarushchaudhary.comlink.bluetooth.MeshRouter
import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.crypto.TagDirectory
import com.aarushchaudhary.comlink.proto.Envelope
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alice -> relay -> Bob, end to end on the JVM: nothing on the wire names either of them. */
class TaggedDeliveryTest {

    private class Party {
        val priv: ByteArray = X25519.generatePrivateKey()
        val pub: ByteArray = X25519.publicFromPrivate(priv)
        fun cipherWith(other: Party) = SessionCipher(priv, pub, other.pub)
    }

    @Test
    fun bobFindsAndOpensAlicesEnvelopeAndTheRelayLearnsNothing() = runBlocking {
        val alice = Party(); val bob = Party(); val relay = Party(); val carol = Party()
        val wire = mutableListOf<ByteArray>()

        val aliceSender = EncryptedSender(
            loadCounter = { 0 }, saveCounter = { _, _ -> },
            cipherFor = { alice.cipherWith(bob) },
            transmit = { _, envelope, _, _ -> wire.add(envelope.encode()); true }
        )
        assertTrue(aliceSender.send("bob-id", "hi bob".toByteArray(), envelopeId = "m1"))
        val bytes = wire.single()

        // Plain text on the wire never contains a device ID
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("alice-id") || text.contains("bob-id"))

        // A relay with its own contacts doesn't recognise it, so passes it on
        val relayed = mutableListOf<ByteArray>()
        val relayRouter = MeshRouter(CoroutineScope(Dispatchers.Unconfined)).apply {
            val dir = TagDirectory(contacts = { mapOf("carol-id" to relay.cipherWith(carol), "alice-id" to relay.cipherWith(alice)) })
            senderOfTag = { dir.senderOf(it) }
            onMessageReceived = { _, _, _ -> error("not for the relay") }
            broadcastToNetwork = { relayed.add(it); true }
        }
        relayRouter.onBytesReceived(bytes, "ALICE-ADDR")
        assertEquals(1, relayed.size)

        // Bob knows alice (and carol): the tag tells him it's alice, and it decrypts
        var opened: String? = null
        val bobDir = TagDirectory(contacts = { mapOf("carol-id" to bob.cipherWith(carol), "alice-id" to bob.cipherWith(alice)) })
        val bobRouter = MeshRouter(CoroutineScope(Dispatchers.Unconfined)).apply {
            senderOfTag = { bobDir.senderOf(it) }
            onMessageReceived = { env: Envelope, senderId, _ ->
                assertEquals("alice-id", senderId)
                val aad = SessionCipher.envelopeAad(env.tag.toByteArray(), env.envelope_id, env.content_type, env.timestamp)
                val (padded, _) = bob.cipherWith(alice).decrypt(env.ciphertext.toByteArray(), env.nonce.toByteArray(), aad)
                opened = String(Padding.unpad(padded)!!)
            }
        }
        bobRouter.onBytesReceived(relayed.single(), "RELAY-ADDR")
        assertEquals("hi bob", opened)
    }
}
