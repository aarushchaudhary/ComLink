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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ComLinkApp : Application() {

    // Set once the database (and the engine that needs it) exist; see [ready]
    @Volatile private var openedDatabase: ComLinkDatabase? = null
    @Volatile private var startedEngine: MessagingEngine? = null

    /** Available once [ready] is true (the UI shows "Securing your chats…" until then). */
    val database: ComLinkDatabase
        get() = checkNotNull(openedDatabase) { "Database not ready yet" }

    // All messaging logic; lives as long as the process, not as long as a screen
    val messagingEngine: MessagingEngine
        get() = checkNotNull(startedEngine) { "Messaging engine not ready yet" }

    lateinit var identityManager: IdentityManager
        private set

    lateinit var meshRouter: MeshRouter
        private set

    lateinit var bluetoothService: BluetoothService
        private set

    // Encrypts and decrypts every stored file (received files, sent copies, thumbnails, parts)
    lateinit var fileCrypto: FileCrypto
        private set

    /** Temporary decrypted copies handed to other apps (open a document, play a video). */
    val shareCacheDir: File get() = File(cacheDir, "shared")

    private val readyState = MutableStateFlow(false)

    /**
     * True once the database and messaging engine exist. Instant on a normal start; on the first
     * start after the encryption update it waits for the one-time database move, which then runs in
     * the background instead of freezing the app.
     */
    val ready: StateFlow<Boolean> = readyState

    // An uncaught exception in background work is logged, not allowed to crash the whole app
    private val appScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, e -> android.util.Log.e("ComLinkApp", "Background task failed", e) }
    )

    override fun onCreate() {
        super.onCreate()

        // The identity holds the keys, so it comes first
        identityManager = IdentityManager(this)

        fileCrypto = FileCrypto(identityManager.fileStreamingAead())
        // Decrypted copies only live until the next start
        shareCacheDir.deleteRecursively()

        meshRouter = MeshRouter(identityManager.getDeviceId(), appScope)

        bluetoothService = BluetoothService(this, meshRouter)

        // Wire up direct send for targeted file transfers
        meshRouter.sendDirectToNetwork = { peerId, payload, noResponse ->
            bluetoothService.sendDirect(peerId, payload, noResponse)
        }

        if (SecureDatabase.needsMigration(this)) {
            // The one-time move to the encrypted database can take a while: keep it off the main thread
            appScope.launch { finishStartup() }
        } else {
            finishStartup()
        }
    }

    /** Opens the (encrypted) database, then the engine, which wires the router and Bluetooth callbacks. */
    private fun finishStartup() {
        openedDatabase = SecureDatabase.open(this, identityManager.databasePassphrase())
        startedEngine = MessagingEngine(this, appScope)
        readyState.value = true
    }

    /** Runs [action] on the main thread once [ready] (right away if it already is). */
    fun whenReady(action: () -> Unit) {
        if (readyState.value) {
            action()
            return
        }
        appScope.launch {
            ready.first { it }
            withContext(Dispatchers.Main) { action() }
        }
    }
}
