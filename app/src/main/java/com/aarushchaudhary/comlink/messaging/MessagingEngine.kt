package com.aarushchaudhary.comlink.messaging

import android.net.Uri
import android.util.Base64
import com.aarushchaudhary.comlink.ComLinkApp
import com.aarushchaudhary.comlink.crypto.ContactQr
import com.aarushchaudhary.comlink.crypto.ReplayWindow
import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.data.FileTransferManager
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.data.PeerEntity
import com.aarushchaudhary.comlink.data.SessionStateEntity
import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private suspend fun getOrCreateCipher(peerId: String): SessionCipher? {
        return sessionCiphers.getOrPut(peerId) {
            val peer = dao.getPeer(peerId) ?: return null
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            SessionCipher(identity.getPrivateKey(), identity.getPublicKey(), peerPubKey)
        }
    }

    // Every outgoing envelope goes through this one sender (it owns the per-contact locks)
    private val sender = EncryptedSender(
        localDeviceId = identity.getDeviceId(),
        loadCounter = { peerId -> dao.getSessionState(peerId)?.myNextCounter },
        saveCounter = { peerId, next -> dao.updateMyCounter(peerId, next) },
        cipherFor = { peerId -> getOrCreateCipher(peerId) },
        transmit = { envelope, direct, noResponse ->
            if (direct) router.sendDirectMessage(envelope.recipient_id, envelope, noResponse)
            else router.sendLocalMessage(envelope)
        }
    )

    // FileTransferManager — created once, sends through the shared sender
    val fileTransferManager = FileTransferManager(
        context = app,
        dao = dao,
        sender = sender
    )

    init {
        // Wired once for the whole app process, not per screen: a closed screen can no longer
        // swallow incoming messages
        router.onMessageReceived = { envelope ->
            processIncomingEnvelope(envelope)
        }
        
        app.bluetoothService.onPeerConnected = { peerId ->
            scope.launch(Dispatchers.IO) {
                dao.updatePeerConnectedStatus(peerId, true)
                dao.updatePeerLastSeen(peerId, System.currentTimeMillis())
                resendPendingMessages(peerId)
            }
        }
        
        app.bluetoothService.onPeerDisconnected = { peerId ->
            scope.launch(Dispatchers.IO) {
                dao.updatePeerConnectedStatus(peerId, false)
                dao.updatePeerLastSeen(peerId, System.currentTimeMillis())
            }
        }
        
        // Presence Monitor
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(15000)
                val connectedPeers = dao.getConnectedPeers()
                val now = System.currentTimeMillis()
                for (peer in connectedPeers) {
                    if (now - peer.lastSeenTimestamp > 45000) {
                        dao.updatePeerConnectedStatus(peer.deviceId, false)
                    }
                }
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
        val isStuckFile = msg.fileTransferId != null && msg.transferProgress < 1f
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
                                // New key = new shared secret and nonce space: drop the cached
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
            fileTransferManager.sendFile(peerId, uri, messageId)
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

    private fun processIncomingEnvelope(envelope: Envelope) {
        scope.launch(Dispatchers.IO) {
            val senderId = envelope.sender_id ?: return@launch
            // Only known contacts get a lock, so random sender IDs can't grow the lock map
            dao.getPeer(senderId) ?: return@launch
            // One envelope at a time per contact: the replay window and file assembly never race
            receiveLocks.getOrPut(senderId) { Mutex() }.withLock {
                handleIncomingEnvelope(senderId, envelope)
            }
        }
    }

    private suspend fun handleIncomingEnvelope(senderId: String, envelope: Envelope) {
            val state = dao.getSessionState(senderId) ?: return

            val cipher = getOrCreateCipher(senderId) ?: return

            try {
                val ciphertext = envelope.ciphertext?.toByteArray() ?: return
                val nonceBytes = envelope.nonce?.toByteArray() ?: return

                // The header as received: if a relay changed any of these fields, the tag check fails
                val aad = SessionCipher.envelopeAad(
                    envelope.sender_id, envelope.recipient_id, envelope.envelope_id,
                    envelope.content_type, envelope.timestamp
                )
                val (decryptedBytes, counter) = cipher.decrypt(ciphertext, nonceBytes, aad)

                // Replay protection: accept new counters and unseen ones inside the last 256,
                // so messages that arrive out of order through the mesh aren't dropped
                val window = ReplayWindow.restore(state.peerHighestCounter, state.peerSeenWindow)
                if (!window.accept(counter)) {
                    return // Replay, or older than the window
                }
                dao.updatePeerReplayState(senderId, window.highest, window.toBytes())

                // Route based on content_type
                val contentType = envelope.content_type ?: 0
                when (contentType) {
                    FileTransferManager.CONTENT_TYPE_FILE_HEADER -> {
                        fileTransferManager.onFileHeaderReceived(senderId, decryptedBytes)
                        // Send ACK for the header envelope
                        sendAck(senderId, envelope.envelope_id ?: return)
                        return
                    }
                    FileTransferManager.CONTENT_TYPE_FILE_PART -> {
                        fileTransferManager.onFilePartReceived(senderId, decryptedBytes)
                        return
                    }
                }
                
                // content_type == 0 (MESSAGE): existing text/ACK/READ logic
                val plaintextRaw = String(decryptedBytes, Charsets.UTF_8)
                if (plaintextRaw.startsWith("ACK:")) {
                    val ackedId = plaintextRaw.substringAfter("ACK:")
                    dao.updateMessageStatus(ackedId, 2) // 2 = Delivered
                    return
                }
                
                if (plaintextRaw.startsWith("READ:")) {
                    val readId = plaintextRaw.substringAfter("READ:")
                    dao.updateMessageStatus(readId, 3) // 3 = Seen
                    return
                }
                
                var actualText = plaintextRaw
                var repId: String? = null
                var repSender: String? = null
                var repSnippet: String? = null
                
                if (plaintextRaw.startsWith("{")) {
                    try {
                        val json = org.json.JSONObject(plaintextRaw)
                        if (json.has("text")) {
                            actualText = json.getString("text")
                            repId = json.optString("replyId", "").takeIf { it.isNotEmpty() }
                            repSender = json.optString("replySender", "").takeIf { it.isNotEmpty() }
                            repSnippet = json.optString("replySnippet", "").takeIf { it.isNotEmpty() }
                        }
                    } catch (e: Exception) {
                        // Not JSON, just use as text
                    }
                }
                
                var finalMsgType = "TEXT"
                
                if (actualText.startsWith("LOCATION:")) {
                    finalMsgType = "LOCATION"
                    actualText = actualText.substringAfter("LOCATION:")
                }
                
                val msgEntity = MessageEntity(
                    messageId = envelope.envelope_id ?: UUID.randomUUID().toString(),
                    deviceId = senderId,
                    isFromMe = false,
                    plaintext = actualText,
                    timestamp = envelope.timestamp ?: System.currentTimeMillis(),
                    replyToMessageId = repId,
                    replyToSenderId = repSender,
                    replyToTextSnippet = repSnippet,
                    messageType = finalMsgType
                )
                dao.insertMessage(msgEntity)
                dao.updatePeerLastMessageTime(senderId, msgEntity.timestamp)
                
                // Send ACK
                sendAck(senderId, msgEntity.messageId)
                
            } catch (e: Exception) {
                e.printStackTrace() // Decryption failed
            }
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

            // Send READ receipts for each unread message
            for (msg in unread) {
                sender.send(peerId, "READ:${msg.messageId}".toByteArray(Charsets.UTF_8))
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
