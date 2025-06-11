package org.operatorfoundation.signalbridge.internal

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Internal implementation of UsbAudioConnection for managing audio recording from USB devices.
 *
 * This class handles the lifecycle of audio recording, including AudioRecord management,
 * audio level monitoring, and device state tracking.
 *
 * @property contect Application context
 * @property device The USB audio device this connection represents
 * @property usbDevice The system USB device object
 * @property usbManager The system USB manager
 */
internal class UsbAudioConnectionImpl(
    private val context: Context,
    override val device: UsbAudioDevice,
    private val usbDevice: UsbDevice,
    private val usbManager: UsbManager
) : UsbAudioConnection
{
    companion object
    {
        // Audio configuration constants
        private const val SAMPLE_RATE = AudioData.DEFAULT_SAMPLE_RATE
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4 // Multiple of minimum buffer size

        // Audio level calculation constants
        private const val LEVEL_CALCULATION_WINDOW_SIZE = 1024
        private const val PEAK_HOLD_TIME_MS = 1000L
        private const val AVERAGE_CALCULATION_WINDOW_SIZE = 4096
    }

    // Audio recording components
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val sequenceNumber = AtomicLong(0)

    // State management
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Stopped)
    private val _audioLevel = MutableStateFlow(AudioLevelInfo(0f, 0f, 0f, System.currentTimeMillis()))

    // Audio level tracking
    private var peakLevel = 0f
    private var peakTimestamp = 0L
    private val recentSamples = mutableListOf<Float>()

    // Audio buffer configuration
    private val minBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val bufferSize: Int by lazy {
        minBufferSize * BUFFER_SIZE_MULTIPLIER
    }

    /**
     * Initialized the USB audio connection.
     *
     * This should be called after construction to set up the audio recording.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun initialize()
    {
        Timber.d("Initializing USB audio connection for device: ${device.displayName}")

        // Validate audio configuration
        if (!device.capabilities.supportsConfiguration(SAMPLE_RATE, 1, 16))
        {
            throw UnsupportedAudioConfigurationException(SAMPLE_RATE, 1, 16)
        }

        // Initialize AudioRecord
        initializeAudioRecord()

        Timber.i("UsbAudioConnection initialized")
    }

    /**
     * Initialized the AudioRecord instance with USB audio source.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initializeAudioRecord()
    {
        try
        {
            // TODO: We need to determine the correct audio source for USB devices.
            //  MediaRecorder.AudioSource.MIC is used here
            //  as a starting point, but this will likely need to be adjusted based on testing.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            // Verify AudioRecord was initialized successfully
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED)
            {
                throw AudioRecordInitializationException(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    cause = IllegalStateException("Failed to initialize AudioRecord"))
            }

            Timber.d("AudioRecord initialized with buffer size: $bufferSize")
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to initialize AudioRecord")
            throw AudioRecordInitializationException(
                MediaRecorder. AudioSource.MIC,
                SAMPLE_RATE,
                cause = exception
            )
        }
    }

    override fun startRecording(): Flow<AudioData>  = callbackFlow {
        Timber.d("Starting audio recording for device: ${device.displayName}")

        // Check if already recording
        if (_recordingState.value is RecordingState.Recording)
        {
            throw RecordingAlreadyActiveException()
        }

        // Check if the device is still connected
        if (!isDeviceConnectedInternal())
        {
            throw DeviceDisconnectedException(device.displayName)
        }

        _recordingState.value = RecordingState.Starting

        val audioRecord = this@UsbAudioConnectionImpl.audioRecord ?: throw AudioRecordInitializationException(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE)

        try
        {
            // Start AudioRecord
            audioRecord.startRecording()
            _recordingState.value = RecordingState.Recording

            // Create audio buffer
            val buffer = ShortArray(bufferSize / 2) // Divide by 2 because we're reading shorts

            Timber.i("Audio recording started successfully")

            // Audio recording loop
            while (currentCoroutineContext().isActive)
            {
                val samplesRead = audioRecord.read(buffer, 0, buffer.size)

                if (samplesRead > 0)
                {
                    val timestamp = System.currentTimeMillis()
                    val sequence = sequenceNumber.incrementAndGet()

                    // Create a copy of the buffer with only the samples that were read
                    val samples = buffer.copyOf(samplesRead)

                    // Update audio level information
                    updateAudioLevels(samples, timestamp)

                    // Create AudioData object
                    val audioData = AudioData(
                        samples = samples,
                        timestamp = timestamp,
                        sampleRate = SAMPLE_RATE,
                        channelCount = 1,
                        sequenceNumber = sequence
                    )

                    // Send to flow
                    trySend(audioData)
                }
                else if (samplesRead < 0)
                {
                    // Handle AudioRecord errors
                    val errorMessage = when (samplesRead)
                    {
                        AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
                        AudioRecord.ERROR_BAD_VALUE -> "Bad value"
                        AudioRecord.ERROR_DEAD_OBJECT -> "Dead object"
                        AudioRecord.ERROR -> "Generic error"
                        else -> "Unknown error code: $samplesRead"
                    }

                    Timber.e("AudioRecord read error: $errorMessage")
                    throw AudioRecordingException("READING", "AudioRecord read failed: $errorMessage")
                }
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during audio recording")
            _recordingState.value = RecordingState.Error(
                message = "Recording failed: ${exception.message}",
                cause = exception
            )
            throw exception
        }
        finally
        {
            // Clean up recording state
            try
            {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    audioRecord.stop()
                }

                _recordingState.value = RecordingState.Stopped
                Timber.d("Audio recording stopped")
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error stopping AudioRecord")
            }
        }

        awaitClose {
            Timber.d("Audio recording flow closed")
        }

    }.flowOn(Dispatchers.IO)

    override suspend fun stopRecording()
    {
        Timber.d("Stopping audio recording")

        if (_recordingState.value !is RecordingState.Recording)
        {
            throw RecordingNotActiveException()
        }

        _recordingState.value = RecordingState.Stopping

        // Cancel the recording job
        recordingJob?.cancel()
        recordingJob = null

        // Stop AudioRecord
        try
        {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    record.stop()
                }
            }
            _recordingState.value = RecordingState.Stopped
            Timber.i("Audio recording stopped successfully")
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error stopping audio recording")
            _recordingState.value = RecordingState.Error(
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

    /**
     * Internal method to check device connection status.
     */
    private fun isDeviceConnectedInternal(): Boolean
    {
        return try
        {
            usbManager.deviceList.containsValue(usbDevice) &&
                    usbManager.hasPermission(usbDevice)
        }
        catch (exception: Exception)
        {
            Timber.w(exception, "Error checking device connection status")
            false
        }
    }

    override suspend fun disconnect()
    {
        Timber.d("Disconnecting from USB audio device: ${device.displayName}")

        try
        {
            // Stop any active recording
            if (_recordingState.value is RecordingState.Recording)
            {
                stopRecording()
            }

            // Release AudioRecord resources
            audioRecord?.release()
            audioRecord = null

            // Cancel any ongoing jobs
            recordingJob?.cancel()
            recordingJob = null

            Timber.i("Disconnected from USB audio device successfully")

        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during disconnection")
            throw AudioRecordingException(
                recordingState = _recordingState.value.toString(),
                message = "Failed to disconnect: ${exception.message}",
                cause = exception
            )
        }
    }

    /**
     * Handles unexpected device disconnection.
     * This is called when the physical USB device is removed.
     */
    internal fun handleDeviceDisconnection()
    {
        Timber.w("Handling unexpected device disconnection")

        try
        {
            // Update recording state
            _recordingState.value = RecordingState.Error(
                message = "Device disconnected",
                cause = DeviceDisconnectedException(device.displayName)
            )

            // Stop recording and release resources
            recordingJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error handling device disconnection")
        }
    }

    /**
     * Updates audio level information based on the current audio samples.
     *
     * @param samples Audio samples to analyze
     * @param timestamp Timestamp of the samples
     */
    private fun updateAudioLevels(samples: ShortArray, timestamp: Long)
    {
        if (samples.isEmpty()) return

        // Calculate current RMS level
        val sumSquares = samples.map { (it.toFloat() / Short.MAX_VALUE) }.map { it * it }.sum()
        val currentLevel = sqrt(sumSquares / samples.size).coerceIn(0f, 1f)

        // Update peak level with hold time
        if (currentLevel > peakLevel || (timestamp - peakTimestamp) > PEAK_HOLD_TIME_MS)
        {
            peakLevel = currentLevel
            peakTimestamp = timestamp
        }

        // Calculate the average level over recent samples
        recentSamples.addAll(samples.map { abs(it.toFloat() / Short.MAX_VALUE) })

        if (recentSamples.size > AVERAGE_CALCULATION_WINDOW_SIZE)
        {
            recentSamples.removeAt(0)
        }

        val averageLevel = if (recentSamples.isNotEmpty())
        {
            recentSamples.average().toFloat().coerceIn(0f, 1f)
        }
        else
        {
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
}