package com.aarushchaudhary.comlink.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class PacketAssemblerTest {

    private fun payload(size: Int, seed: Int = 1) = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun piece(packetId: Int, total: Int, index: Int, data: ByteArray = byteArrayOf(1, 2, 3)): ByteArray =
        ByteBuffer.allocate(PacketFraming.HEADER_SIZE + data.size)
            .putInt(packetId).put(total.toByte()).put(index.toByte()).put(data).array()

    @Test
    fun frameThenAssembleRoundTrip() {
        val data = payload(1234)
        val pieces = PacketFraming.frame(data, 100, 7)!!
        assertEquals(13, pieces.size)
        val assembler = PacketAssembler()
        val results = pieces.map { assembler.accept("A", it) }
        assertNull(results.dropLast(1).firstOrNull { it != null })
        assertArrayEquals(data, results.last())
        assertEquals(0, assembler.openGroupCount())
    }

    @Test
    fun frameRejectsEmptyAndOversizedPayloads() {
        assertNull(PacketFraming.frame(ByteArray(0), 100, 1))
        assertNull(PacketFraming.frame(ByteArray(256 * 10), 10, 1)) // 256 pieces > 255
        assertEquals(255, PacketFraming.frame(ByteArray(255 * 10), 10, 1)!!.size)
    }

    @Test
    fun twoSendersWithSamePacketIdDoNotMix() {
        val fromA = payload(300, seed = 1)
        val fromB = payload(300, seed = 2)
        val piecesA = PacketFraming.frame(fromA, 100, 0)!!
        val piecesB = PacketFraming.frame(fromB, 100, 0)!!
        val assembler = PacketAssembler()

        // Interleave A and B pieces that share packet ID 0
        assertNull(assembler.accept("A", piecesA[0]))
        assertNull(assembler.accept("B", piecesB[0]))
        assertNull(assembler.accept("A", piecesA[1]))
        assertNull(assembler.accept("B", piecesB[1]))
        assertArrayEquals(fromA, assembler.accept("A", piecesA[2]))
        assertArrayEquals(fromB, assembler.accept("B", piecesB[2]))
    }

    @Test
    fun indexOutOfRangeIsDroppedNotCrashing() {
        val assembler = PacketAssembler()
        // Group created with 2 pieces, then a piece claiming 5 pieces and index 4 (crashed the old code)
        assertNull(assembler.accept("A", piece(9, total = 2, index = 0)))
        assertNull(assembler.accept("A", piece(9, total = 5, index = 4)))
        // Index equal to total, and total of zero, are dropped
        assertNull(assembler.accept("A", piece(10, total = 3, index = 3)))
        assertNull(assembler.accept("A", piece(11, total = 0, index = 0)))
        // Too short to hold a header
        assertNull(assembler.accept("A", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun mismatchedCountRestartsTheGroup() {
        val assembler = PacketAssembler()
        assertNull(assembler.accept("A", piece(1, total = 3, index = 0, data = byteArrayOf(9))))
        // Same ID, new count: old group is discarded and a fresh 2-piece group starts
        assertNull(assembler.accept("A", piece(1, total = 2, index = 0, data = byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(1, 2), assembler.accept("A", piece(1, total = 2, index = 1, data = byteArrayOf(2))))
    }

    @Test
    fun duplicatePieceIsIgnored() {
        val assembler = PacketAssembler()
        assertNull(assembler.accept("A", piece(3, total = 2, index = 0, data = byteArrayOf(1))))
        assertNull(assembler.accept("A", piece(3, total = 2, index = 0, data = byteArrayOf(7))))
        assertArrayEquals(byteArrayOf(1, 2), assembler.accept("A", piece(3, total = 2, index = 1, data = byteArrayOf(2))))
    }

    @Test
    fun unfinishedGroupsExpire() {
        var now = 0L
        val assembler = PacketAssembler(maxAgeMs = 30_000L, clock = { now })
        assertNull(assembler.accept("A", piece(5, total = 2, index = 0)))
        assertEquals(1, assembler.openGroupCount())
        now = 30_001L
        // Any new piece triggers cleanup; the old half-packet is gone, so its second half can't complete it
        assertNull(assembler.accept("A", piece(5, total = 2, index = 1)))
        assertEquals(1, assembler.openGroupCount())
    }

    @Test
    fun openGroupsPerSenderAreCapped() {
        val assembler = PacketAssembler(maxOpenPerSender = 4)
        for (id in 0 until 10) assembler.accept("A", piece(id, total = 2, index = 0))
        assertEquals(4, assembler.openGroupCount())
        // Another sender is unaffected by A's flood
        assertNull(assembler.accept("B", piece(0, total = 2, index = 0, data = byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(1, 2), assembler.accept("B", piece(0, total = 2, index = 1, data = byteArrayOf(2))))
    }

    @Test
    fun forgetDropsOnlyThatSender() {
        val assembler = PacketAssembler()
        assembler.accept("A", piece(1, total = 2, index = 0))
        assembler.accept("B", piece(1, total = 2, index = 0))
        assembler.forget("A")
        assertEquals(1, assembler.openGroupCount())
    }
}
