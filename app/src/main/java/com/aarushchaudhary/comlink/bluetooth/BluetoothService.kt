package com.aarushchaudhary.comlink.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission") // Ensure permissions are checked by caller UI
class BluetoothService(
    private val context: Context,
    private val meshRouter: MeshRouter,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
) {

    companion object {
        private const val TAG = "ComLinkBluetooth"
        private const val APP_NAME = "ComLinkMesh"
        // Standard SPP (Serial Port Profile) UUID for Bluetooth Classic
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var acceptThread: AcceptThread? = null
    private val connectedThreads = CopyOnWriteArrayList<ConnectedThread>()

    init {
        // Wire up the MeshRouter broadcast callback to our sockets
        meshRouter.broadcastToNetwork = { payload ->
            broadcast(payload)
        }
    }

    @Synchronized
    fun startListening() {
        if (acceptThread != null) return
        acceptThread = AcceptThread().apply { start() }
    }

    @Synchronized
    fun stopAll() {
        acceptThread?.cancel()
        acceptThread = null
        connectedThreads.forEach { it.cancel() }
        connectedThreads.clear()
    }

    @Synchronized
    fun connectToDevice(device: BluetoothDevice) {
        // Don't connect if we already have an active socket with this device
        if (connectedThreads.any { it.socket.remoteDevice.address == device.address }) {
            return
        }
        ConnectThread(device).start()
    }

    private fun broadcast(payload: ByteArray) {
        // Simple length-prefixed framing: 4 bytes for length, followed by payload
        val lengthBytes = java.nio.ByteBuffer.allocate(4).putInt(payload.size).array()
        val framedPayload = lengthBytes + payload
        
        connectedThreads.forEach { thread ->
            thread.write(framedPayload)
        }
    }

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket) {
        val connectedThread = ConnectedThread(socket)
        connectedThreads.add(connectedThread)
        connectedThread.start()
    }

    private inner class AcceptThread : Thread() {
        private val serverSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID)
        }

        override fun run() {
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    serverSocket?.accept()
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    shouldLoop = false
                    null
                }
                socket?.also {
                    manageConnectedSocket(it)
                }
            }
        }

        fun cancel() {
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter?.cancelDiscovery()

            socket?.let { socket ->
                try {
                    socket.connect()
                    manageConnectedSocket(socket)
                } catch (e: IOException) {
                    Log.e(TAG, "Connection failed", e)
                    try {
                        socket.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Could not close the client socket", closeException)
                    }
                }
            }
        }
    }

    private inner class ConnectedThread(val socket: BluetoothSocket) : Thread() {
        private val inStream: InputStream = socket.inputStream
        private val outStream: OutputStream = socket.outputStream

        override fun run() {
            val lengthBuffer = ByteArray(4)
            while (true) {
                try {
                    // Read framing length (4 bytes)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val count = inStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                        if (count == -1) throw IOException("Stream closed")
                        bytesRead += count
                    }
                    val payloadLength = java.nio.ByteBuffer.wrap(lengthBuffer).int
                    
                    if (payloadLength <= 0 || payloadLength > 10 * 1024 * 1024) { // Max 10MB sanity check
                         throw IOException("Invalid payload length: $payloadLength")
                    }

                    // Read payload
                    val payloadBuffer = ByteArray(payloadLength)
                    bytesRead = 0
                    while (bytesRead < payloadLength) {
                        val count = inStream.read(payloadBuffer, bytesRead, payloadLength - bytesRead)
                        if (count == -1) throw IOException("Stream closed")
                        bytesRead += count
                    }

                    // Feed payload to the Mesh Router
                    meshRouter.onBytesReceived(payloadBuffer)

                } catch (e: IOException) {
                    Log.d(TAG, "Input stream disconnected", e)
                    connectedThreads.remove(this)
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outStream.write(bytes)
                outStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)
                connectedThreads.remove(this)
            }
        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }
}
