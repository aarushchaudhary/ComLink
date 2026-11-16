package com.aarushchaudhary.comlink.crypto

/**
 * Answers "which contact sent this envelope?" from its rotating tag (or null: not for us, relay it).
 *
 * Holds the incoming tags of every contact for the previous, current and next 15-minute window,
 * so phones whose clocks are up to 15 minutes apart still find each other. Rebuilt when the
 * window changes or a contact is added or changes key ([invalidate]). That costs 3 HMACs per
 * contact once per window; a lookup is one map read, cheap enough for every envelope that passes.
 *
 * No Android imports, so it can be unit-tested.
 */
class TagDirectory(
    /** Every contact that has a session: contact ID -> cipher. Read on each rebuild. */
    private val contacts: () -> Map<String, SessionCipher>,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var builtForWindow = Long.MIN_VALUE
    private var index: Map<String, String> = emptyMap() // tag (hex) -> contact ID

    /** A contact was added, removed or changed key: rebuild before the next lookup. */
    @Synchronized
    fun invalidate() {
        builtForWindow = Long.MIN_VALUE
    }

    @Synchronized
    fun senderOf(tag: ByteArray): String? {
        if (tag.size != SessionCipher.TAG_SIZE) return null
        val window = SessionCipher.tagWindow(clock())
        if (window != builtForWindow) {
            index = buildMap {
                for ((peerId, cipher) in contacts()) {
                    for (w in window - 1..window + 1) put(cipher.incomingTag(w).toHex(), peerId)
                }
            }
            builtForWindow = window
        }
        return index[tag.toHex()]
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
