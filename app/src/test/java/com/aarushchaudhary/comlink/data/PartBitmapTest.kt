package com.aarushchaudhary.comlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartBitmapTest {

    @Test
    fun encodeAndReadBack() {
        val bits = PartBitmap.encode(100, listOf(0, 7, 8, 63, 99))
        assertEquals(13, bits.size) // 100 parts fit in 13 bytes
        for (i in listOf(0, 7, 8, 63, 99)) assertTrue("part $i", PartBitmap.isHeld(bits, i))
        for (i in listOf(1, 6, 9, 62, 98)) assertFalse("part $i", PartBitmap.isHeld(bits, i))
    }

    @Test
    fun missingParts() {
        val bits = PartBitmap.encode(10, (0 until 10).filter { it != 3 && it != 9 })
        assertEquals(listOf(3, 9), PartBitmap.missing(bits, 10))
        assertEquals(emptyList<Int>(), PartBitmap.missing(PartBitmap.encode(10, 0 until 10), 10))
        assertEquals((0 until 10).toList(), PartBitmap.missing(PartBitmap.encode(10, emptyList()), 10))
    }

    @Test
    fun shortOrEmptyBitmapMeansMissing() {
        // A receiver that sent fewer bytes than needed holds none of the parts past them
        val bits = PartBitmap.encode(8, 0 until 8)  // 1 byte
        assertEquals((8 until 20).toList(), PartBitmap.missing(bits, 20))
        assertEquals((0 until 5).toList(), PartBitmap.missing(ByteArray(0), 5))
    }

    @Test
    fun outOfRangeIndexesAreIgnored() {
        val bits = PartBitmap.encode(4, listOf(-1, 2, 4, 1000))
        assertEquals(listOf(0, 1, 3), PartBitmap.missing(bits, 4))
        assertFalse(PartBitmap.isHeld(bits, -1))
    }
}
