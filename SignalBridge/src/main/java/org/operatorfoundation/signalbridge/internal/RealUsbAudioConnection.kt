package org.operatorfoundation.signalbridge.internal

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.exceptions.AudioRecordInitializationException
import org.operatorfoundation.signalbridge.exceptions.AudioRecordingException
import org.operatorfoundation.signalbridge.exceptions.DeviceDisconnectedException
import org.operatorfoundation.signalbridge.exceptions.RecordingAlreadyActiveException
import org.operatorfoundation.signalbridge.exceptions.RecordingNotActiveException
import org.operatorfoundation.signalbridge.exceptions.UnsupportedAudioConfigurationException
import org.operatorfoundation.signalbridge.models.AudioData
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.RecordingState
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 2 - Real implementation of UsbAudioConnection using actual USB and AudioRecord
 *
 * This class integrates all the real components:
 * - Real USB device management
 * - Real AudioRecord integration
 * - Real audio level monitoring
 * - Real device state tracking
 */
internal class RealUsbAudioConnection(
    private val context: Context,
    override val device: UsbAudioDevice,
    private val usbDevice: UsbDevice,
    private val usbManager: UsbManager
) : UsbAudioConnection {

    companion object {
        // Audio level calculation constants
        private const val PEAK_HOLD_TIME_MS = 1000L
        private const val AVERAGE_CALCULATION_WINDOW_SIZE = 4096
    }

    // Core audio recording manager
    private var audioRecordManager: UsbAudioRecordManager? = null

    // State management
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Stopped)
    private val _audioLevel = MutableStateFlow(
        AudioLevelInfo(0f, 0f, 0f, System.currentTimeMillis())
    )

    // Audio level tracking
    private var peakLevel = 0f
    private var peakTimestamp = 0L
    private val recentSamples = mutableListOf<Float>()

    // Recording job management
    private var recordingJob: Job? = null

    /**
     * Initializes the USB audio connection and tests AudioRecord compatibility
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun initializeAudioRecord(): ConnectionInitResult {
        Timber.d("Initializing real USB audio connection for device: ${device.displayName}")

        return try {
            // Validate device configuration
            if (!device.capabilities.supportsConfiguration(48000, 1, 16)) {
                return ConnectionInitResult.Failed(
                    UnsupportedAudioConfigurationException(48000, 1, 16),
                    "Device does not support required audio configuration"
                )
            }

            // Create and initialize AudioRecord manager
            val manager = UsbAudioRecordManager(usbDevice)
            val audioRecordResult = manager.initializeAudioRecord()

            if (audioRecordResult.isSuccess) {
                audioRecordManager = manager

                val successResult = audioRecordResult as AudioRecordResult.Success
                Timber.i("AudioRecord initialized successfully for USB device: ${device.displayName}")
                Timber.i("Using audio source: ${successResult.audioSource}")

                ConnectionInitResult.Success(
                    audioRecordInfo = manager.getAudioInfo(),
                    audioSource = successResult.audioSource,
                    message = "AudioRecord initialized with source ${successResult.audioSource}"
                )
            } else {
                val failedResult = audioRecordResult as AudioRecordResult.Failed
                Timber.e("AudioRecord initialization failed for device: ${device.displayName}")
                Timber.e("Error: ${failedResult.error.message}")

                ConnectionInitResult.Failed(
                    failedResult.error,
                    "AudioRecord cannot access USB audio device"
                )
            }

        } catch (exception: Exception) {
            Timber.e(exception, "Exception during AudioRecord initialization")
            ConnectionInitResult.Failed(
                exception,
                "Unexpected error during initialization: ${exception.message}"
            )
        }
    }

    override fun startRecording(): Flow<AudioData> {
        Timber.d("Starting real audio recording for device: ${device.displayName}")

        // Check if already recording
        if (_recordingState.value is RecordingState.Recording) {
            throw RecordingAlreadyActiveException()
        }

        // Check if device is still connected
        if (!isDeviceConnectedInternal()) {
            throw DeviceDisconnectedException(device.displayName)
        }

        val manager = audioRecordManager
            ?: throw AudioRecordInitializationException(
                0, 48000,
                cause = IllegalStateException("AudioRecord manager not initialized")
            )

        _recordingState.value = RecordingState.Starting

        return manager.startRecording()
            .onStart {
                _recordingState.value = RecordingState.Recording
                Timber.i("Real audio recording started for device: ${device.displayName}")
            }
            .onEach { audioData ->
                // Update audio level information
                updateAudioLevel(audioData.samples, audioData.timestamp)
            }
            .catch { exception ->
                Timber.e(exception, "Error in audio recording flow")
                _recordingState.value = RecordingState.Error(
                    message = "Recording failed: ${exception.message}",
                    cause = exception
                )
                throw exception
            }
            .onCompletion { cause ->
                _recordingState.value = if (cause == null) {
                    RecordingState.Stopped
                } else {
                    RecordingState.Error(
                        message = "Recording stopped due to error: ${cause.message}",
                        cause = cause
                    )
                }
                Timber.d("Real audio recording flow completed")
            }
    }

    override suspend fun stopRecording() {
        Timber.d("Stopping real audio recording")

        if (_recordingState.value !is RecordingState.Recording) {
            throw RecordingNotActiveException()
        }

        _recordingState.value = RecordingState.Stopping

        try {
            // Cancel the recording job
            recordingJob?.cancel()
            recordingJob = null

            // Stop AudioRecord
            audioRecordManager?.stopRecording()

            _recordingState.value = RecordingState.Stopped
            Timber.i("Real audio recording stopped successfully")

        } catch (exception: Exception) {
            Timber.e(exception, "Error stopping real audio recording")
            _recordingState.value = RecordingState.Error(
                message = "Failed to stop recording: ${exception.message}",
                cause = exception
            )
            throw AudioRecordingException(
                recordingState = _recordingState.value.toString(),
                message = "Failed to stop recording: ${exception.message}",
                cause = exception
            )
        }
    }

    override fun getAudioLevel(): Flow<AudioLevelInfo> {
        return _audioLevel.asStateFlow()
    }

    override fun getRecordingState(): Flow<RecordingState> {
        return _recordingState.asStateFlow()
    }

    override suspend fun isDeviceConnected(): Boolean {
        return isDeviceConnectedInternal()
    }

    private fun isDeviceConnectedInternal(): Boolean {
        return try {
            // Check if USB device is still in the system device list
            val currentDevices = usbManager.deviceList
            val deviceStillConnected = currentDevices.containsValue(usbDevice)

            // Check if we still have permission
            val hasPermission = usbManager.hasPermission(usbDevice)

            val isConnected = deviceStillConnected && hasPermission

            if (!isConnected) {
                Timber.d("Device connection check failed - Connected: $deviceStillConnected, Permission: $hasPermission")
            }

            isConnected
        } catch (exception: Exception) {
            Timber.w(exception, "Error checking device connection status")
            false
        }
    }

    override suspend fun disconnect() {
        Timber.d("Disconnecting from real USB audio device: ${device.displayName}")

        try {
            // Stop any active recording
            if (_recordingState.value is RecordingState.Recording) {
                stopRecording()
            }

            // Release AudioRecord resources
            audioRecordManager?.release()
            audioRecordManager = null

            // Cancel any ongoing jobs
            recordingJob?.cancel()
            recordingJob = null

            // Reset state
            _recordingState.value = RecordingState.Stopped
            _audioLevel.value = AudioLevelInfo(0f, 0f, 0f, System.currentTimeMillis())

            Timber.i("Disconnected from real USB audio device successfully")

        } catch (exception: Exception) {
            Timber.e(exception, "Error during real device disconnection")
            throw AudioRecordingException(
                recordingState = _recordingState.value.toString(),
                message = "Failed to disconnect: ${exception.message}",
                cause = exception
            )
        }
    }

    /**
     * Handles unexpected device disconnection (called when USB device is physically removed)
     */
    internal fun handleDeviceDisconnection() {
        Timber.w("Handling unexpected real device disconnection: ${device.displayName}")

        try {
            // Update recording state immediately
            _recordingState.value = RecordingState.Error(
                message = "Device disconnected",
                cause = DeviceDisconnectedException(device.displayName)
            )

            // Stop recording and release resources
            recordingJob?.cancel()
            audioRecordManager?.stopRecording()
            audioRecordManager?.release()
            audioRecordManager = null

            // Reset audio level
            _audioLevel.value = AudioLevelInfo(0f, 0f, 0f, System.currentTimeMillis())

        } catch (exception: Exception) {
            Timber.e(exception, "Error handling real device disconnection")
        }
    }

    /**
     * Updates audio level information based on current audio samples
     */
    private fun updateAudioLevel(samples: ShortArray, timestamp: Long) {
        if (samples.isEmpty()) return

        // Calculate current RMS level
        val sumSquares = samples.map { (it.toFloat() / Short.MAX_VALUE) }.map { it * it }.sum()
        val currentLevel = sqrt(sumSquares / samples.size).coerceIn(0f, 1f)

        // Update peak level with hold time
        if (currentLevel > peakLevel || (timestamp - peakTimestamp) > PEAK_HOLD_TIME_MS) {
            peakLevel = currentLevel
            peakTimestamp = timestamp
        }

        // Calculate average level over recent samples
        recentSamples.addAll(samples.map { abs(it.toFloat() / Short.MAX_VALUE) })
        if (recentSamples.size > AVERAGE_CALCULATION_WINDOW_SIZE) {
            // Remove oldest samples to maintain window size
            val samplesToRemove = recentSamples.size - AVERAGE_CALCULATION_WINDOW_SIZE
            repeat(samplesToRemove) {
                recentSamples.removeAt(0)
            }
        }

        val averageLevel = if (recentSamples.isNotEmpty()) {
            recentSamples.average().toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

        // Update audio level flow
        val levelInfo = AudioLevelInfo(
            currentLevel = currentLevel,
            peakLevel = peakLevel,
            averageLevel = averageLevel,
            timestamp = timestamp
        )

        _audioLevel.value = levelInfo
    }

    /**
     * Gets detailed information about the AudioRecord configuration
     */
    fun getAudioRecordInfo(): AudioRecordInfo? {
        return audioRecordManager?.getAudioInfo()
    }

    /**
     * Gets the USB device information
     */
    fun getUsbDeviceInfo(): UsbDeviceInfo {
        return UsbDeviceInfo(
            usbDevice = usbDevice,
            hasPermission = usbManager.hasPermission(usbDevice),
            isConnected = isDeviceConnectedInternal(),
            deviceClass = usbDevice.deviceClass,
            deviceSubclass = usbDevice.deviceSubclass,
            deviceProtocol = usbDevice.deviceProtocol,
            interfaceCount = usbDevice.interfaceCount
        )
    }
}

