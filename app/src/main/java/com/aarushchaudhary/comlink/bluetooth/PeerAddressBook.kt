package com.aarushchaudhary.comlink.bluetooth

import java.util.concurrent.ConcurrentHashMap

/**
 * Which Bluetooth address belongs to which contact.
 *
 * Two kinds of entry:
 *  - claims: a neighbour said "I'm X" in a plain-text HELLO/PING. Anyone can say that, so claims
 *    only decide who gets a PRESENCE and whose disconnect we report.
 *  - trusted: X's encrypted PRESENCE decrypted on a write from that address. Only these are used
 *    for direct sends, so a fake HELLO can't redirect someone's messages.
 *
 * Thread-safe, no Android imports.
 */
class PeerAddressBook {
    private val claimed = ConcurrentHashMap<String, String>() // address -> claimed device ID
    private val trusted = ConcurrentHashMap<String, String>() // device ID -> proven address

    fun claim(address: String, peerId: String) {
        claimed[address] = peerId
    }

    fun claimedPeerAt(address: String): String? = claimed[address]

    fun trust(peerId: String, address: String) {
        trusted[peerId] = address
    }

    fun trustedAddressOf(peerId: String): String? = trusted[peerId]

    /** The link at [address] is gone: it no longer proves anyone. */
    fun forgetTrust(address: String) {
        trusted.entries.removeAll { it.value == address }
    }

    fun clearTrust() {
        trusted.clear()
    }
}
