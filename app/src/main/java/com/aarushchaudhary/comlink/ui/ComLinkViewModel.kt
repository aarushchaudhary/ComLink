package com.aarushchaudhary.comlink.ui

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aarushchaudhary.comlink.ComLinkApp
import com.aarushchaudhary.comlink.crypto.SessionCipher
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.data.PeerEntity
import com.aarushchaudhary.comlink.data.SessionStateEntity
import com.aarushchaudhary.comlink.proto.Envelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okio.ByteString.Companion.toByteString
import java.util.UUID

class ComLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ComLinkApp
    private val dao = app.database.comLinkDao()
    private val identity = app.identityManager
    private val router = app.meshRouter

    val peers: Flow<List<PeerEntity>> = dao.getAllPeers()

    init {
        router.onMessageReceived = { envelope ->
            processIncomingEnvelope(envelope)
        }
        
        app.bluetoothService.onPeerConnected = { peerId ->
            viewModelScope.launch(Dispatchers.IO) {
                dao.updatePeerConnectedStatus(peerId, true)
                resendPendingMessages(peerId)
            }
        }
        
        app.bluetoothService.onPeerDisconnected = { peerId ->
            viewModelScope.launch(Dispatchers.IO) {
                dao.updatePeerConnectedStatus(peerId, false)
                dao.updatePeerLastSeen(peerId, System.currentTimeMillis())
            }
        }
    }
    
    private fun resendPendingMessages(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = dao.getPendingMessages(peerId)
            if (pending.isEmpty()) return@launch
            
            val peer = dao.getPeer(peerId) ?: return@launch
            val state = dao.getSessionState(peerId) ?: return@launch
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            val cipher = SessionCipher(identity.getPrivateKey(), identity.getPublicKey(), peerPubKey)
            
            var currentCounter = state.myNextCounter
            
            for (msg in pending) {
                var finalPayload = msg.plaintext
                if (msg.replyToMessageId != null || msg.replyToSenderId != null || msg.replyToTextSnippet != null) {
                    val json = org.json.JSONObject()
                    json.put("text", msg.plaintext)
                    msg.replyToMessageId?.let { json.put("replyId", it) }
                    msg.replyToSenderId?.let { json.put("replySender", it) }
                    msg.replyToTextSnippet?.let { json.put("replySnippet", it) }
                    finalPayload = json.toString()
                }
                
                val ciphertextBytes = cipher.encrypt(finalPayload.toByteArray(Charsets.UTF_8), currentCounter)
                val nonceBytes = cipher.constructNonce(currentCounter)
                val envelope = Envelope(
                    sender_id = identity.getDeviceId(),
                    recipient_id = peerId,
                    ciphertext = ciphertextBytes.toByteString(),
                    nonce = nonceBytes.toByteString(),
                    timestamp = msg.timestamp,
                    envelope_id = msg.messageId,
                    ttl = 10
                )
                currentCounter++
                val success = router.sendLocalMessage(envelope)
                if (success) {
                    dao.updateMessageStatus(msg.messageId, 1)
                }
            }
            dao.updateMyCounter(peerId, currentCounter)
        }
    }

    fun startBluetoothIfPermitted() {
        app.bluetoothService.startListening()
    }

    fun checkConnection(peerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            app.bluetoothService.forceHeartbeat()
        }
    }

    fun getMessages(peerId: String): Flow<List<MessageEntity>> {
        return dao.getMessagesForPeer(peerId)
    }

    fun getPeerFlow(peerId: String): Flow<PeerEntity?> {
        return dao.getPeerFlow(peerId)
    }

    fun getMyQrPayload(): String {
        val pubKey = Base64.encodeToString(identity.getPublicKey(), Base64.NO_WRAP)
        val devId = identity.getDeviceId()
        return "$devId:$pubKey"
    }

    fun generateFingerprint(peerPubKeyBase64: String): String {
        return try {
            val peerPubKey = Base64.decode(peerPubKeyBase64, Base64.NO_WRAP)
            identity.generateFingerprint(peerPubKey)
        } catch (e: Exception) {
            "ERROR"
        }
    }

    fun processScannedQr(qrData: String, contactName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parts = qrData.split(":")
                if (parts.size != 2) return@launch
                val peerDeviceId = parts[0]
                val peerPubKey = parts[1]
                
                val peer = PeerEntity(peerDeviceId, peerPubKey, contactName)
                dao.insertPeer(peer)
                
                val state = dao.getSessionState(peerDeviceId)
                if (state == null) {
                    dao.insertSessionState(SessionStateEntity(peerDeviceId))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendMessage(
        peerId: String, 
        plaintext: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val peer = dao.getPeer(peerId) ?: return@launch
            val state = dao.getSessionState(peerId) ?: return@launch
            
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            
            val cipher = SessionCipher(
                myPrivateKey = identity.getPrivateKey(),
                myPublicKey = identity.getPublicKey(),
                peerPublicKey = peerPubKey
            )
            
            val counter = state.myNextCounter
            
            var finalPayload = plaintext
            if (replyToId != null || replyToSender != null || replyToSnippet != null) {
                val json = org.json.JSONObject()
                json.put("text", plaintext)
                replyToId?.let { json.put("replyId", it) }
                replyToSender?.let { json.put("replySender", it) }
                replyToSnippet?.let { json.put("replySnippet", it) }
                finalPayload = json.toString()
            }
            
            val ciphertextBytes = cipher.encrypt(finalPayload.toByteArray(Charsets.UTF_8), counter)
            val nonceBytes = cipher.constructNonce(counter)
            
            val envId = UUID.randomUUID().toString()
            
            val envelope = Envelope(
                sender_id = identity.getDeviceId(),
                recipient_id = peerId,
                ciphertext = ciphertextBytes.toByteString(),
                nonce = nonceBytes.toByteString(),
                timestamp = System.currentTimeMillis(),
                envelope_id = envId,
                ttl = 10 // Max 10 hops
            )
            
            dao.updateMyCounter(peerId, counter + 1)
            
            val msgEntity = MessageEntity(
                messageId = envId,
                deviceId = peerId,
                isFromMe = true,
                plaintext = plaintext,
                timestamp = envelope.timestamp ?: System.currentTimeMillis(),
                replyToMessageId = replyToId,
                replyToSenderId = replyToSender,
                replyToTextSnippet = replyToSnippet
            )
            dao.insertMessage(msgEntity)
            
            val success = router.sendLocalMessage(envelope)
            if (success) {
                dao.updateMessageStatus(envId, 1)
            }
        }
    }

    private fun processIncomingEnvelope(envelope: Envelope) {
        viewModelScope.launch(Dispatchers.IO) {
            val senderId = envelope.sender_id ?: return@launch
            val peer = dao.getPeer(senderId) ?: return@launch
            val state = dao.getSessionState(senderId) ?: return@launch
            
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            
            val cipher = SessionCipher(
                myPrivateKey = identity.getPrivateKey(),
                myPublicKey = identity.getPublicKey(),
                peerPublicKey = peerPubKey
            )
            
            try {
                val ciphertext = envelope.ciphertext?.toByteArray() ?: return@launch
                val nonceBytes = envelope.nonce?.toByteArray() ?: return@launch
                
                val (decryptedBytes, counter) = cipher.decrypt(ciphertext, nonceBytes)
                
                // Ensure strictly monotonic counter to prevent replay attacks
                if (counter <= state.peerHighestCounter) {
                    // Replay attack or delayed message, drop it.
                    return@launch
                }
                
                dao.updatePeerCounter(senderId, counter)
                
                val plaintextRaw = String(decryptedBytes, Charsets.UTF_8)
                if (plaintextRaw.startsWith("ACK:")) {
                    val ackedId = plaintextRaw.substringAfter("ACK:")
                    dao.updateMessageStatus(ackedId, 2) // 2 = Delivered
                    return@launch
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
                
                val msgEntity = MessageEntity(
                    messageId = envelope.envelope_id ?: UUID.randomUUID().toString(),
                    deviceId = senderId,
                    isFromMe = false,
                    plaintext = actualText,
                    timestamp = envelope.timestamp ?: System.currentTimeMillis(),
                    replyToMessageId = repId,
                    replyToSenderId = repSender,
                    replyToTextSnippet = repSnippet
                )
                dao.insertMessage(msgEntity)
                
                // Send ACK
                sendAck(senderId, msgEntity.messageId)
                
            } catch (e: Exception) {
                e.printStackTrace() // Decryption failed
            }
        }
    }
    
    private fun sendAck(peerId: String, messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val peer = dao.getPeer(peerId) ?: return@launch
            val state = dao.getSessionState(peerId) ?: return@launch
            val peerPubKey = Base64.decode(peer.publicKeyBase64, Base64.NO_WRAP)
            val cipher = SessionCipher(identity.getPrivateKey(), identity.getPublicKey(), peerPubKey)
            
            val counter = state.myNextCounter
            val ciphertextBytes = cipher.encrypt("ACK:$messageId".toByteArray(Charsets.UTF_8), counter)
            val nonceBytes = cipher.constructNonce(counter)
            
            val envelope = Envelope(
                sender_id = identity.getDeviceId(),
                recipient_id = peerId,
                ciphertext = ciphertextBytes.toByteString(),
                nonce = nonceBytes.toByteString(),
                timestamp = System.currentTimeMillis(),
                envelope_id = UUID.randomUUID().toString(),
                ttl = 10
            )
            
            dao.updateMyCounter(peerId, counter + 1)
            router.sendLocalMessage(envelope)
        }
    }
}
