package com.aarushchaudhary.comlink

import android.app.Application
import androidx.room.Room
import com.aarushchaudhary.comlink.bluetooth.BluetoothService
import com.aarushchaudhary.comlink.bluetooth.MeshRouter
import com.aarushchaudhary.comlink.crypto.IdentityManager
import com.aarushchaudhary.comlink.data.ComLinkDatabase
import com.aarushchaudhary.comlink.messaging.MessagingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        database = Room.databaseBuilder(
            this,
            ComLinkDatabase::class.java,
            "comlink_db"
        ).build()

        identityManager = IdentityManager(this)

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
