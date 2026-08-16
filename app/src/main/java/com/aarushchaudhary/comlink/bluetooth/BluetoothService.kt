package com.aarushchaudhary.comlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BluetoothService(
    private val context: Context,
    private val meshRouter: MeshRouter,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {
    var onPeerConnected: ((String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    private val macToPeerId = ConcurrentHashMap<String, String>()
    companion object {
        private const val TAG = "ComLinkBluetooth"
        // Same as BitChat
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

        // MTU 512 - 3 (GATT overhead) - 6 (Custom Header) = 503
        private const val MAX_CHUNK_PAYLOAD_SIZE = 500
    }

    private val leAdvertiser by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }
    private val leScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private val activeGatts = ConcurrentHashMap<String, GattConnection>()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Buffers for reassembling fragmented messages on the Server
    private class MessageBuffer(val totalChunks: Int) {
        val chunks = arrayOfNulls<ByteArray>(totalChunks)
        fun isComplete() = chunks.all { it != null }
        fun reassemble(): ByteArray {
            val totalSize = chunks.sumOf { it?.size ?: 0 }
            val result = ByteBuffer.allocate(totalSize)
            chunks.forEach { chunk ->
                if (chunk != null) result.put(chunk)
            }
            return result.array()
        }
    }
    
    // Map of Packet ID to MessageBuffer
    private val rxBuffers = ConcurrentHashMap<Int, MessageBuffer>()

    // Concurrency protection: Mutex for sequential characteristic writes
    private inner class GattConnection(val gatt: BluetoothGatt) {
        val writeMutex = kotlinx.coroutines.sync.Mutex()
        val writeCompleted = Channel<Unit>(1)

        suspend fun writeChunk(chunk: ByteArray): Boolean {
            writeMutex.lock()
            try {
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    characteristic.value = chunk
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    if (gatt.writeCharacteristic(characteristic)) {
                        writeCompleted.receive() // Wait for onCharacteristicWrite
                        return true
                    }
                }
                return false
            } finally {
                writeMutex.unlock()
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                if (serviceData != null) {
                    macToPeerId[device.address] = String(serviceData)
                }
                if (!activeGatts.containsKey(device.address)) {
                    connectToDevice(device)
                }
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Client connected to our server: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Client disconnected from our server: ${device.address}")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                handleIncomingChunk(device, value)
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected as client to: ${gatt.device.address}")
                val conn = GattConnection(gatt)
                activeGatts[gatt.device.address] = conn
                // Android BLE workaround: discover services first
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from: ${gatt.device.address}")
                activeGatts.remove(gatt.device.address)
                macToPeerId[gatt.device.address]?.let { onPeerDisconnected?.invoke(it) }
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu for ${gatt.device.address}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                // Request MTU after services discovered
                gatt.requestMtu(512)
                
                // Send HELLO packet to identify ourselves
                serviceScope.launch {
                    val helloPayload = ("HELLO:" + meshRouter.localDeviceId).toByteArray()
                    broadcast(helloPayload) // Broadcast will reach this new connection
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                // Unblock the queue for the next chunk
                activeGatts[gatt.device.address]?.writeCompleted?.trySend(Unit)
            }
        }
    }

    init {
        // Wire up the MeshRouter broadcast callback to our BLE transmission
        meshRouter.broadcastToNetwork = { payload ->
            broadcast(payload)
        }
    }

    private var heartbeatJob: Job? = null

    @Synchronized
    fun startListening() {
        if (gattServer != null) return

        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                forceHeartbeat()
            }
        }

        try {
            // 1. Setup GATT Server
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic)
            
            gattServer?.addService(service)

            // 2. Start Advertising (Dual-Role)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                
                .build()
            
            leAdvertiser?.startAdvertising(settings, data, advertiseCallback)

            // 3. Start Scanning (Dual-Role)
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
                
            leScanner?.startScan(filters, scanSettings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE operations: ${e.message}")
        }
    }

    @Synchronized
    fun stopAll() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            leAdvertiser?.stopAdvertising(advertiseCallback)
            leScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop BLE advertising/scanning", e)
        }
        
        activeGatts.values.forEach { it.gatt.close() }
        activeGatts.clear()
        
        gattServer?.close()
        gattServer = null
        
        serviceScope.cancel()
    }

    fun forceHeartbeat() {
        serviceScope.launch {
            activeGatts.keys.toList().forEach { address ->
                val conn = activeGatts[address] ?: return@forEach
                val pingPayload = ("PING:" + meshRouter.localDeviceId).toByteArray()
                val success = conn.writeChunk(pingPayload)
                if (!success) {
                    Log.d(TAG, "Heartbeat failed for $address")
                    conn.gatt.disconnect()
                    activeGatts.remove(address)
                    macToPeerId[address]?.let { onPeerDisconnected?.invoke(it) }
                } else {
                    macToPeerId[address]?.let { onPeerConnected?.invoke(it) }
                }
            }
        }
    }

    @Synchronized
    fun connectToDevice(device: BluetoothDevice) {
        if (activeGatts.containsKey(device.address)) return
        // Silent unbonded connection via BLE TRANSPORT_LE
        device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun handleIncomingChunk(device: BluetoothDevice, chunk: ByteArray) {
        if (chunk.size < 6) return // Invalid header

        val byteBuffer = ByteBuffer.wrap(chunk)
        val packetId = byteBuffer.int
        val totalChunks = byteBuffer.get().toInt() and 0xFF
        val chunkIndex = byteBuffer.get().toInt() and 0xFF
        val payloadData = ByteArray(chunk.size - 6)
        byteBuffer.get(payloadData)

        val buffer = rxBuffers.getOrPut(packetId) { MessageBuffer(totalChunks) }
        
        if (chunkIndex in 0 until totalChunks) {
            buffer.chunks[chunkIndex] = payloadData
        }

        if (buffer.isComplete()) {
            val completePayload = buffer.reassemble()
            rxBuffers.remove(packetId)
            
            val payloadStr = String(completePayload)
            if (payloadStr.startsWith("HELLO:")) {
                val peerId = payloadStr.substringAfter("HELLO:")
                macToPeerId[device.address] = peerId
                onPeerConnected?.invoke(peerId)
                return
            }
            if (payloadStr.startsWith("PING:")) {
                val peerId = payloadStr.substringAfter("PING:")
                macToPeerId[device.address] = peerId
                onPeerConnected?.invoke(peerId)
                return
            }
            
            // Pass complete envelope back to router
            meshRouter.onBytesReceived(completePayload)
        }
    }

    suspend fun broadcast(payload: ByteArray): Boolean {
        val packetId = payload.contentHashCode()
        val chunkPayloads = payload.toList().chunked(MAX_CHUNK_PAYLOAD_SIZE).map { it.toByteArray() }
        val totalChunks = chunkPayloads.size

        if (totalChunks > 255) {
            Log.e(TAG, "Payload too large to fragment ($totalChunks chunks)")
            return false
        }

        val chunksToSend = chunkPayloads.mapIndexed { index, chunkData ->
            val buffer = ByteBuffer.allocate(6 + chunkData.size)
            buffer.putInt(packetId)
            buffer.put(totalChunks.toByte())
            buffer.put(index.toByte())
            buffer.put(chunkData)
            buffer.array()
        }

        if (activeGatts.isEmpty()) return false

        val jobs = activeGatts.values.map { connection ->
            serviceScope.async {
                var connSuccess = true
                for (chunk in chunksToSend) {
                    if (!connection.writeChunk(chunk)) {
                        connSuccess = false
                        break
                    }
                }
                connSuccess
            }
        }
        
        val results = jobs.awaitAll()
        return results.any { it } // Return true if at least one connection successfully received all chunks
    }
}
