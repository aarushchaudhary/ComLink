package com.aarushchaudhary.comlink.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerAddressBookTest {

    @Test
    fun aHelloAloneNeverEnablesDirectSends() {
        val book = PeerAddressBook()
        // An attacker's phone says "I'm bob" in plain text
        book.claim("AA:AA:AA:AA:AA:AA", "bob")
        assertEquals("bob", book.claimedPeerAt("AA:AA:AA:AA:AA:AA"))
        // ...but direct sends to bob still have no address, so they go by broadcast
        assertNull(book.trustedAddressOf("bob"))
    }

    @Test
    fun presenceProvesTheAddress() {
        val book = PeerAddressBook()
        book.claim("AA:AA:AA:AA:AA:AA", "bob")          // fake claim
        book.trust("bob", "BB:BB:BB:BB:BB:BB")          // bob's real PRESENCE decrypted here
        assertEquals("BB:BB:BB:BB:BB:BB", book.trustedAddressOf("bob"))
        // A later fake HELLO doesn't move it
        book.claim("AA:AA:AA:AA:AA:AA", "bob")
        assertEquals("BB:BB:BB:BB:BB:BB", book.trustedAddressOf("bob"))
    }

    @Test
    fun disconnectDropsTrustForThatAddressOnly() {
        val book = PeerAddressBook()
        book.trust("bob", "BB")
        book.trust("carol", "CC")
        book.forgetTrust("BB")
        assertNull(book.trustedAddressOf("bob"))
        assertEquals("CC", book.trustedAddressOf("carol"))
        book.clearTrust()
        assertNull(book.trustedAddressOf("carol"))
    }
}
