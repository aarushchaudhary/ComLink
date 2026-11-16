package com.aarushchaudhary.comlink.bluetooth

import android.util.Log
import com.aarushchaudhary.comlink.proto.Envelope
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MeshRouter(val localDeviceId: String, private val scope: CoroutineScope) {

    companion object {
        /** Most hops a relayed envelope may still make; our own messages start at 10 too. */
        const val MAX_TTL = 10
    }

    // Listener for messages that are destined for this device. [fromAddress] is the Bluetooth address
    // of the neighbour that handed it over (for PRESENCE: the contact's own address).
    var onMessageReceived: ((envelope: Envelope, fromAddress: String) -> Unit)? = null
    
    // Callback to physically broadcast an envelope to all active Bluetooth connections
    var broadcastToNetwork: (suspend (ByteArray) -> Boolean)? = null

    // Callback to send directly to a specific peer (bypasses broadcast for file transfers)
    var sendDirectToNetwork: (suspend (peerId: String, ByteArray, noResponse: Boolean) -> Boolean)? = null

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
     * [fromAddress] is the address of the neighbour it came from.
     */
    fun onBytesReceived(payload: ByteArray, fromAddress: String) {
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
            onMessageReceived?.invoke(envelope, fromAddress)
        } else {
            // 3. Multi-hop Relay Logic
            // We never attempt to decrypt ciphertext if we are not the recipient
            // Capped at MAX_TTL, so a sender can't make an envelope hop (and flood the mesh) forever
            val newTtl = minOf((envelope.ttl ?: 0) - 1, MAX_TTL)
            if (newTtl > 0) {
                // Rebuild envelope with decremented TTL
                val relayedEnvelope = envelope.copy(ttl = newTtl)
                val encodedBytes = relayedEnvelope.encode()
                // Broadcast to all connected sockets in background
                scope.launch {
                    broadcastToNetwork?.invoke(encodedBytes)
                }
            }
        }
    }

    /**
     * Submits a newly created local envelope to the mesh network.
     * Returns true if transmitted successfully.
     */
    suspend fun sendLocalMessage(envelope: Envelope): Boolean {
        // Record it so we don't rebroadcast it if it echoes back
        seenEnvelopes[envelope.envelope_id] = true
        return broadcastToNetwork?.invoke(envelope.encode()) ?: false
    }

    /**
     * Send an envelope directly to a specific peer. Uses targeted BLE connection
     * instead of broadcasting. Falls back to broadcast if direct send unavailable.
     */
    suspend fun sendDirectMessage(peerId: String, envelope: Envelope, noResponse: Boolean = false): Boolean {
        seenEnvelopes[envelope.envelope_id] = true
        val encoded = envelope.encode()
        return sendDirectToNetwork?.invoke(peerId, encoded, noResponse) 
            ?: broadcastToNetwork?.invoke(encoded) 
            ?: false
    }
}
