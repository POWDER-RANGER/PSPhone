package com.powderranger.psphone

import android.content.Context
import android.hardware.input.InputManager as AndroidInputManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * InputManager handles PlayStation controller and touchpad input mapping.
 * Maps DualShock 4 and DualSense controller inputs using native Android Input APIs.
 * Provides haptic feedback and touch input handling.
 *
 * Sony SDK Compliance: Uses only Android native input APIs.
 */
class InputManager(private val context: Context) {

    companion object {
        private const val TAG = "PSPhone.InputManager"

        // PlayStation button mappings (Android KeyEvent codes)
        const val PS_BUTTON_CROSS = KeyEvent.KEYCODE_BUTTON_A
        const val PS_BUTTON_CIRCLE = KeyEvent.KEYCODE_BUTTON_B
        const val PS_BUTTON_SQUARE = KeyEvent.KEYCODE_BUTTON_X
        const val PS_BUTTON_TRIANGLE = KeyEvent.KEYCODE_BUTTON_Y
        const val PS_BUTTON_L1 = KeyEvent.KEYCODE_BUTTON_L1
        const val PS_BUTTON_R1 = KeyEvent.KEYCODE_BUTTON_R1
        const val PS_BUTTON_L2 = KeyEvent.KEYCODE_BUTTON_L2
        const val PS_BUTTON_R2 = KeyEvent.KEYCODE_BUTTON_R2
        const val PS_BUTTON_SHARE = KeyEvent.KEYCODE_BUTTON_SELECT
        const val PS_BUTTON_OPTIONS = KeyEvent.KEYCODE_BUTTON_START
        const val PS_BUTTON_PS = KeyEvent.KEYCODE_BUTTON_MODE
        const val PS_BUTTON_L3 = KeyEvent.KEYCODE_BUTTON_THUMBL
        const val PS_BUTTON_R3 = KeyEvent.KEYCODE_BUTTON_THUMBR

        // Analog stick axes
        const val AXIS_LEFT_X = MotionEvent.AXIS_X
        const val AXIS_LEFT_Y = MotionEvent.AXIS_Y
        const val AXIS_RIGHT_X = MotionEvent.AXIS_Z
        const val AXIS_RIGHT_Y = MotionEvent.AXIS_RZ
        const val AXIS_L2_TRIGGER = MotionEvent.AXIS_LTRIGGER
        const val AXIS_R2_TRIGGER = MotionEvent.AXIS_RTRIGGER
    }

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as AndroidInputManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionManager: SessionManager? = null
    private var inputCallback: InputCallback? = null
    private var isActive = false

    // Controller state
    private val buttonStates = mutableMapOf<Int, Boolean>()
    private val axisValues = mutableMapOf<Int, Float>()
    private var touchpadActive = false
    private var touchpadX = 0f
    private var touchpadY = 0f

    /**
     * Initialize input manager with session manager for sending input events.
     */
    fun initialize(sessionManager: SessionManager) {
        Log.d(TAG, "Initializing InputManager")
        this.sessionManager = sessionManager
        registerDeviceListener()
    }

