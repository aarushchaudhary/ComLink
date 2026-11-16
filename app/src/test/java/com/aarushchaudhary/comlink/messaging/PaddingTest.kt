package com.aarushchaudhary.comlink.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PaddingTest {

    private fun data(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun roundTripAtEveryBucketEdge() {
        val sizes = mutableListOf(0, 1, 100_000, 102_396, 102_397, 150_000)
        for (b in Padding.BUCKETS) sizes += listOf(b - 5, b - 4, b - 3)
        for (n in sizes.filter { it >= 0 }) {
            val padded = Padding.pad(data(n))
            assertArrayEquals("size $n", data(n), Padding.unpad(padded))
        }
    }

    @Test
    fun paddedSizesOnlyLandOnBuckets() {
        for (n in listOf(0, 1, 10, 200, 252, 253, 900, 3_000, 10_000, 20_000, 50_000, 100_000, 102_396)) {
            val size = Padding.pad(data(n)).size
            assertTrue("size $n padded to $size", size in Padding.BUCKETS)
        }
    }

    @Test
    fun bucketBoundaries() {
        assertEquals(256, Padding.pad(data(0)).size)
        assertEquals(256, Padding.pad(data(252)).size)    // 252 + 4-byte length = 256
        assertEquals(1024, Padding.pad(data(253)).size)
        // A full 100 KB file part (plus its protobuf fields) fits the largest bucket
        assertEquals(100 * 1024, Padding.pad(data(100_060)).size)
        // Bigger than every bucket: rounded up to 4 KiB, still recoverable
        assertEquals(0, Padding.pad(data(150_000)).size % 4096)
    }

    @Test
    fun shortTextAndReadReceiptLookTheSame() {
        assertEquals(Padding.pad("hi".toByteArray()).size, Padding.pad("READ:0f8c1c2e-1c4a-4c56-9d1e-6b1d2f3a4b5c".toByteArray()).size)
    }

    @Test
    fun badLengthPrefixIsRejected() {
        assertNull(Padding.unpad(ByteArray(3)))
        assertNull(Padding.unpad(ByteBuffer.allocate(256).putInt(253).array()))  // claims more than it holds
        assertNull(Padding.unpad(ByteBuffer.allocate(256).putInt(-1).array()))
    }
}
