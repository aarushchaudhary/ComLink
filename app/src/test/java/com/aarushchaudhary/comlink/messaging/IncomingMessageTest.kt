package com.aarushchaudhary.comlink.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageTest {

    private fun msg(text: String) = IncomingMessage.classify(ContentTypes.MESSAGE, text.toByteArray())

    @Test
    fun plainText() {
        assertEquals(IncomingMessage.Chat("hello", isLocation = false), msg("hello"))
    }

    @Test
    fun ackAndRead() {
        assertEquals(IncomingMessage.Ack("abc-123"), msg("ACK:abc-123"))
        assertEquals(IncomingMessage.Read(listOf("abc-123")), msg("READ:abc-123"))
        // One receipt for several messages
        assertEquals(IncomingMessage.Read(listOf("a-1", "b-2", "c-3")), msg("READ:a-1,b-2,c-3"))
        assertEquals(IncomingMessage.Read(emptyList()), msg("READ:"))
    }

    @Test
    fun locationWithAndWithoutReply() {
        assertEquals(IncomingMessage.Chat("52.5,13.4", isLocation = true), msg("LOCATION:52.5,13.4"))
        val reply = buildReplyPayload("LOCATION:52.5,13.4", "m1", "You", "where?")
        assertEquals(IncomingMessage.Chat("52.5,13.4", true, "m1", "You", "where?"), msg(reply))
    }

    @Test
    fun replyRoundTripsThroughTheSenderFormat() {
        val reply = buildReplyPayload("sure", "m9", "Bob", "lunch?")
        assertEquals(IncomingMessage.Chat("sure", false, "m9", "Bob", "lunch?"), msg(reply))
        // No reply fields: the sender sends the bare text
        assertEquals("plain", buildReplyPayload("plain", null, null, null))
    }

    @Test
    fun braceTextThatIsNotAReplyStaysAsTyped() {
        assertEquals(IncomingMessage.Chat("{not json", false), msg("{not json"))
        assertEquals(IncomingMessage.Chat("{\"a\":1}", false), msg("{\"a\":1}")) // JSON without "text"
    }

    @Test
    fun fileAndControlTypes() {
        val bytes = byteArrayOf(1, 2, 3)
        val header = IncomingMessage.classify(ContentTypes.FILE_HEADER, bytes)
        assertTrue(header is IncomingMessage.FileHeader)
        assertArrayEquals(bytes, (header as IncomingMessage.FileHeader).payload)
        assertTrue(IncomingMessage.classify(ContentTypes.FILE_PART, bytes) is IncomingMessage.FilePart)
        assertTrue(IncomingMessage.classify(ContentTypes.FILE_STATUS, bytes) is IncomingMessage.FileStatus)
        assertSame(IncomingMessage.Presence, IncomingMessage.classify(ContentTypes.PRESENCE, bytes))
    }

    @Test
    fun unknownTypeIsNeverShownAsText() {
        // Before, any unknown content type fell through and became a chat bubble
        assertEquals(IncomingMessage.Unknown(7), IncomingMessage.classify(7, "looks like text".toByteArray()))
    }
}