/**
 * Result of connection initialization
 */
sealed class ConnectionInitResult
{
    abstract val isSuccess: Boolean
    data class Success(
        val audioRecordInfo: AudioRecordInfo?,
        val audioSource: Int,
        val message: String
    ) : ConnectionInitResult() {
        override val isSuccess = true
    }

    data class Failed(
        val exception: Exception,
        val errorMessage: String
    ) : ConnectionInitResult() {
        override val isSuccess = false
    }
}

/**
 * Information about the USB device for diagnostics
 */
data class UsbDeviceInfo(
    val usbDevice: UsbDevice,
    val hasPermission: Boolean,
    val isConnected: Boolean,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val interfaceCount: Int
) {
    val deviceName: String
        get() = usbDevice.productName ?: "Unknown Device"

    val vendorId: Int
        get() = usbDevice.vendorId

    val productId: Int
        get() = usbDevice.productId

    val deviceId: Int
        get() = usbDevice.deviceId

    override fun toString(): String {
        return "UsbDeviceInfo(name='$deviceName', id=$deviceId, " +
                "vendor=0x${vendorId.toString(16)}, product=0x${productId.toString(16)}, " +
                "class=$deviceClass, subclass=$deviceSubclass, protocol=$deviceProtocol, " +
                "interfaces=$interfaceCount, permission=$hasPermission, connected=$isConnected)"
    }
}