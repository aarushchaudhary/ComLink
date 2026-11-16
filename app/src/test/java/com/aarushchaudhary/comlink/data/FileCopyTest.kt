package com.aarushchaudhary.comlink.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class FileCopyTest {

    private fun roundTrip(size: Int) {
        val data = Random(size).nextBytes(size)
        val out = ByteArrayOutputStream()
        val copied = FileCopy.copy(ByteArrayInputStream(data), out)
        assertEquals(size.toLong(), copied)
        assertArrayEquals("size $size", data, out.toByteArray())
    }

    @Test fun emptyFile() = roundTrip(0)
    @Test fun oneByte() = roundTrip(1)
    @Test fun exactlyOneBuffer() = roundTrip(64 * 1024)
    @Test fun tenMegabytes() = roundTrip(10_000_000)

    @Test
    fun suggestedNameHasNoFolderSeparators() {
        assertEquals("a_b_c.pdf", FileCopy.suggestedName("a/b\\c.pdf"))
        assertEquals("photo.jpg", FileCopy.suggestedName("photo.jpg"))
        assertEquals("comlink_file", FileCopy.suggestedName(null))
        assertEquals("comlink_file", FileCopy.suggestedName("   "))
        assertEquals(120, FileCopy.suggestedName("x".repeat(500)).length)
    }
}
