package com.aarushchaudhary.comlink.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    private fun fresh() = ReplayWindow.restore(-1, null)

    @Test
    fun inOrderCountersAreAccepted() {
        val w = fresh()
        for (c in 0 until 1000) assertTrue("counter $c", w.accept(c))
        assertEquals(999, w.highest)
    }

    @Test
    fun outOfOrderInsideWindowIsAccepted() {
        val w = fresh()
        assertTrue(w.accept(10))
        assertTrue(w.accept(5))
        assertTrue(w.accept(9))
        assertTrue(w.accept(0))
        assertEquals(10, w.highest)
    }

    @Test
    fun duplicatesAreRejected() {
        val w = fresh()
        assertTrue(w.accept(3))
        assertFalse(w.accept(3))
        assertTrue(w.accept(1))
        assertFalse(w.accept(1))
        assertTrue(w.accept(200))
        assertFalse(w.accept(3))   // still remembered after the window moved
        assertFalse(w.accept(200))
    }

    @Test
    fun tooOldIsRejected() {
        val w = fresh()
        assertTrue(w.accept(300))
        assertFalse(w.accept(300 - ReplayWindow.SIZE))      // just outside
        assertTrue(w.accept(300 - ReplayWindow.SIZE + 1))   // oldest slot still inside
        assertFalse(w.accept(-1))
    }

    @Test
    fun jumpLargerThanWindowClearsOldMarks() {
        val w = fresh()
        assertTrue(w.accept(0))
        assertTrue(w.accept(1))
        assertTrue(w.accept(1000))
        // 999 was never seen and is inside the new window
        assertTrue(w.accept(999))
        // 0 and 1 are now far outside the window
        assertFalse(w.accept(1))
    }

    @Test
    fun saveAndRestoreKeepsState() {
        val w = fresh()
        listOf(50, 48, 47).forEach { assertTrue(w.accept(it)) }
        val restored = ReplayWindow.restore(w.highest, w.toBytes())
        assertFalse(restored.accept(48))
        assertFalse(restored.accept(50))
        assertTrue(restored.accept(49))
        assertTrue(restored.accept(51))
    }

    @Test
    fun upgradeFromOldStrictRuleRejectsEverythingUpToHighest() {
        // Session saved before this change: highest = 20, no window stored
        val w = ReplayWindow.restore(20, null)
        assertFalse(w.accept(20))
        assertFalse(w.accept(5))
        assertTrue(w.accept(21))
        // After the upgrade, out-of-order arrivals work normally
        assertTrue(w.accept(25))
        assertTrue(w.accept(23))
    }
}