    /**
     * Register input device listener to detect controller connections.
     */
    private fun registerDeviceListener() {
        inputManager.registerInputDeviceListener(object : AndroidInputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId)
                if (isPlayStationController(device)) {
                    Log.d(TAG, "PlayStation controller connected: ${device.name}")
                    inputCallback?.onControllerConnected(device)
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                Log.d(TAG, "Input device removed: $deviceId")
                inputCallback?.onControllerDisconnected(deviceId)
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                Log.d(TAG, "Input device changed: $deviceId")
            }
        }, mainHandler)
    }

    /**
     * Check if device is a PlayStation controller.
     */
    private fun isPlayStationController(device: InputDevice?): Boolean {
        if (device == null) return false
        val sources = device.sources
        val hasGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val hasJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val name = device.name.lowercase()
        val isPSController = name.contains("dualshock") || 
                            name.contains("dualsense") ||
                            name.contains("playstation") ||
                            name.contains("ps4") ||
                            name.contains("ps5")
        return (hasGamepad || hasJoystick) && isPSController
    }

    /**
     * Handle key events from controller.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isActive) return false

        val keyCode = event.keyCode
        val action = event.action
        val isPressed = action == KeyEvent.ACTION_DOWN

        // Update button state
        buttonStates[keyCode] = isPressed

        // Send to session manager
        val inputData = createInputPacket(
            type = "button",
            code = keyCode,
            value = if (isPressed) 1f else 0f
        )
        sessionManager?.sendInputEvent(inputData)

        Log.v(TAG, "Button ${getButtonName(keyCode)}: ${if (isPressed) "pressed" else "released"}")
        return true
    }

    /**
     * Handle motion events from controller (analog sticks, triggers, touchpad).
     */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!isActive) return false

        val device = InputDevice.getDevice(event.deviceId)
        if (!isPlayStationController(device)) return false

        // Process analog sticks
        processAxis(event, AXIS_LEFT_X)
        processAxis(event, AXIS_LEFT_Y)
        processAxis(event, AXIS_RIGHT_X)
        processAxis(event, AXIS_RIGHT_Y)
        processAxis(event, AXIS_L2_TRIGGER)
        processAxis(event, AXIS_R2_TRIGGER)

        // Process touchpad (if available)
        if (event.source and InputDevice.SOURCE_TOUCHPAD == InputDevice.SOURCE_TOUCHPAD) {
            processTouchpad(event)
        }

        return true
    }

    /**
     * Process analog axis values.
     */
    private fun processAxis(event: MotionEvent, axis: Int) {
        val value = event.getAxisValue(axis)
        val previousValue = axisValues[axis] ?: 0f

        // Dead zone filtering
        val filteredValue = applyDeadZone(value)

        if (filteredValue != previousValue) {
            axisValues[axis] = filteredValue

            val inputData = createInputPacket(
                type = "axis",
                code = axis,
                value = filteredValue
            )
            sessionManager?.sendInputEvent(inputData)
        }
    }

    /**
     * Process touchpad input (DualShock 4 / DualSense).
     */
    private fun processTouchpad(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchpadActive = true
                touchpadX = event.x
                touchpadY = event.y

                val inputData = createInputPacket(
                    type = "touchpad",
                    code = 0,
                    value = touchpadX,
                    extraData = mapOf("y" to touchpadY, "active" to true)
                )
                sessionManager?.sendInputEvent(inputData)
            }
            MotionEvent.ACTION_UP -> {
                touchpadActive = false
                val inputData = createInputPacket(
                    type = "touchpad",
                    code = 0,
                    value = 0f,
                    extraData = mapOf("active" to false)
                )
                sessionManager?.sendInputEvent(inputData)
            }
        }
    }

    /**
     * Apply dead zone to analog values.
     */
    private fun applyDeadZone(value: Float, threshold: Float = 0.1f): Float {
        return if (Math.abs(value) < threshold) 0f else value
    }

    /**
     * Create input data packet.
     */
    private fun createInputPacket(
        type: String,
        code: Int,
        value: Float,
        extraData: Map<String, Any>? = null
    ): ByteArray {
        // Create binary packet format for transmission
        // Format: [type:1byte][code:4bytes][value:4bytes][extra:variable]
        val typeBytes = type.toByteArray()
        val packet = mutableListOf<Byte>()
        packet.add(typeBytes.size.toByte())
        packet.addAll(typeBytes.toList())
        packet.addAll(intToBytes(code).toList())
        packet.addAll(floatToBytes(value).toList())
        
        // TODO: Serialize extraData if needed
        return packet.toByteArray()
    }

    /**
     * Trigger haptic feedback (vibration).
     */
    fun triggerHaptic(intensity: Float, duration: Long = 100) {
        if (!vibrator.hasVibrator()) return

        try {
            val amplitudeValue = (intensity * 255).toInt().coerceIn(1, 255)
            val effect = VibrationEffect.createOneShot(duration, amplitudeValue)
            vibrator.vibrate(effect)
            Log.d(TAG, "Haptic feedback triggered: intensity=$intensity, duration=$duration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger haptic feedback", e)
        }
    }

    /**
     * Get button name for logging.
     */
    private fun getButtonName(keyCode: Int): String {
        return when (keyCode) {
            PS_BUTTON_CROSS -> "Cross"
            PS_BUTTON_CIRCLE -> "Circle"
            PS_BUTTON_SQUARE -> "Square"
            PS_BUTTON_TRIANGLE -> "Triangle"
            PS_BUTTON_L1 -> "L1"
            PS_BUTTON_R1 -> "R1"
            PS_BUTTON_L2 -> "L2"
            PS_BUTTON_R2 -> "R2"
            PS_BUTTON_SHARE -> "Share"
            PS_BUTTON_OPTIONS -> "Options"
            PS_BUTTON_PS -> "PS"
            PS_BUTTON_L3 -> "L3"
            PS_BUTTON_R3 -> "R3"
            else -> "Unknown($keyCode)"
        }
    }

    /**
     * Start input processing.
     */
    fun start() {
        Log.d(TAG, "Starting input processing")
        isActive = true
    }

    /**
     * Stop input processing.
     */
    fun stop() {
        Log.d(TAG, "Stopping input processing")
        isActive = false
        buttonStates.clear()
        axisValues.clear()
    }

    /**
     * Set input callback for controller events.
     */
    fun setInputCallback(callback: InputCallback) {
        this.inputCallback = callback
    }

    /**
     * Helper: Convert int to byte array.
     */
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    /**
     * Helper: Convert float to byte array.
     */
    private fun floatToBytes(value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return intToBytes(bits)
    }

    /**
     * Callback interface for input events.
     */
    interface InputCallback {
        fun onControllerConnected(device: InputDevice)
        fun onControllerDisconnected(deviceId: Int)
    }
}
