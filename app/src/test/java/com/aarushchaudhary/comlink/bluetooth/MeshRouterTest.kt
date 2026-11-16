package com.aarushchaudhary.comlink.bluetooth

import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterTest {

    private val ourTag = ByteArray(16) { 1 }
    private val otherTag = ByteArray(16) { 2 }

    private val delivered = mutableListOf<Pair<String, String>>() // envelope ID, sender
    private val relayed = mutableListOf<Envelope>()

    private val router = MeshRouter(CoroutineScope(Dispatchers.Unconfined)).apply {
        senderOfTag = { tag -> if (tag.contentEquals(ourTag)) "bob" else null }
        onMessageReceived = { envelope, senderId, _ -> delivered.add(envelope.envelope_id to senderId) }
        broadcastToNetwork = { bytes -> relayed.add(Envelope.ADAPTER.decode(bytes)); true }
    }

    private fun envelope(id: String, tag: ByteArray, ttl: Int = 9) =
        Envelope(envelope_id = id, tag = tag.toByteString(), ttl = ttl).encode()

    @Test
    fun aRetryWithTheSameIdIsStillDeliveredToUs() {
        router.onBytesReceived(envelope("m1", ourTag), "AA")
        router.onBytesReceived(envelope("m1", ourTag), "AA") // the sender's retry: same message ID
        assertEquals(listOf("m1" to "bob", "m1" to "bob"), delivered)
        assertTrue("ours is never relayed", relayed.isEmpty())
    }

    @Test
    fun othersAreRelayedOnceWithOneHopLess() {
        router.onBytesReceived(envelope("x1", otherTag, ttl = 9), "AA")
        router.onBytesReceived(envelope("x1", otherTag, ttl = 9), "CC") // same envelope, other path
        assertEquals(1, relayed.size)
        assertEquals(8, relayed.single().ttl)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun ourOwnEnvelopeEchoingBackIsNotRelayedAgain() = runBlocking {
        router.sendLocalMessage(Envelope.ADAPTER.decode(envelope("mine", otherTag)))
        relayed.clear()
        router.onBytesReceived(envelope("mine", otherTag), "AA")
        assertTrue(relayed.isEmpty())
    }

    @Test
    fun lastHopIsNotRelayed() {
        router.onBytesReceived(envelope("p1", otherTag, ttl = 1), "AA") // e.g. someone else's PRESENCE
        assertTrue(relayed.isEmpty())
    }
}
