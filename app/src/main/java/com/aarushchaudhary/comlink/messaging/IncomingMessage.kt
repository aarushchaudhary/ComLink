package com.aarushchaudhary.comlink.messaging

import org.json.JSONObject

/**
 * What a decrypted, unpadded envelope from a contact is. Deciding this is kept free of Android
 * and the database, so it can be unit-tested (MessagingEngine then acts on the result).
 */
sealed interface IncomingMessage {
    class FileHeader(val payload: ByteArray) : IncomingMessage
    class FilePart(val payload: ByteArray) : IncomingMessage
    object Presence : IncomingMessage
    class FileStatus(val payload: ByteArray) : IncomingMessage
    data class Ack(val messageId: String) : IncomingMessage
    data class Read(val messageId: String) : IncomingMessage

    /** A chat bubble: plain text, or a location ("lat,lon"), optionally a reply. */
    data class Chat(
        val text: String,
        val isLocation: Boolean,
        val replyId: String? = null,
        val replySender: String? = null,
        val replySnippet: String? = null
    ) : IncomingMessage

    /** A content type this version doesn't know (e.g. from a newer app): ignored, never shown as text. */
    data class Unknown(val contentType: Int) : IncomingMessage

    companion object {
        private const val ACK_PREFIX = "ACK:"
        private const val READ_PREFIX = "READ:"
        private const val LOCATION_PREFIX = "LOCATION:"

        fun classify(contentType: Int, payload: ByteArray): IncomingMessage = when (contentType) {
            ContentTypes.FILE_HEADER -> FileHeader(payload)
            ContentTypes.FILE_PART -> FilePart(payload)
            ContentTypes.PRESENCE -> Presence
            ContentTypes.FILE_STATUS -> FileStatus(payload)
            ContentTypes.MESSAGE -> classifyMessage(String(payload, Charsets.UTF_8))
            else -> Unknown(contentType)
        }

        /** The inverse of [buildReplyPayload] plus the ACK/READ/LOCATION prefixes. */
        private fun classifyMessage(raw: String): IncomingMessage {
            if (raw.startsWith(ACK_PREFIX)) return Ack(raw.substringAfter(ACK_PREFIX))
            if (raw.startsWith(READ_PREFIX)) return Read(raw.substringAfter(READ_PREFIX))

            var text = raw
            var replyId: String? = null
            var replySender: String? = null
            var replySnippet: String? = null
            if (raw.startsWith("{")) {
                try {
                    val json = JSONObject(raw)
                    if (json.has("text")) {
                        text = json.getString("text")
                        replyId = json.optString("replyId", "").takeIf { it.isNotEmpty() }
                        replySender = json.optString("replySender", "").takeIf { it.isNotEmpty() }
                        replySnippet = json.optString("replySnippet", "").takeIf { it.isNotEmpty() }
                    }
                } catch (e: Exception) {
                    // Not JSON after all: show it as typed
                }
            }
            val isLocation = text.startsWith(LOCATION_PREFIX)
            if (isLocation) text = text.substringAfter(LOCATION_PREFIX)
            return Chat(text, isLocation, replyId, replySender, replySnippet)
        }
    }
}
