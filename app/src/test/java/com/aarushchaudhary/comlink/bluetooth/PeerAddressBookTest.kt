package com.aarushchaudhary.comlink.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerAddressBookTest {

    @Test
    fun unknownUntilPresenceProvesTheAddress() {
        val book = PeerAddressBook()
        assertNull(book.trustedAddressOf("bob"))
        assertTrue(book.peersAt("BB").isEmpty())
        book.trust("bob", "BB") // bob's real PRESENCE decrypted on a write from BB
        assertEquals("BB", book.trustedAddressOf("bob"))
        assertEquals(listOf("bob"), book.peersAt("BB"))
    }

    @Test
    fun disconnectDropsTrustForThatAddressOnlyAndSaysWhoLeft() {
        val book = PeerAddressBook()
        book.trust("bob", "BB")
        book.trust("carol", "CC")
        assertEquals(listOf("bob"), book.forgetTrust("BB"))
        assertNull(book.trustedAddressOf("bob"))
        assertEquals("CC", book.trustedAddressOf("carol"))
        assertTrue(book.forgetTrust("BB").isEmpty())
        book.clearTrust()
        assertNull(book.trustedAddressOf("carol"))
    }

    @Test
    fun aContactThatMovedIsNotForgottenByTheOldAddress() {
        val book = PeerAddressBook()
        book.trust("bob", "BB")
        book.trust("bob", "B2") // bob's phone now reached through a new address
        assertTrue(book.forgetTrust("BB").isEmpty())
        assertEquals("B2", book.trustedAddressOf("bob"))
    }
}
