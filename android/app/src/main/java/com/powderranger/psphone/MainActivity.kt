package com.powderranger.psphone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.webrtc.PeerConnectionFactory

class MainActivity : AppCompatActivity() {

    // UI components for device discovery
    private lateinit var deviceListView: ListView
    private lateinit var discoverButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private val deviceNames = mutableListOf<String>()

    // MediaProjection
    private var mediaProjectionManager: MediaProjectionManager? = null

    // Permissions
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.FOREGROUND_SERVICE
    )
    private val PERMISSION_REQUEST_CODE = 1

    // SessionManager and MirrorService (should be implemented in your project)
    private lateinit var sessionManager: SessionManager
    private lateinit var mirrorService: MirrorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        deviceListView = findViewById(R.id.device_list_view)
        discoverButton = findViewById(R.id.discover_button)
        statusTextView = findViewById(R.id.status_text_view)

        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
        deviceListView.adapter = deviceListAdapter

        // Setup PeerConnectionFactory (WebRTC)
        initializePeerConnectionFactory(this)

        // Initialize SessionManager and MirrorService
        sessionManager = SessionManager(applicationContext)
        mirrorService = MirrorService(applicationContext)

        // Set up SessionManager callbacks
        sessionManager.setConnectionCallback(connectionCallback)
        sessionManager.setAuthCallback(authCallback)

        // Start device discovery when button is clicked
        discoverButton.setOnClickListener {
            discoverDevices()
        }

        // Handle device selection
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = deviceNames[position]
            connectToDevice(selectedDevice)
        }

        // Permissions
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        // MediaProjectionManager for screen capture
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    /**
     * Initialize the WebRTC PeerConnectionFactory.
     */
    private fun initializePeerConnectionFactory(context: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    /**
     * Simulate device discovery and update UI.
     * Replace with actual discovery logic as needed.
     */
    private fun discoverDevices() {
        statusTextView.text = "Discovering devices..."
        // Simulate discovered devices
        deviceNames.clear()
        deviceNames.addAll(listOf("PS5-LivingRoom", "PS4-Bedroom", "PS5-Office"))
        deviceListAdapter.notifyDataSetChanged()
        statusTextView.text = "Select a device to connect."
    }

    /**
     * Connect to the selected device using SessionManager.
     */
    private fun connectToDevice(deviceName: String) {
        statusTextView.text = "Connecting to $deviceName..."
        sessionManager.connectToDevice(deviceName)
    }

    /**
     * Request MediaProjection for screen capture.
     * Call this when screen sharing/mirroring is needed.
     */
    private fun requestScreenCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        startForResult.launch(intent)
    }

    // Handle MediaProjection result
    private val startForResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            // Pass the MediaProjection data to MirrorService for screen capture
            mirrorService.startMirroring(result.resultCode, data)
            statusTextView.text = "Screen mirroring started."
        } else {
            statusTextView.text = "Screen capture permission denied."
        }
    }

    /**
     * Check if all required permissions are granted.
     */
    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Handle runtime permission result.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if any permission was denied
            if (!hasAllPermissions()) {
                Toast.makeText(this, "Permissions required for app functionality.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * SessionManager.ConnectionCallback implementation.
     * Replace with actual implementation.
     */
    private val connectionCallback = object : SessionManager.ConnectionCallback {
        override fun onConnected() {
            runOnUiThread {
                statusTextView.text = "Connected to device."
                // Optionally launch screen capture
                requestScreenCapture()
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                statusTextView.text = "Disconnected from device."
            }
        }

        override fun onError(message: String) {
            runOnUiThread {
                statusTextView.text = "Connection error: $message"
            }
        }
    }

    /**
     * SessionManager.AuthCallback implementation.
     * Replace with actual implementation.
     */
    private val authCallback = object : SessionManager.AuthCallback {
        override fun onAuthSuccess() {
            runOnUiThread {
                statusTextView.text = "Authentication successful!"
            }
        }

        override fun onAuthFailure(reason: String) {
            runOnUiThread {
                statusTextView.text = "Authentication failed: $reason"
            }
        }
    }
}
