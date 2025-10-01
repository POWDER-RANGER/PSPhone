package com.powderranger.psphone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * MirrorEngine manages the screen mirroring pipeline using Android MediaProjection API.
 * Implements MediaCodec for H.264/H.265 encoding with Surface pipeline.
 * Handles Bluetooth/Wi-Fi transport sessions and uses Android Keystore for encryption.
 *
 * Sony SDK Compliance: Uses only native Android APIs and Sony-approved SDKs.
 */
class MirrorEngine(private val context: Context) {

    companion object {
        private const val TAG = "PSPhone.MirrorEngine"
        private const val VIDEO_MIME_TYPE = "video/avc" // H.264
        private const val VIDEO_FRAME_RATE = 60
        private const val VIDEO_IFRAME_INTERVAL = 1
        private const val VIDEO_BIT_RATE = 15_000_000 // 15 Mbps
        private const val KEYSTORE_ALIAS = "psphone_encryption_key"
        private const val ENCRYPTION_TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null
    private var sessionManager: SessionManager? = null
    private var isEncoding = false

    private val displayMetrics: DisplayMetrics = context.resources.displayMetrics
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /**
     * Initialize the mirror engine with MediaProjection.
     */
    fun initialize(projection: MediaProjection, sessionManager: SessionManager) {
        Log.d(TAG, "Initializing MirrorEngine")
        this.mediaProjection = projection
        this.sessionManager = sessionManager
        setupKeystore()
    }

    /**
     * Setup Android Keystore for AES-256 encryption.
     */
    private fun setupKeystore() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
                Log.d(TAG, "Generated encryption key in Android Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keystore setup failed", e)
        }
    }

    /**
     * Start screen capture and encoding pipeline.
     */
    fun startCapture(width: Int, height: Int, dpi: Int) {
        Log.d(TAG, "Starting capture: ${width}x${height}@${dpi}dpi")

        try {
            // Setup encoder thread
            encoderThread = HandlerThread("EncoderThread").apply {
                start()
                encoderHandler = Handler(looper)
            }

            // Configure MediaCodec for H.264 encoding
            val format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_IFRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            }

            mediaCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                setCallback(codecCallback, encoderHandler)
                start()
            }

            // Create VirtualDisplay for screen mirroring
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PSPhone-Mirror",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null
            )

            isEncoding = true
            Log.d(TAG, "Capture started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
            stopCapture()
        }
    }

    /**
     * MediaCodec callback for handling encoded frames.
     */
    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface mode doesn't use input buffers
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                val outputBuffer = codec.getOutputBuffer(index)
                if (outputBuffer != null && info.size > 0) {
                    // Encrypt frame data
                    val encryptedData = encryptFrame(outputBuffer, info.size)

                    // Send via session manager (Bluetooth/Wi-Fi)
                    sessionManager?.sendVideoFrame(encryptedData, info)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "Output format changed: $format")
            sessionManager?.updateVideoFormat(format)
        }
    }

    /**
     * Encrypt frame using Android Keystore AES-256-GCM.
     */
    private fun encryptFrame(buffer: ByteBuffer, size: Int): ByteArray {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey

            val cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val data = ByteArray(size)
            buffer.get(data)
            val encrypted = cipher.doFinal(data)

            // Prepend IV for decryption
            return iv + encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            return ByteArray(0)
        }
    }

    /**
     * Stop screen capture and release resources.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping capture")
        isEncoding = false

        virtualDisplay?.release()
        virtualDisplay = null

        inputSurface?.release()
        inputSurface = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping codec", e)
        }
        mediaCodec = null

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null

        Log.d(TAG, "Capture stopped")
    }

    /**
     * Release MediaProjection.
     */
    fun release() {
        Log.d(TAG, "Releasing MirrorEngine")
        stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
    }

    /**
     * Adjust bitrate dynamically for adaptive streaming.
     */
    fun adjustBitrate(bitrate: Int) {
        try {
            mediaCodec?.let { codec ->
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate)
                codec.setParameters(params)
                Log.d(TAG, "Bitrate adjusted to $bitrate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust bitrate", e)
        }
    }

    /**
     * Get encoding status.
     */
    fun isActive(): Boolean = isEncoding
}
