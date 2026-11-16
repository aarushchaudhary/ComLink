package com.aarushchaudhary.comlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class BluetoothService(
    private val context: Context,
    private val meshRouter: MeshRouter,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {
    /**
     * A neighbour *claims* to be this contact (plain-text HELLO/PING, or a working heartbeat).
     * Untrusted: anyone can send a HELLO. It only prompts a PRESENCE exchange; a contact is shown
     * online, and gets direct sends, only after their encrypted PRESENCE decrypts (see [trustAddress]).
     */
    var onPeerSeen: ((String) -> Unit)? = null
    var onPeerDisconnected: ((String) -> Unit)? = null
    // Claimed (HELLO/PING) and proven (PRESENCE) addresses per contact
    private val addresses = PeerAddressBook()

    /** Called once a PRESENCE from [peerId] decrypted on a write from [address]. */
    fun trustAddress(peerId: String, address: String) {
        addresses.trust(peerId, address)
    }
    companion object {
        private const val TAG = "ComLinkBluetooth"
        // Same as BitChat
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

        // Upper bound on data bytes per piece. The real size per connection is
        // (negotiated MTU - 3 GATT overhead - 6 piece header), capped at this value.
        private const val MAX_CHUNK_PAYLOAD_SIZE = 500
    }

    private val leAdvertiser by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }
    private val leScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private val activeGatts = ConcurrentHashMap<String, GattConnection>()
    private val pendingConnections = ConcurrentHashMap.newKeySet<String>()
    // Random start, so phones still on the old app (which group pieces by packet ID alone)
    // are unlikely to see the same ID from two different senders
    private val packetCounter = AtomicInteger(kotlin.random.Random.nextInt())

    // Bluetooth callbacks start work here; a failure is logged instead of crashing the app
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Bluetooth task failed", e) }
    )

    // Reassembles incoming pieces, grouped per sending device
    private val assembler = PacketAssembler()

    // Concurrency protection: Mutex for sequential characteristic writes
    private inner class GattConnection(val gatt: BluetoothGatt) {
        @Volatile var negotiatedMtu: Int = 23
        // Data bytes per piece, so a whole piece always fits in one GATT write (no long writes)
        val effectiveChunkSize: Int
            get() = (negotiatedMtu - 3 - PacketFraming.HEADER_SIZE).coerceIn(14, MAX_CHUNK_PAYLOAD_SIZE)
        val writeMutex = kotlinx.coroutines.sync.Mutex()
        val writeCompleted = Channel<Unit>(1)

        suspend fun writeChunk(chunk: ByteArray, noResponse: Boolean = false): Boolean {
            writeMutex.lock()
            try {
                val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    characteristic.value = chunk
                    characteristic.writeType = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                    // Drain old events
                    while(writeCompleted.tryReceive().isSuccess) {}

                    if (gatt.writeCharacteristic(characteristic)) {
                        val result = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                            writeCompleted.receive()
                        }
                        return result != null
                    }
                }
                return false
            } catch (e: Exception) {
                return false
            } finally {
                writeMutex.unlock()
            }
        }

        /** Frames [payload] for this connection's packet size and writes every piece in order. */
        suspend fun sendFramed(payload: ByteArray, packetId: Int, noResponse: Boolean = false): Boolean {
            val pieces = PacketFraming.frame(payload, effectiveChunkSize, packetId)
            if (pieces == null) {
                Log.e(TAG, "Payload of ${payload.size} bytes doesn't fit in ${PacketFraming.MAX_CHUNKS} pieces of $effectiveChunkSize bytes")
                return false
            }
            for (piece in pieces) {
                if (!writeChunk(piece, noResponse)) return false
            }
            return true
        }
    }

    /** The connection for [address], but only if it belongs to [gatt] (ignores callbacks from replaced connections). */
    private fun connectionFor(address: String, gatt: BluetoothGatt): GattConnection? =
        activeGatts[address]?.takeIf { it.gatt === gatt }

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
                    addresses.claim(device.address, String(serviceData))
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
                assembler.forget(device.address)
                addresses.forgetTrust(device.address)
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
                // Our pieces always fit in one write; a prepared (long) write fragment isn't a whole piece
                if (!preparedWrite) {
                    handleIncomingChunk(device, value)
                }
            }
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            pendingConnections.remove(address)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected as client to: $address")
                val previous = activeGatts.put(address, GattConnection(gatt))
                if (previous != null && previous.gatt !== gatt) {
                    // A duplicate connection replaced an older one: release the old one
                    previous.gatt.close()
                }
                // Android BLE workaround: discover services first
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from: $address")
                val current = connectionFor(address, gatt)
                if (current != null && activeGatts.remove(address, current)) {
                    addresses.forgetTrust(address)
                    addresses.claimedPeerAt(address)?.let { onPeerDisconnected?.invoke(it) }
                }
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu for ${gatt.device.address}")
                val conn = connectionFor(gatt.device.address, gatt) ?: return
                conn.negotiatedMtu = mtu
                // Send HELLO only to this peer, now that MTU is negotiated
                serviceScope.launch {
                    val helloPayload = ("HELLO:" + meshRouter.localDeviceId).toByteArray()
                    sendToSinglePeer(conn, helloPayload)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(512)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                // Unblock the queue for the next chunk
                connectionFor(gatt.device.address, gatt)?.writeCompleted?.trySend(Unit)
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
        if (gattServer != null || bluetoothManager == null || bluetoothAdapter == null) return

        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(15000)
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

    /**
     * Stops advertising, scanning and all connections. Only the jobs started here are cancelled,
     * not [serviceScope], so [startListening] can start everything again later.
     */
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
        pendingConnections.clear()
        assembler.clear()
        addresses.clearTrust()

        gattServer?.close()
        gattServer = null
    }

    fun forceHeartbeat() {
        serviceScope.launch {
            activeGatts.keys.toList().forEach { address ->
                val conn = activeGatts[address] ?: return@forEach
                val pingPayload = ("PING:" + meshRouter.localDeviceId).toByteArray()
                // Framed like HELLO, so the receiver can read it
                val success = sendToSinglePeer(conn, pingPayload)
                if (!success) {
                    Log.d(TAG, "Heartbeat failed for $address")
                    conn.gatt.disconnect()
                    if (activeGatts.remove(address, conn)) {
                        addresses.forgetTrust(address)
                        addresses.claimedPeerAt(address)?.let { onPeerDisconnected?.invoke(it) }
                    }
                } else {
                    addresses.claimedPeerAt(address)?.let { onPeerSeen?.invoke(it) }
                }
            }
        }
    }

    @Synchronized
    fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        if (activeGatts.containsKey(address)) return
        // Scan results repeat many times a second: only one connection attempt per device at a time
        if (!pendingConnections.add(address)) return
        // Silent unbonded connection via BLE TRANSPORT_LE
        val gatt = device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            pendingConnections.remove(address)
        }
    }

    private fun handleIncomingChunk(device: BluetoothDevice, chunk: ByteArray) {
        val completePayload = assembler.accept(device.address, chunk) ?: return

        val payloadStr = String(completePayload)
        if (payloadStr.startsWith("HELLO:")) {
            val peerId = payloadStr.substringAfter("HELLO:")
            addresses.claim(device.address, peerId)
            onPeerSeen?.invoke(peerId)
            return
        }
        if (payloadStr.startsWith("PING:")) {
            val peerId = payloadStr.substringAfter("PING:")
            addresses.claim(device.address, peerId)
            onPeerSeen?.invoke(peerId)
            return
        }

        // Pass complete envelope back to router
        meshRouter.onBytesReceived(completePayload, device.address)
    }

    suspend fun broadcast(payload: ByteArray): Boolean {
        if (activeGatts.isEmpty()) return false
        val packetId = packetCounter.getAndIncrement()

        // Each connection frames with its own negotiated packet size
        val jobs = activeGatts.values.map { connection ->
            serviceScope.async { connection.sendFramed(payload, packetId) }
        }

        val results = jobs.awaitAll()
        return results.any { it } // Return true if at least one connection successfully received all chunks
    }

    private suspend fun sendToSinglePeer(connection: GattConnection, payload: ByteArray): Boolean {
        return connection.sendFramed(payload, packetCounter.getAndIncrement())
    }

    /**
     * Send a payload directly to a specific peer by device ID, bypassing broadcast.
     * Uses no-response BLE writes for maximum throughput when [noResponse] is true.
     * Only an address proven by that peer's PRESENCE is used, so a fake HELLO can't redirect
     * someone's messages; until then this falls back to broadcast.
     */
    suspend fun sendDirect(peerId: String, payload: ByteArray, noResponse: Boolean = false): Boolean {
        val macAddress = addresses.trustedAddressOf(peerId)
            ?: return broadcast(payload) // Fallback to broadcast until the address is proven
        val connection = activeGatts[macAddress]
            ?: return broadcast(payload) // Fallback if connection lost

        return connection.sendFramed(payload, packetCounter.getAndIncrement(), noResponse)
    }
}
