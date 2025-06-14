package org.operatorfoundation.signalbridge.internal

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.exceptions.AudioRecordException
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Implementation of UsbAudioConnection using USB and AudioRecord
 *
 * This class integrates:
 * - USB device management
 * - AudioRecord integration
 * - Audio level monitoring
 * - Device state tracking
 */
internal class RealUsbAudioConnection(
    private val context: Context,
    override val device: UsbAudioDevice,
    private val usbDevice: UsbDevice,
    private val usbManager: UsbManager
) : UsbAudioConnection
{
    companion object
    {
        // Audio level calculation constants
        private const val PEAK_HOLD_TIME_MS = 1000L
        private const val AVERAGE_CALCULATION_WINDOW_SIZE = 4096
    }

    // Core audio recording manager
    private var audioRecordManager: AudioRecordManager? = null
    private var audioLevelProcessor: AudioLevelProcessor? = null
    private var audioOutputManager: AudioOutputManager? = null

    // Sequence number for audio data tracking
    private val sequenceNumber = AtomicLong(0)

    // State management
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Stopped)
    private val _audioLevel = MutableStateFlow(AudioLevelInfo(0f, 0f, 0f, System.currentTimeMillis()))
    private val _isPlaybackEnabled = MutableStateFlow(false)

    // Audio level tracking
    private var peakLevel = 0f
    private var peakTimestamp = 0L
    private val recentSamples = mutableListOf<Float>()

    // Recording job management
    private var recordingJob: Job? = null

    /**
     * Initializes the USB audio connection and tests AudioRecord compatibility
     */
    suspend fun initializeAudioRecord(): ConnectionInitResult
    {
        Timber.d("Initializing real USB audio connection for device: ${device.displayName}")

        return try
        {
            // Validate device configuration
            if (!device.capabilities.supportsConfiguration(48000, 1, 16))
            {
                return ConnectionInitResult.Failed(
                    UnsupportedAudioConfigurationException(48000, 1, 16),
                    "Device does not support required audio configuration"
                )
            }

            // Create and initialize the AudioRecordManager
            val recordManager = AudioRecordManager()
            val recordManagerResult = recordManager.initialize()

            if (recordManagerResult.isSuccess)
            {
                // Set up all components
                audioRecordManager = recordManager
                audioLevelProcessor = AudioLevelProcessor()
                audioOutputManager = AudioOutputManager()
                val outputInitialized = audioOutputManager!!.initialize()

                val successResult = recordManagerResult as AudioRecordInitResult.Success
                Timber.i("AudioRecordManager initialized successfully for USB device: ${device.displayName}")

                ConnectionInitResult.Success(
                    audioRecordInfo = successResult.audioRecordInfo,
                    audioSource = successResult.workingAudioSource,
                    message = "AudioRecord initialized with source ${successResult.workingAudioSource}"
                )
            }
            else
            {
                val failedResult = recordManagerResult as AudioRecordInitResult.AllSourcesFailed
                Timber.e("AudioRecord initialization failed for device: ${device.displayName}")
                Timber.e("Error: ${failedResult.errorMessage}")

                ConnectionInitResult.Failed(
                    AudioRecordException(failedResult.errorMessage),
                    failedResult.errorMessage
                )
            }

        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Exception during AudioRecord initialization")
            ConnectionInitResult.Failed(
                exception,
                "Unexpected error during initialization: ${exception.message}"
            )
        }
    }

    override fun startRecording(): Flow<AudioData>
    {
        Timber.d("Starting audio recording for device: ${device.displayName}")

        val recordManager = audioRecordManager ?: throw IllegalStateException("Not initialized")
        val levelProcessor = audioLevelProcessor ?: throw IllegalStateException("Not initialized")

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

        return recordManager.startRecording()
            .onStart {
                _recordingState.value = RecordingState.Recording
                Timber.i("Audio recording started for device: ${device.displayName}")

                if (_isPlaybackEnabled.value)
                {
                    audioOutputManager?.startPlayback()
                }
            }
            .map { rawSamples ->
                // Process audio levels using levels processor
                val levelInfo = levelProcessor.processAudioLevel(rawSamples.samples, rawSamples.timestamp)
                _audioLevel.value = levelInfo

                // Play through speakers if enabled
                if (_isPlaybackEnabled.value)
                {
                    audioOutputManager?.playAudioSamples(rawSamples.samples)
                }

                // Convert to public AudioData format
                AudioData(
                    samples = rawSamples.samples,
                    timestamp = rawSamples.timestamp,
                    sampleRate = rawSamples.sampleRate,
                    channelCount = 1,
                    sequenceNumber = sequenceNumber.incrementAndGet()
                )
            }
            .catch { exception ->
                _recordingState.value = RecordingState.Error("Recording failed", exception)
                throw exception
            }
            .onCompletion {
                _recordingState.value = RecordingState.Stopped
            }

    }

    override suspend fun stopRecording()
    {
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

    override fun getAudioLevel(): Flow<AudioLevelInfo>
    {
        return _audioLevel.asStateFlow()
    }

    override fun getRecordingState(): Flow<RecordingState>
    {
        return _recordingState.asStateFlow()
    }

    override suspend fun setPlaybackEnabled(enabled: Boolean)
    {
        Timber.d("Setting audio playback enabled: $enabled")

        _isPlaybackEnabled.value = enabled

        if (enabled && _recordingState.value is RecordingState.Recording)
        {
            // Start playback if currently recording
            val started = audioOutputManager?.startPlayback() ?: false

            if (started)
            {
                Timber.i("Audio playback enabled during recording")
            }
            else
            {
                Timber.w("Failed to start audio playback")
            }
        }
        else if (!enabled)
        {
            // Stop playback
            audioOutputManager?.stopPlayback()
            Timber.i("Audio playback disabled")
        }
    }

    override fun getPlaybackEnabled(): Flow<Boolean>
    {
        return _isPlaybackEnabled.asStateFlow()
    }

    override suspend fun isDeviceConnected(): Boolean
    {
        return isDeviceConnectedInternal()
    }

    private fun isDeviceConnectedInternal(): Boolean
    {
        return try
        {
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

    override suspend fun disconnect()
    {
        Timber.d("Disconnecting from real USB audio device: ${device.displayName}")

        try {
            // Stop any active recording
            if (_recordingState.value is RecordingState.Recording)
            {
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
        return audioRecordManager?.getAudioRecordInfo()
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