package com.aarushchaudhary.comlink.bluetooth

import android.util.Log
import com.aarushchaudhary.comlink.proto.Envelope
import java.util.Collections
import java.util.LinkedHashMap

class MeshRouter(val localDeviceId: String) {

    // Listener for messages that are destined for this device
    var onMessageReceived: ((Envelope) -> Unit)? = null
    
    // Callback to physically broadcast an envelope to all active Bluetooth connections
    var broadcastToNetwork: ((ByteArray) -> Unit)? = null

    // LRU Cache to prevent broadcast storms (Max 10,000 envelope IDs)
    private val seenEnvelopes = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(1000, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 10000
            }
        }
    )

    /**
     * Called by the Bluetooth layer whenever a raw byte payload is received from any socket.
     */
    fun onBytesReceived(payload: ByteArray) {
        val envelope = try {
            Envelope.ADAPTER.decode(payload)
        } catch (e: Exception) {
            Log.e("MeshRouter", "Failed to decode envelope, dropping.", e)
            return
        }

        // 1. Check LRU cache to prevent broadcast storms
        if (seenEnvelopes.containsKey(envelope.envelope_id)) {
            return // Already seen this envelope, drop it
        }

        // Add to cache
        seenEnvelopes[envelope.envelope_id] = true

        // 2. Check if the message is for this device
        if (envelope.recipient_id == localDeviceId) {
            // Pass to crypto layer for decryption
            onMessageReceived?.invoke(envelope)
        } else {
            // 3. Multi-hop Relay Logic
            // We never attempt to decrypt ciphertext if we are not the recipient
            val newTtl = (envelope.ttl ?: 0) - 1
            if (newTtl > 0) {
                // Rebuild envelope with decremented TTL
                val relayedEnvelope = envelope.copy(ttl = newTtl)
                val encodedBytes = relayedEnvelope.encode()
                
                // Broadcast to all connected sockets
                broadcastToNetwork?.invoke(encodedBytes)
            }
        }
    }

    /**
     * Submits a newly created local envelope to the mesh network.
     */
    fun sendLocalMessage(envelope: Envelope) {
        // Record it so we don't rebroadcast it if it echoes back
        seenEnvelopes[envelope.envelope_id] = true
        broadcastToNetwork?.invoke(envelope.encode())
    }
}
