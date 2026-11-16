package com.aarushchaudhary.comlink.bluetooth

import java.util.concurrent.ConcurrentHashMap

/**
 * Which Bluetooth address belongs to which contact.
 *
 * HELLO and PING name nobody, so the only way in is proof: a contact's encrypted PRESENCE
 * decrypted on a write from that address. Only these addresses are used for direct sends.
 *
 * Thread-safe, no Android imports.
 */
class PeerAddressBook {
    private val trusted = ConcurrentHashMap<String, String>() // device ID -> proven address

    fun trust(peerId: String, address: String) {
        trusted[peerId] = address
    }

    fun trustedAddressOf(peerId: String): String? = trusted[peerId]

    /** The contacts proven to be at [address] (usually one). */
    fun peersAt(address: String): List<String> = trusted.entries.filter { it.value == address }.map { it.key }

    /** The link at [address] is gone: it no longer proves anyone. Returns who was there. */
    fun forgetTrust(address: String): List<String> {
        val gone = peersAt(address)
        gone.forEach { trusted.remove(it, address) }
        return gone
    }

    fun clearTrust() {
        trusted.clear()
    }
}
