package com.aarushchaudhary.comlink.messaging

import org.json.JSONObject

/**
 * The plaintext sent for a chat message: the bare text, or a JSON object
 * `{"text", "replyId", "replySender", "replySnippet"}` when it is a reply.
 * The receiver's parser in ComLinkViewModel.processIncomingEnvelope reads both forms.
 */
fun buildReplyPayload(
    text: String,
    replyId: String?,
    replySender: String?,
    replySnippet: String?
): String {
    if (replyId == null && replySender == null && replySnippet == null) return text
    val json = JSONObject()
    json.put("text", text)
    replyId?.let { json.put("replyId", it) }
    replySender?.let { json.put("replySender", it) }
    replySnippet?.let { json.put("replySnippet", it) }
    return json.toString()
}
