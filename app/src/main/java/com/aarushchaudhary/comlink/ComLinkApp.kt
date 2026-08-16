package com.aarushchaudhary.comlink

import android.app.Application
import androidx.room.Room
import com.aarushchaudhary.comlink.bluetooth.BluetoothService
import com.aarushchaudhary.comlink.bluetooth.MeshRouter
import com.aarushchaudhary.comlink.crypto.IdentityManager
import com.aarushchaudhary.comlink.data.ComLinkDatabase

class ComLinkApp : Application() {
    
    lateinit var database: ComLinkDatabase
        private set
        
    lateinit var identityManager: IdentityManager
        private set
        
    lateinit var meshRouter: MeshRouter
        private set
        
    lateinit var bluetoothService: BluetoothService
        private set

    override fun onCreate() {
        super.onCreate()
        
        database = Room.databaseBuilder(
            this,
            ComLinkDatabase::class.java,
            "comlink_db"
        ).build()
        
        identityManager = IdentityManager(this)
        
        meshRouter = MeshRouter(identityManager.getDeviceId())
        
        bluetoothService = BluetoothService(this, meshRouter)
    }
}
