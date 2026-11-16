package com.aarushchaudhary.comlink.messaging

import android.net.Uri
import android.util.Base64
import com.aarushchaudhary.comlink.ComLinkApp
import com.aarushchaudhary.comlink.crypto.ContactQr
import com.aarushchaudhary.comlink.crypto.ReplayWindow
import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.crypto.TagDirectory
import com.aarushchaudhary.comlink.data.FileTransferManager
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.data.PeerEntity
import com.aarushchaudhary.comlink.data.SessionStateEntity
import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * All messaging logic: sending, receiving, retries, presence and contacts. Owned by [ComLinkApp]
 * and running on the app's own [scope], so it keeps working after the chat screen is closed,
 * for as long as Android keeps the process alive.
 */
class MessagingEngine(
    private val app: ComLinkApp,
    private val scope: CoroutineScope
) {

    companion object {
        const val SAVED_MESSAGES_ID = "__SAVED__"

        /** PRESENCE goes to every nearby contact this often. */
        private const val PRESENCE_INTERVAL_MS = 30_000L
        /** A contact is offline after this long without a PRESENCE (2.5 intervals, so one loss is OK). */
        private const val OFFLINE_AFTER_MS = 75_000L
        /** A contact proven nearby (PRESENCE, or a PING from their proven address) within this window. */
        private const val NEARBY_WINDOW_MS = 60_000L
        /** At most one PRESENCE per contact per this long, however many HELLO/PINGs arrive. */
        private const val PRESENCE_MIN_GAP_MS = 20_000L
        /** An unknown neighbour gets one PRESENCE per contact at most this often (it might be any of them). */
        private const val STRANGER_PRESENCE_GAP_MS = 60_000L
        /** READ receipts per envelope (36-character IDs: 40 of them fit the 4 KB padding bucket). */
        private const val READ_BATCH = 40
        private val PRESENCE_PAYLOAD = "PRESENCE".toByteArray(Charsets.UTF_8)
    }

    private val dao = app.database.comLinkDao()
    private val identity = app.identityManager
    private val router = app.meshRouter

    private val sessionCiphers = ConcurrentHashMap<String, SessionCipher>()

    // Contacts with a resend already running, so overlapping triggers don't run it twice
    private val resendInFlight = ConcurrentHashMap.newKeySet<String>()

    // Incoming envelopes are handled one at a time per contact. A separate lock from the
    // sender's, so a slow Bluetooth send never delays reading what that contact sends us.
    private val receiveLocks = ConcurrentHashMap<String, Mutex>()

    // Contact -> last time they were proven nearby (their PRESENCE, or a PING from their proven
    // address). Decides who gets the 30 s PRESENCE. Known contacts only, so it can't grow without bound.
    private val nearbySeenAt = ConcurrentHashMap<String, Long>()
    // Contact -> last time we sent them a PRESENCE
    private val presenceSentAt = ConcurrentHashMap<String, Long>()
    // Unknown neighbour address -> last time it got our PRESENCEs (pruned, see onNeighbourSeen)
    private val strangerPresenceAt = ConcurrentHashMap<String, Long>()

    // Tag -> contact, for every contact whose key is loaded in sessionCiphers
    private val tagDirectory = TagDirectory(contacts = { HashMap(sessionCiphers) })

    /**
     * The session cipher for [peerId], or null when there is none: unknown contact, the local Saved
     * Messages chat (it has no key), or a stored key that isn't valid (e.g. a junk contact added by
     * an old version). Never throws: an exception here used to crash the app.
     */
    private suspend fun getOrCreateCipher(peerId: String): SessionCipher? {
        if (peerId == SAVED_MESSAGES_ID) return null
        sessionCiphers[peerId]?.let { return it }
        val peer = dao.getPeer(peerId) ?: return null
        val cipher = try {
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            SessionCipher(identity.getPrivateKey(), identity.getPublicKey(), peerPubKey)
        } catch (e: Exception) {
            android.util.Log.w("MessagingEngine", "No usable key for contact $peerId", e)
            return null
        }
        return sessionCiphers.putIfAbsent(peerId, cipher) ?: cipher
    }

    // Every outgoing envelope goes through this one sender (it owns the per-contact locks)
    private val sender = EncryptedSender(
        loadCounter = { peerId -> dao.getSessionState(peerId)?.myNextCounter },
        saveCounter = { peerId, next -> dao.updateMyCounter(peerId, next) },
        cipherFor = { peerId -> getOrCreateCipher(peerId) },
        transmit = { peerId, envelope, direct, noResponse ->
            if (direct) router.sendDirectMessage(peerId, envelope, noResponse)
            else router.sendLocalMessage(envelope)
        }
    )

    // FileTransferManager — created once, sends through the shared sender
    val fileTransferManager = FileTransferManager(
        context = app,
        dao = dao,
        sender = sender,
        scope = scope,
        crypto = app.fileCrypto
    )

    init {
        // Wired once for the whole app process, not per screen: a closed screen can no longer
        // swallow incoming messages
        router.senderOfTag = { tag -> tagDirectory.senderOf(tag) }
        router.onMessageReceived = { envelope, senderId, fromAddress ->
            processIncomingEnvelope(envelope, senderId, fromAddress)
        }

        // Every contact's key, so their tags are recognised from the first envelope on
        scope.launch(Dispatchers.IO) {
            dao.getAllPeers().first().forEach { getOrCreateCipher(it.deviceId) }
            tagDirectory.invalidate()
        }

        // HELLO/PING names nobody: answer with PRESENCE. The contact shows online only once
        // *their* PRESENCE decrypts (handlePresence).
        app.bluetoothService.onNeighbourSeen = { address ->
            scope.launch(Dispatchers.IO) { onNeighbourSeen(address) }
        }
        
        app.bluetoothService.onPeerDisconnected = { peerId ->
            scope.launch(Dispatchers.IO) {
                dao.updatePeerConnectedStatus(peerId, false)
                dao.updatePeerLastSeen(peerId, System.currentTimeMillis())
            }
        }
        
        // Files stored before encryption existed: encrypt them once, in the background
        scope.launch(Dispatchers.IO) { fileTransferManager.encryptLegacyFiles() }

        // Presence Monitor
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(15000)
                val connectedPeers = dao.getConnectedPeers()
                val now = System.currentTimeMillis()
                for (peer in connectedPeers) {
                    if (now - peer.lastSeenTimestamp > OFFLINE_AFTER_MS) {
                        dao.updatePeerConnectedStatus(peer.deviceId, false)
                    }
                }
            }
        }

        // Presence: every 30 s, a PRESENCE to each contact seen nearby in the last minute
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(PRESENCE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                for ((peerId, seenAt) in nearbySeenAt.entries.toList()) {
                    if (now - seenAt > NEARBY_WINDOW_MS) nearbySeenAt.remove(peerId, seenAt)
                    else sendPresence(peerId, force = true)
                }
                strangerPresenceAt.entries.removeAll { now - it.value > STRANGER_PRESENCE_GAP_MS }
            }
        }

        // Periodic retry: every 30s, re-attempt any stuck [WAIT] messages for connected peers
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(30000)
                val connectedPeers = dao.getConnectedPeers()
                for (peer in connectedPeers) {
                    resendPendingMessages(peer.deviceId)
                }
            }
        }
    }
    
    /** The plaintext to (re)send for a stored outgoing text or location message. */
    private fun outgoingPayload(msg: MessageEntity): ByteArray {
        val base = if (msg.messageType == "LOCATION") "LOCATION:${msg.plaintext}" else msg.plaintext
        return buildReplyPayload(base, msg.replyToMessageId, msg.replyToSenderId, msg.replyToTextSnippet)
            .toByteArray(Charsets.UTF_8)
    }

    private fun resendPendingMessages(peerId: String) {
        // Heartbeats, HELLOs and the 30 s loop all trigger this; run at most one per contact at a time
        if (!resendInFlight.add(peerId)) return
        scope.launch(Dispatchers.IO) {
            for (msg in dao.getPendingMessages(peerId)) {
                // File messages have their own transfer logic
                if (msg.messageType != "TEXT" && msg.messageType != "LOCATION") continue
                val sent = sender.send(peerId, outgoingPayload(msg), envelopeId = msg.messageId, timestamp = msg.timestamp)
                if (sent) {
                    dao.updateMessageStatus(msg.messageId, 1)
                }
            }
        }.invokeOnCompletion { resendInFlight.remove(peerId) }
    }

    /**
     * Manually retry sending a single stuck [WAIT] message.
     * Re-encrypts and re-broadcasts with the current session counter so the
     * recipient's replay-guard accepts it as a fresh message.
     */
    fun retryMessage(peerId: String, msg: com.aarushchaudhary.comlink.data.MessageEntity) {
        if (!msg.isFromMe) return
        // Allow retry for stuck text (status 0) or stuck file transfers (progress < 1)
        val isStuckFile = msg.fileTransferId != null && (msg.transferProgress < 1f || msg.status == 0)
        val isStuckText = msg.status == 0 && msg.fileTransferId == null
        if (!isStuckFile && !isStuckText) return
        
        scope.launch(Dispatchers.IO) {
            
            // 1. Jumpstart / Resume File Transfers. A file message never goes down the text path:
            // with no local copy (too large, or unreadable) there is nothing to resend.
            if (msg.fileTransferId != null) {
                val current = dao.getMessage(msg.messageId) ?: msg
                fileTransferManager.resumeOutgoingTransfer(peerId, current)
                return@launch
            }

            // 2. Retry standard text / location messages
            val sent = sender.send(peerId, outgoingPayload(msg), envelopeId = msg.messageId, timestamp = msg.timestamp)
            if (sent) {
                dao.updateMessageStatus(msg.messageId, 1)
            }
        }
    }

    fun processScannedQr(qrData: String, contactName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Current protobuf format or old "devId:pubKey"; anything without a valid
                // 32-byte key (a URL, a random QR) is ignored instead of becoming a junk contact
                val contact = ContactQr.parse(qrData) ?: return@launch
                val peerDeviceId = contact.deviceId
                val peerPubKey = contact.publicKeyBase64
                val exchangedName = contact.displayName

                val existing = dao.getPeer(peerDeviceId)
                if (existing == null) {
                    dao.insertPeer(PeerEntity(peerDeviceId, peerPubKey, contactName, exchangedName = exchangedName))
                } else {
                    // Re-scan: update in place, so chat history, nickname and pin are kept
                    // (re-inserting would delete the contact row and cascade-delete its messages)
                    val keyChanged = existing.publicKeyBase64 != peerPubKey
                    // Hold both of the contact's locks so no send or receive uses the old key meanwhile
                    receiveLocks.getOrPut(peerDeviceId) { Mutex() }.withLock {
                        sender.lockFor(peerDeviceId).withLock {
                            dao.updatePeerIdentity(peerDeviceId, peerPubKey, contactName, exchangedName)
                            if (keyChanged) {
                                // New key = new shared secret, nonce space and tags: drop the cached
                                // cipher and start counters fresh. Messages are kept.
                                sessionCiphers.remove(peerDeviceId)
                                dao.resetSessionState(peerDeviceId)
                            }
                        }
                    }
                }

                val state = dao.getSessionState(peerDeviceId)
                if (state == null) {
                    dao.insertSessionState(SessionStateEntity(peerDeviceId))
                }
                // Load the (new) key and recognise this contact's tags from now on
                getOrCreateCipher(peerDeviceId)
                tagDirectory.invalidate()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateNickname(peerId: String, newNickname: String) {
        scope.launch(Dispatchers.IO) {
            // An in-place UPDATE: re-inserting the contact would cascade-delete its messages
            dao.updatePeerNickname(peerId, newNickname)
        }
    }

    fun sendMessage(
        peerId: String, 
        plaintext: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            // No session yet (contact not set up): nothing is stored or sent, as before
            dao.getSessionState(peerId) ?: return@launch

            val msgEntity = MessageEntity(
                messageId = UUID.randomUUID().toString(),
                deviceId = peerId,
                isFromMe = true,
                plaintext = plaintext,
                timestamp = System.currentTimeMillis(),
                replyToMessageId = replyToId,
                replyToSenderId = replyToSender,
                replyToTextSnippet = replyToSnippet
            )
            dao.insertMessage(msgEntity)
            dao.updatePeerLastMessageTime(peerId, msgEntity.timestamp)

            val sent = sender.send(peerId, outgoingPayload(msgEntity), envelopeId = msgEntity.messageId, timestamp = msgEntity.timestamp)
            if (sent) {
                dao.updateMessageStatus(msgEntity.messageId, 1)
            }
        }
    }

    /**
     * Send a file (image, document, audio) to a peer.
     * Creates a placeholder message immediately and starts the multi-envelope
     * file transfer in the background.
     */
    fun sendFileMessage(
        peerId: String,
        uri: Uri,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val contentResolver = app.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = fileTransferManager.getFileName(uri) ?: "file"
            val fileSize = fileTransferManager.getFileSize(uri)

            val messageType = when {
                mimeType.startsWith("image/") -> "IMAGE"
                mimeType.startsWith("audio/") -> "AUDIO"
                mimeType.startsWith("video/") -> "VIDEO"
                else -> "DOCUMENT"
            }

            val messageId = UUID.randomUUID().toString()

            // Insert placeholder message immediately (shows in chat with progress bar)
            val message = MessageEntity(
                messageId = messageId,
                deviceId = peerId,
                isFromMe = true,
                plaintext = "\u2709 $fileName",
                timestamp = System.currentTimeMillis(),
                replyToMessageId = replyToId,
                replyToSenderId = replyToSender,
                replyToTextSnippet = replyToSnippet,
                messageType = messageType,
                fileTransferId = messageId,
                fileName = fileName,
                fileMimeType = mimeType,
                fileSize = fileSize
            )
            dao.insertMessage(message)
            dao.updatePeerLastMessageTime(peerId, message.timestamp)

            // Launch the multi-envelope transfer
            val problem = when (fileTransferManager.sendFile(peerId, uri, messageId)) {
                FileTransferManager.SendFileResult.TOO_LARGE -> "File too large to send (max 10 MB)"
                FileTransferManager.SendFileResult.UNREADABLE -> "Couldn't read this file"
                else -> null
            }
            if (problem != null) {
                // Nothing was saved or sent, and nothing ever could be: remove the bubble, say why
                dao.deleteMessage(messageId)
                showToast(problem)
            }
        }
    }

    /** Short message for the user; works from any thread, even with the chat screen closed. */
    private fun showToast(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun sendLocationMessage(
        peerId: String,
        lat: Double,
        lon: Double,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            // No session yet (contact not set up): nothing is stored or sent, as before
            dao.getSessionState(peerId) ?: return@launch

            val msgEntity = MessageEntity(
                messageId = UUID.randomUUID().toString(),
                deviceId = peerId,
                isFromMe = true,
                plaintext = "$lat,$lon",
                timestamp = System.currentTimeMillis(),
                replyToMessageId = replyToId,
                replyToSenderId = replyToSender,
                replyToTextSnippet = replyToSnippet,
                messageType = "LOCATION"
            )
            dao.insertMessage(msgEntity)
            dao.updatePeerLastMessageTime(peerId, msgEntity.timestamp)

            // outgoingPayload adds the "LOCATION:" prefix the receiver looks for
            val sent = sender.send(peerId, outgoingPayload(msgEntity), envelopeId = msgEntity.messageId, timestamp = msgEntity.timestamp)
            if (sent) {
                dao.updateMessageStatus(msgEntity.messageId, 1)
            }
        }
    }

    /**
     * [senderId] comes from the envelope's tag, which only that contact's key produces; nothing on
     * the wire claims a sender any more. Decryption below then proves it.
     */
    private fun processIncomingEnvelope(envelope: Envelope, senderId: String, fromAddress: String) {
        scope.launch(Dispatchers.IO) {
            // Safety net: the Saved Messages chat has no key, so no tag can map to it
            if (senderId == SAVED_MESSAGES_ID) return@launch
            // Only known contacts get a lock, so random sender IDs can't grow the lock map
            dao.getPeer(senderId) ?: return@launch
            // One envelope at a time per contact: the replay window and file assembly never race
            receiveLocks.getOrPut(senderId) { Mutex() }.withLock {
                handleIncomingEnvelope(senderId, envelope, fromAddress)
            }
        }
    }

    private suspend fun handleIncomingEnvelope(senderId: String, envelope: Envelope, fromAddress: String) {
            val state = dao.getSessionState(senderId) ?: return

            val cipher = getOrCreateCipher(senderId) ?: return

            try {
                val ciphertext = envelope.ciphertext?.toByteArray() ?: return
                val nonceBytes = envelope.nonce?.toByteArray() ?: return

                // The header as received: if a relay changed any of these fields, the tag check fails
                val aad = SessionCipher.envelopeAad(
                    envelope.tag.toByteArray(), envelope.envelope_id, envelope.content_type, envelope.timestamp
                )
                val (paddedBytes, counter) = cipher.decrypt(ciphertext, nonceBytes, aad)
                // Strip the size-bucket padding (authenticated, so a bad length means a broken sender)
                val decryptedBytes = Padding.unpad(paddedBytes) ?: return

                // Replay protection: accept new counters and unseen ones inside the last 256,
                // so messages that arrive out of order through the mesh aren't dropped
                val window = ReplayWindow.restore(state.peerHighestCounter, state.peerSeenWindow)
                if (!window.accept(counter)) {
                    return // Replay, or older than the window
                }
                dao.updatePeerReplayState(senderId, window.highest, window.toBytes())

                // Decide what it is (pure and unit-tested: IncomingMessage.classify), then act on it
                val contentType = envelope.content_type ?: ContentTypes.MESSAGE
                when (val incoming = IncomingMessage.classify(contentType, decryptedBytes)) {
                    is IncomingMessage.FileHeader -> {
                        fileTransferManager.onFileHeaderReceived(senderId, incoming.payload)
                        // Send ACK for the header envelope
                        sendAck(senderId, envelope.envelope_id ?: return)
                    }
                    is IncomingMessage.FilePart -> fileTransferManager.onFilePartReceived(senderId, incoming.payload)
                    is IncomingMessage.Presence -> handlePresence(senderId, fromAddress)
                    is IncomingMessage.FileStatus -> fileTransferManager.onFileStatusReceived(senderId, incoming.payload)
                    // Only our own message to this contact, and never lower (a late ACK after READ)
                    is IncomingMessage.Ack -> dao.raiseOwnMessageStatus(incoming.messageId, senderId, 2) // Delivered
                    is IncomingMessage.Read -> incoming.messageIds.forEach { dao.raiseOwnMessageStatus(it, senderId, 3) } // Seen
                    is IncomingMessage.Unknown -> android.util.Log.w("MessagingEngine", "Ignored unknown content type ${incoming.contentType} from $senderId")
                    is IncomingMessage.Chat -> {
                        val msgEntity = MessageEntity(
                            messageId = envelope.envelope_id ?: UUID.randomUUID().toString(),
                            deviceId = senderId,
                            isFromMe = false,
                            plaintext = incoming.text,
                            timestamp = envelope.timestamp ?: System.currentTimeMillis(),
                            replyToMessageId = incoming.replyId,
                            replyToSenderId = incoming.replySender,
                            replyToTextSnippet = incoming.replySnippet,
                            messageType = if (incoming.isLocation) "LOCATION" else "TEXT"
                        )
                        dao.insertMessage(msgEntity)
                        dao.updatePeerLastMessageTime(senderId, msgEntity.timestamp)
                        sendAck(senderId, msgEntity.messageId)
                    }
                }
                
            } catch (e: Exception) {
                e.printStackTrace() // Decryption failed
            }
    }

    /**
     * A phone running ComLink is connected at [address]. If it's a contact proven there, keep them
     * fresh. Otherwise it could be any contact (HELLO names nobody), so every contact gets a
     * PRESENCE; only the right one can read theirs. Throttled per address.
     */
    private suspend fun onNeighbourSeen(address: String) {
        val known = app.bluetoothService.peersAt(address)
        val now = System.currentTimeMillis()
        if (known.isNotEmpty()) {
            for (peerId in known) {
                nearbySeenAt[peerId] = now
                sendPresence(peerId)
            }
            return
        }
        val last = strangerPresenceAt[address]
        if (last != null && now - last < STRANGER_PRESENCE_GAP_MS) return
        strangerPresenceAt[address] = now
        for (peerId in sessionCiphers.keys.toList()) sendPresence(peerId)
    }

    /**
     * Sends our encrypted PRESENCE (ttl 1: neighbours only). At most once per [PRESENCE_MIN_GAP_MS]
     * per contact unless [force] (the 30 s loop).
     */
    private suspend fun sendPresence(peerId: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = presenceSentAt[peerId]
        if (!force && last != null && now - last < PRESENCE_MIN_GAP_MS) return
        presenceSentAt[peerId] = now
        sender.send(peerId, PRESENCE_PAYLOAD, contentType = ContentTypes.PRESENCE, ttl = 1)
    }

    /**
     * A contact's PRESENCE decrypted: they are really there, at [fromAddress]. Only now are they
     * shown online and given direct sends, and their waiting messages go out.
     */
    private suspend fun handlePresence(peerId: String, fromAddress: String) {
        app.bluetoothService.trustAddress(peerId, fromAddress)
        nearbySeenAt[peerId] = System.currentTimeMillis()
        dao.updatePeerConnectedStatus(peerId, true)
        dao.updatePeerLastSeen(peerId, System.currentTimeMillis())
        resendPendingMessages(peerId)
        // Make sure they see us too, without waiting for the next loop
        scope.launch(Dispatchers.IO) { sendPresence(peerId) }
    }

    private fun sendAck(peerId: String, messageId: String) {
        scope.launch(Dispatchers.IO) {
            sender.send(peerId, "ACK:$messageId".toByteArray(Charsets.UTF_8))
        }
    }

    fun markAsRead(peerId: String) {
        scope.launch(Dispatchers.IO) {
            val unread = dao.getUnreadMessages(peerId)
            if (unread.isEmpty()) return@launch

            // Mark messages as read locally
            dao.markIncomingMessagesAsRead(peerId)

            // One READ receipt per batch of messages, not one per message
            for (batch in unread.chunked(READ_BATCH)) {
                val ids = batch.joinToString(",") { it.messageId }
                sender.send(peerId, "READ:$ids".toByteArray(Charsets.UTF_8))
            }
        }
    }

    private suspend fun ensureSavedPeerExists() {
        if (dao.getPeer(SAVED_MESSAGES_ID) == null) {
            dao.insertPeer(PeerEntity(
                deviceId = SAVED_MESSAGES_ID,
                publicKeyBase64 = "",
                contactName = "SAVED MESSAGES",
                lastSeenTimestamp = 0L
            ))
        }
    }

    fun saveMessageToSaved(msg: MessageEntity, senderName: String) {
        scope.launch(Dispatchers.IO) {
            ensureSavedPeerExists()
            val savedMsg = MessageEntity(
                messageId = UUID.randomUUID().toString(),
                deviceId = SAVED_MESSAGES_ID,
                isFromMe = false,
                plaintext = msg.plaintext,
                timestamp = System.currentTimeMillis(),
                replyToSenderId = senderName,
                replyToTextSnippet = msg.plaintext.take(50),
                status = 1
            )
            dao.insertMessage(savedMsg)
            dao.updatePeerLastMessageTime(SAVED_MESSAGES_ID, savedMsg.timestamp)
        }
    }

    fun sendSavedNote(text: String) {
        scope.launch(Dispatchers.IO) {
            ensureSavedPeerExists()
            val noteMsg = MessageEntity(
                messageId = UUID.randomUUID().toString(),
                deviceId = SAVED_MESSAGES_ID,
                isFromMe = true,
                plaintext = text,
                timestamp = System.currentTimeMillis(),
                status = 1
            )
            dao.insertMessage(noteMsg)
            dao.updatePeerLastMessageTime(SAVED_MESSAGES_ID, noteMsg.timestamp)
        }
    }

    fun togglePin(peerId: String, isPinned: Boolean) {
        scope.launch(Dispatchers.IO) {
            dao.updatePeerPinned(peerId, isPinned)
        }
    }
}
