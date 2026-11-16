package com.aarushchaudhary.comlink.messaging

/** `Envelope.content_type` values (see comlink.proto). One list for every sender and receiver. */
object ContentTypes {
    /** Text, location, ACK and READ (the default when the field is absent). */
    const val MESSAGE = 0
    const val FILE_HEADER = 1
    const val FILE_PART = 2
    /** Encrypted "I'm here", ttl 1: the only thing that marks a contact online. */
    const val PRESENCE = 3
    /** Which parts of a transfer the receiver holds, or a request for that. */
    const val FILE_STATUS = 4
}
