package com.aarushchaudhary.comlink

import android.app.Application
import com.aarushchaudhary.comlink.bluetooth.BluetoothService
import com.aarushchaudhary.comlink.bluetooth.MeshRouter
import com.aarushchaudhary.comlink.crypto.IdentityManager
import com.aarushchaudhary.comlink.data.ComLinkDatabase
import com.aarushchaudhary.comlink.data.FileCrypto
import com.aarushchaudhary.comlink.data.SecureDatabase
import com.aarushchaudhary.comlink.messaging.MessagingEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class ComLinkApp : Application() {

    lateinit var database: ComLinkDatabase
        private set

    lateinit var identityManager: IdentityManager
        private set

    lateinit var meshRouter: MeshRouter
        private set

    lateinit var bluetoothService: BluetoothService
        private set

    // All messaging logic; lives as long as the process, not as long as a screen
    lateinit var messagingEngine: MessagingEngine
        private set

    // Encrypts and decrypts every stored file (received files, sent copies, thumbnails, parts)
    lateinit var fileCrypto: FileCrypto
        private set

    /** Temporary decrypted copies handed to other apps (open a document, play a video). */
    val shareCacheDir: File get() = File(cacheDir, "shared")

    // An uncaught exception in background work is logged, not allowed to crash the whole app
    private val appScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("ComLinkApp", "Background task failed", e) }
    )

    override fun onCreate() {
        super.onCreate()

        // The identity holds the keys, so it comes first
        identityManager = IdentityManager(this)

        // SQLCipher-encrypted database; a pre-encryption database is moved over once, verified
        database = SecureDatabase.open(this, identityManager.databasePassphrase())

        fileCrypto = FileCrypto(identityManager.fileStreamingAead())
        // Decrypted copies only live until the next start
        shareCacheDir.deleteRecursively()

        meshRouter = MeshRouter(identityManager.getDeviceId(), appScope)

        bluetoothService = BluetoothService(this, meshRouter)

        // Wire up direct send for targeted file transfers
        meshRouter.sendDirectToNetwork = { peerId, payload, noResponse ->
            bluetoothService.sendDirect(peerId, payload, noResponse)
        }

        // Created last: it wires the router and Bluetooth callbacks once, for the whole process
        messagingEngine = MessagingEngine(this, appScope)
    }
}
