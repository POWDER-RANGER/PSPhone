package com.powderranger.psphone

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.PeerConnectionFactory

/**
 * Foreground service responsible for managing screen capture using MediaProjection,
 * integrating with MirrorEngine and SessionManager, and handling WebRTC PeerConnectionFactory.
 */
class MirrorService : Service() {

    companion object {
        const val TAG = "MirrorService"
        const val NOTIFICATION_CHANNEL_ID = "mirror_service_channel"
        const val NOTIFICATION_CHANNEL_NAME = "PSPhone Mirror Service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CAPTURE = "com.powderranger.psphone.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.powderranger.psphone.action.STOP_CAPTURE"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var mirrorEngine: MirrorEngine? = null
    private var sessionManager: SessionManager? = null
    private var isCapturing: Boolean = false

    /**
     * Binder to allow clients to access public methods of the service.
     */
    inner class LocalBinder : Binder() {
        fun getService(): MirrorService = this@MirrorService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize notification channel for foreground service
        createNotificationChannel()

        // Get MediaProjectionManager system service
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Initialize PeerConnectionFactory for WebRTC
        initializePeerConnectionFactory()

        // Initialize MirrorEngine (assumed to be your custom screen capture engine)
        mirrorEngine = MirrorEngine()

        // Initialize SessionManager (assumed to be your custom session manager)
        sessionManager = SessionManager(applicationContext)

        // Start as a foreground service with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification("Waiting to start screen mirroring..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                Log.d(TAG, "Received ACTION_START_CAPTURE")
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                if (data != null && resultCode == Activity.RESULT_OK) {
                    startScreenCapture(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid projection data or result code")
                }
            }
            ACTION_STOP_CAPTURE -> {
                Log.d(TAG, "Received ACTION_STOP_CAPTURE")
                stopScreenCapture()
            }
            else -> {
                Log.d(TAG, "Service started without specific action")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopScreenCapture()
        peerConnectionFactory?.dispose()
        super.onDestroy()
    }

    /**
     * Initializes the WebRTC PeerConnectionFactory.
     */
    private fun initializePeerConnectionFactory() {
        // PeerConnectionFactory initialization options
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory
            .builder()
            .createPeerConnectionFactory()
        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    /**
     * Starts screen capture using MediaProjection and initializes MirrorEngine.
     *
     * @param resultCode The result code returned from the screen capture permission activity.
     * @param data The intent data returned from the screen capture permission activity.
     */
    fun startScreenCapture(resultCode: Int, data: Intent) {
        if (isCapturing) {
            Log.w(TAG, "Screen capture already running")
            return
        }

        mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            return
        }

        // Initialize MirrorEngine with MediaProjection and PeerConnectionFactory
        mirrorEngine?.initialize(mediaProjection!!, peerConnectionFactory!!)
        sessionManager?.onMirrorStarted()

        // Start capturing screen
        mirrorEngine?.startCapture()
        isCapturing = true
        updateNotification("Screen mirroring is running.")

        Log.d(TAG, "Screen capture started")
    }

    /**
     * Stops the current screen capture and releases resources.
     */
    fun stopScreenCapture() {
        if (!isCapturing) {
            Log.w(TAG, "Screen capture not running")
            return
        }

        mirrorEngine?.stopCapture()
        sessionManager?.onMirrorStopped()
        mediaProjection?.stop()
        mediaProjection = null
        isCapturing = false
        updateNotification("Screen mirroring stopped.")

        Log.d(TAG, "Screen capture stopped")
    }

    /**
     * Creates a notification channel for foreground service notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for PSPhone MirrorService"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds a notification for the foreground service.
     *
     * @param contentText Text to display in the notification.
     */
    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PSPhone Mirroring")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_mirror) // Ensure you have this icon in your resources
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Updates the notification with new content text.
     */
    private fun updateNotification(contentText: String) {
        val notification = buildNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
