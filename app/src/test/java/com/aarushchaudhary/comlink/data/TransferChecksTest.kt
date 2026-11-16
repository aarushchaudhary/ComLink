package com.aarushchaudhary.comlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TransferChecksTest {

    private val id = UUID.randomUUID().toString()
    private val msg = UUID.randomUUID().toString()

    @Test
    fun onlyCanonicalUuidsAreAccepted() {
        assertTrue(TransferChecks.isCanonicalUuid(id))
        assertFalse(TransferChecks.isCanonicalUuid("../.."))
        assertFalse(TransferChecks.isCanonicalUuid("../../shared_prefs/x"))
        assertFalse(TransferChecks.isCanonicalUuid(""))
        assertFalse(TransferChecks.isCanonicalUuid("$id/../x"))
        assertFalse(TransferChecks.isCanonicalUuid(id.uppercase()))  // not the form UUID.toString() gives
        assertFalse(TransferChecks.isCanonicalUuid("1-1-1-1-1"))     // parses, but isn't canonical
    }

    @Test
    fun validHeaderIsAccepted() {
        assertTrue(TransferChecks.isValidHeader(id, msg, 250_000, 3))
        assertTrue(TransferChecks.isValidHeader(id, msg, 0, 1))
        assertTrue(TransferChecks.isValidHeader(id, msg, TransferChecks.MAX_FILE_SIZE, TransferChecks.MAX_PARTS))
    }

    @Test
    fun badHeadersAreRejected() {
        assertFalse(TransferChecks.isValidHeader("../..", msg, 100, 1))
        assertFalse(TransferChecks.isValidHeader(id, "../..", 100, 1))
        assertFalse(TransferChecks.isValidHeader(id, msg, TransferChecks.MAX_FILE_SIZE + 1, 101))
        assertFalse(TransferChecks.isValidHeader(id, msg, -1, 1))
        assertFalse(TransferChecks.isValidHeader(id, msg, 250_000, 0))
        assertFalse(TransferChecks.isValidHeader(id, msg, 250_000, 1_000_000)) // oversized part count
        assertFalse(TransferChecks.isValidHeader(id, msg, 250_000, 4))         // doesn't match the size
    }

    @Test
    fun partChecks() {
        assertTrue(TransferChecks.isValidPart(id, 0, 3, 100_000))
        assertTrue(TransferChecks.isValidPart(id, 2, 3, 1))
        assertFalse(TransferChecks.isValidPart(id, 3, 3, 10))       // index == count
        assertFalse(TransferChecks.isValidPart(id, -1, 3, 10))
        assertFalse(TransferChecks.isValidPart(id, 0, 3, 0))        // empty
        assertFalse(TransferChecks.isValidPart(id, 0, 3, 100_001))  // bigger than a part
        assertFalse(TransferChecks.isValidPart("../..", 0, 3, 10))
    }

    @Test
    fun expectedPartCountMatchesSender() {
        assertEquals(1, TransferChecks.expectedPartCount(0))
        assertEquals(1, TransferChecks.expectedPartCount(100_000))
        assertEquals(2, TransferChecks.expectedPartCount(100_001))
        assertEquals(100, TransferChecks.MAX_PARTS)
    }
}
