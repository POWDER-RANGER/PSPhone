package com.powderranger.psphone

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SessionManager handles Bluetooth and Wi-Fi transport sessions.
 * Manages connection handshake, data transmission, and session lifecycle.
 *
 * Sony SDK Compliance: Uses only Android native networking and Bluetooth APIs.
 */
class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "PSPhone.SessionManager"
        private const val WIFI_PORT = 9295
        private const val BLUETOOTH_UUID = "00001101-0000-1000-8000-00805F9B34FB" // SPP UUID
        private const val BUFFER_SIZE = 65536 // 64KB buffer
    }

    enum class TransportType {
        WIFI, BLUETOOTH
    }

    enum class SessionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    private var transportType: TransportType = TransportType.WIFI
    private var sessionState: SessionState = SessionState.DISCONNECTED
    private var sessionCallback: SessionCallback? = null

    // Wi-Fi transport
    private var wifiSocket: Socket? = null
    private var wifiOutputStream: OutputStream? = null
    private var wifiInputStream: InputStream? = null
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // Bluetooth transport
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var bluetoothOutputStream: OutputStream? = null
    private var bluetoothInputStream: InputStream? = null

    // Executors for async operations
    private val executorService: ExecutorService = Executors.newFixedThreadPool(2)
    private var isActive = false

    /**
     * Initialize session with specified transport type.
     */
    fun initialize(type: TransportType, callback: SessionCallback) {
        Log.d(TAG, "Initializing SessionManager with transport: $type")
        this.transportType = type
        this.sessionCallback = callback
        sessionState = SessionState.DISCONNECTED
    }

    /**
     * Connect to PlayStation receiver via Wi-Fi.
     */
    fun connectWiFi(serverAddress: String, port: Int = WIFI_PORT) {
        if (sessionState == SessionState.CONNECTED || sessionState == SessionState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        sessionState = SessionState.CONNECTING
        sessionCallback?.onStateChanged(sessionState)

        executorService.execute {
            try {
                Log.d(TAG, "Connecting to WiFi: $serverAddress:$port")
                wifiSocket = Socket()
                wifiSocket?.connect(InetSocketAddress(serverAddress, port), 5000)
                wifiOutputStream = wifiSocket?.getOutputStream()
                wifiInputStream = wifiSocket?.getInputStream()

                sessionState = SessionState.CONNECTED
                isActive = true
                sessionCallback?.onStateChanged(sessionState)
                sessionCallback?.onConnected(transportType)

                // Start receiving thread
                startReceiveThread()

                Log.d(TAG, "WiFi connection established")
            } catch (e: IOException) {
                Log.e(TAG, "WiFi connection failed", e)
                sessionState = SessionState.ERROR
                sessionCallback?.onStateChanged(sessionState)
                sessionCallback?.onError(e)
                disconnect()
            }
        }
    }

    /**
     * Connect to PlayStation receiver via Bluetooth.
     */
    fun connectBluetooth(device: BluetoothDevice) {
        if (sessionState == SessionState.CONNECTED || sessionState == SessionState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not available")
            sessionCallback?.onError(Exception("Bluetooth not available"))
            return
        }

        sessionState = SessionState.CONNECTING
        sessionCallback?.onStateChanged(sessionState)

        executorService.execute {
            try {
                Log.d(TAG, "Connecting to Bluetooth device: ${device.name}")
                val uuid = UUID.fromString(BLUETOOTH_UUID)
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket?.connect()
                bluetoothOutputStream = bluetoothSocket?.outputStream
                bluetoothInputStream = bluetoothSocket?.inputStream

                sessionState = SessionState.CONNECTED
                isActive = true
                sessionCallback?.onStateChanged(sessionState)
                sessionCallback?.onConnected(transportType)

                // Start receiving thread
                startReceiveThread()

                Log.d(TAG, "Bluetooth connection established")
            } catch (e: IOException) {
                Log.e(TAG, "Bluetooth connection failed", e)
                sessionState = SessionState.ERROR
                sessionCallback?.onStateChanged(sessionState)
                sessionCallback?.onError(e)
                disconnect()
            }
        }
    }

    /**
     * Start thread to receive data from PlayStation.
     */
    private fun startReceiveThread() {
        executorService.execute {
            val buffer = ByteArray(BUFFER_SIZE)
            val inputStream = when (transportType) {
                TransportType.WIFI -> wifiInputStream
                TransportType.BLUETOOTH -> bluetoothInputStream
            }

            try {
                while (isActive && inputStream != null) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val data = buffer.copyOfRange(0, bytesRead)
                        sessionCallback?.onDataReceived(data)
                    } else if (bytesRead == -1) {
                        Log.w(TAG, "Connection closed by remote")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "Error receiving data", e)
                    sessionCallback?.onError(e)
                }
            } finally {
                disconnect()
            }
        }
    }

    /**
     * Send video frame data to PlayStation.
     */
    fun sendVideoFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (sessionState != SessionState.CONNECTED) return

        executorService.execute {
            try {
                val outputStream = when (transportType) {
                    TransportType.WIFI -> wifiOutputStream
                    TransportType.BLUETOOTH -> bluetoothOutputStream
                }

                // Send frame header (size + flags + timestamp)
                val header = createFrameHeader(data.size, info.flags, info.presentationTimeUs)
                outputStream?.write(header)
                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error sending video frame", e)
                sessionCallback?.onError(e)
            }
        }
    }

    /**
     * Send input event data to PlayStation.
     */
    fun sendInputEvent(data: ByteArray) {
        if (sessionState != SessionState.CONNECTED) return

        executorService.execute {
            try {
                val outputStream = when (transportType) {
                    TransportType.WIFI -> wifiOutputStream
                    TransportType.BLUETOOTH -> bluetoothOutputStream
                }

                outputStream?.write(data)
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error sending input event", e)
                sessionCallback?.onError(e)
            }
        }
    }

    /**
     * Update video format for decoder configuration.
     */
    fun updateVideoFormat(format: MediaFormat) {
        sessionCallback?.onFormatChanged(format)
    }

    /**
     * Create frame header for video transmission.
     */
    private fun createFrameHeader(size: Int, flags: Int, timestamp: Long): ByteArray {
        val header = ByteArray(16)
        // Size (4 bytes)
        header[0] = (size shr 24).toByte()
        header[1] = (size shr 16).toByte()
        header[2] = (size shr 8).toByte()
        header[3] = size.toByte()
        // Flags (4 bytes)
        header[4] = (flags shr 24).toByte()
        header[5] = (flags shr 16).toByte()
        header[6] = (flags shr 8).toByte()
        header[7] = flags.toByte()
        // Timestamp (8 bytes)
        header[8] = (timestamp shr 56).toByte()
        header[9] = (timestamp shr 48).toByte()
        header[10] = (timestamp shr 40).toByte()
        header[11] = (timestamp shr 32).toByte()
        header[12] = (timestamp shr 24).toByte()
        header[13] = (timestamp shr 16).toByte()
        header[14] = (timestamp shr 8).toByte()
        header[15] = timestamp.toByte()
        return header
    }

    /**
     * Disconnect session and clean up resources.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting session")
        isActive = false

        try {
            // Close Wi-Fi resources
            wifiInputStream?.close()
            wifiOutputStream?.close()
            wifiSocket?.close()

            // Close Bluetooth resources
            bluetoothInputStream?.close()
            bluetoothOutputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing connections", e)
        }

        wifiInputStream = null
        wifiOutputStream = null
        wifiSocket = null
        bluetoothInputStream = null
        bluetoothOutputStream = null
        bluetoothSocket = null

        sessionState = SessionState.DISCONNECTED
        sessionCallback?.onStateChanged(sessionState)
        sessionCallback?.onDisconnected()

        Log.d(TAG, "Session disconnected")
    }

    /**
     * Get current session state.
     */
    fun getState(): SessionState = sessionState

    /**
     * Check if session is active.
     */
    fun isConnected(): Boolean = sessionState == SessionState.CONNECTED

    /**
     * Release resources.
     */
    fun release() {
        Log.d(TAG, "Releasing SessionManager")
        disconnect()
        executorService.shutdown()
    }

    /**
     * Session callback interface.
     */
    interface SessionCallback {
        fun onStateChanged(state: SessionState)
        fun onConnected(type: TransportType)
        fun onDisconnected()
        fun onDataReceived(data: ByteArray)
        fun onFormatChanged(format: MediaFormat)
        fun onError(error: Exception)
    }
}
