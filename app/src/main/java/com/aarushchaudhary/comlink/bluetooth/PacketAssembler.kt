package com.aarushchaudhary.comlink.bluetooth

import java.nio.ByteBuffer

/**
 * BLE piece framing. Every piece starts with a 6-byte header:
 * [4 bytes packetId][1 byte totalChunks][1 byte chunkIndex], then the data.
 *
 * No Android imports, so this file can be unit-tested on the JVM.
 */
object PacketFraming {
    const val HEADER_SIZE = 6
    const val MAX_CHUNKS = 255

    /**
     * Splits [payload] into framed pieces of at most [chunkPayloadSize] data bytes each.
     * Returns null when the payload is empty or would need more than [MAX_CHUNKS] pieces.
     */
    fun frame(payload: ByteArray, chunkPayloadSize: Int, packetId: Int): List<ByteArray>? {
        require(chunkPayloadSize > 0) { "chunkPayloadSize must be positive" }
        if (payload.isEmpty()) return null
        val total = (payload.size + chunkPayloadSize - 1) / chunkPayloadSize
        if (total > MAX_CHUNKS) return null
        return List(total) { index ->
            val start = index * chunkPayloadSize
            val length = minOf(chunkPayloadSize, payload.size - start)
            ByteBuffer.allocate(HEADER_SIZE + length)
                .putInt(packetId)
                .put(total.toByte())
                .put(index.toByte())
                .put(payload, start, length)
                .array()
        }
    }
}

/**
 * Puts framed pieces back together into whole payloads.
 *
 * Pieces are grouped per sender, so two phones using the same packet ID never mix.
 * Bad pieces are dropped instead of crashing, unfinished groups expire after [maxAgeMs],
 * and each sender can have at most [maxOpenPerSender] unfinished groups at once.
 */
class PacketAssembler(
    private val maxAgeMs: Long = 30_000L,
    private val maxOpenPerSender: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Group(val total: Int, val createdAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var received = 0
    }

    // sender -> (packetId -> group), insertion-ordered so the oldest group is evicted first
    private val groups = HashMap<String, LinkedHashMap<Int, Group>>()

    /**
     * Adds one piece from [sender]. Returns the whole payload when this piece completes it,
     * otherwise null (also null for any malformed piece).
     */
    @Synchronized
    fun accept(sender: String, piece: ByteArray): ByteArray? {
        if (piece.size < PacketFraming.HEADER_SIZE) return null

        val header = ByteBuffer.wrap(piece)
        val packetId = header.int
        val total = header.get().toInt() and 0xFF
        val index = header.get().toInt() and 0xFF
        if (total == 0 || index >= total) return null

        val now = clock()
        dropExpired(now)

        val perSender = groups.getOrPut(sender) { LinkedHashMap() }
        var group = perSender[packetId]
        if (group != null && group.total != total) {
            // Same ID but a different piece count: the old group is stale (e.g. the sender restarted).
            perSender.remove(packetId)
            group = null
        }
        if (group == null) {
            if (perSender.size >= maxOpenPerSender) {
                val oldest = perSender.keys.iterator()
                oldest.next()
                oldest.remove()
            }
            group = Group(total, now)
            perSender[packetId] = group
        }

        if (group.chunks[index] == null) {
            group.chunks[index] = piece.copyOfRange(PacketFraming.HEADER_SIZE, piece.size)
            group.received++
        }
        if (group.received < total) return null

        perSender.remove(packetId)
        if (perSender.isEmpty()) groups.remove(sender)
        val out = ByteBuffer.allocate(group.chunks.sumOf { it!!.size })
        group.chunks.forEach { out.put(it!!) }
        return out.array()
    }

    /** Drops every unfinished group from [sender], e.g. when it disconnects. */
    @Synchronized
    fun forget(sender: String) {
        groups.remove(sender)
    }

    @Synchronized
    fun clear() {
        groups.clear()
    }

    /** Number of unfinished groups across all senders (for tests and diagnostics). */
    @Synchronized
    fun openGroupCount(): Int = groups.values.sumOf { it.size }

    private fun dropExpired(now: Long) {
        val senders = groups.values.iterator()
        while (senders.hasNext()) {
            val perSender = senders.next()
            perSender.values.removeAll { now - it.createdAt > maxAgeMs }
            if (perSender.isEmpty()) senders.remove()
        }
    }
}
