package com.aarushchaudhary.comlink.ui

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aarushchaudhary.comlink.ComLinkApp
import com.aarushchaudhary.comlink.crypto.ContactQr
import com.aarushchaudhary.comlink.crypto.Fingerprint
import com.aarushchaudhary.comlink.data.MessageEntity
import com.aarushchaudhary.comlink.data.PeerEntity
import com.aarushchaudhary.comlink.messaging.MeshService
import com.aarushchaudhary.comlink.messaging.MessagingEngine
import com.aarushchaudhary.comlink.ui.theme.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The screens' entry point. All messaging logic lives in [MessagingEngine] (owned by the app
 * object, so it keeps running after a screen closes); this class only passes calls through
 * and exposes the database flows the screens read.
 */
class ComLinkViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val SAVED_MESSAGES_ID = MessagingEngine.SAVED_MESSAGES_ID
    }

    private val app = application as ComLinkApp
    private val dao = app.database.comLinkDao()
    private val identity = app.identityManager
    private val engine = app.messagingEngine

    val peers: Flow<List<PeerEntity>> = dao.getAllPeers()

    private val backgroundSwitchWatched = AtomicBoolean(false)

    fun startBluetoothIfPermitted() {
        app.bluetoothService.startListening()
        // From now on, keep the foreground service in step with the Settings switch.
        // This runs while the app is on screen, which Android 12+ requires for starting it.
        if (backgroundSwitchWatched.compareAndSet(false, true)) {
            viewModelScope.launch {
                app.dataStore.data
                    .map { it[MeshService.BACKGROUND_DELIVERY_PREF] ?: true }
                    .distinctUntilChanged()
                    .collect { enabled -> MeshService.sync(app, enabled) }
            }
        }
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

    fun getMyQrPayload(displayName: String): String {
        val pubKey = Base64.encodeToString(identity.getPublicKey(), Base64.NO_WRAP)
        val devId = identity.getDeviceId()
        val payload = com.aarushchaudhary.comlink.proto.ContactPayload(
            device_id = devId,
            public_key = pubKey,
            display_name = displayName
        )
        return Base64.encodeToString(com.aarushchaudhary.comlink.proto.ContactPayload.ADAPTER.encode(payload), Base64.NO_WRAP)
    }

    /**
     * Fingerprint of the scanned contact's key, for the scan dialog. [scannedText] is the whole QR
     * (current format) or just the key part (old "deviceId:key" format); both give the same
     * fingerprint for the same key. "ERROR" when there's no valid 32-byte key.
     * It should match what the other phone shows under its QR code ([myFingerprint]).
     */
    fun generateFingerprint(scannedText: String): String {
        val peerPubKey = ContactQr.publicKeyFrom(scannedText) ?: return "ERROR"
        return Fingerprint.of(peerPubKey)
    }

    /** Fingerprint of our own key, shown under our QR code. */
    fun myFingerprint(): String = Fingerprint.of(identity.getPublicKey())

    // ─── Pass-through to the engine (work continues even if this screen closes) ───

    fun retryMessage(peerId: String, msg: MessageEntity) = engine.retryMessage(peerId, msg)

    fun processScannedQr(qrData: String, contactName: String) = engine.processScannedQr(qrData, contactName)

    fun updateNickname(peerId: String, newNickname: String) = engine.updateNickname(peerId, newNickname)

    fun sendMessage(
        peerId: String,
        plaintext: String,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) = engine.sendMessage(peerId, plaintext, replyToId, replyToSender, replyToSnippet)

    fun sendFileMessage(
        peerId: String,
        uri: Uri,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) = engine.sendFileMessage(peerId, uri, replyToId, replyToSender, replyToSnippet)

    fun sendLocationMessage(
        peerId: String,
        lat: Double,
        lon: Double,
        replyToId: String? = null,
        replyToSender: String? = null,
        replyToSnippet: String? = null
    ) = engine.sendLocationMessage(peerId, lat, lon, replyToId, replyToSender, replyToSnippet)

    fun markAsRead(peerId: String) = engine.markAsRead(peerId)

    fun saveMessageToSaved(msg: MessageEntity, senderName: String) = engine.saveMessageToSaved(msg, senderName)

    fun sendSavedNote(text: String) = engine.sendSavedNote(text)

    fun togglePin(peerId: String, isPinned: Boolean) = engine.togglePin(peerId, isPinned)

    /** Save to device: copies the message's file to [destination] (picked in "Save as"). True if saved. */
    suspend fun saveFileToDevice(msg: MessageEntity, destination: Uri): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val path = msg.filePath ?: return@withContext false
            engine.fileTransferManager.exportToDevice(path, destination)
        }
}
