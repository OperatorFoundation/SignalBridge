package org.operatorfoundation.signalbridge.internal

import android.Manifest
import android.hardware.usb.UsbDevice
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.operatorfoundation.signalbridge.exceptions.AudioRecordInitializationException
import org.operatorfoundation.signalbridge.exceptions.AudioRecordingException
import org.operatorfoundation.signalbridge.exceptions.UnsupportedAudioConfigurationException
import org.operatorfoundation.signalbridge.models.AudioData
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * AudioRecord integration for USB audio devices.
 *
 * Note: Experimental, attempting multiple approaches to see what works.
 */
internal class UsbAudioRecordManager(private val usbDevice: UsbDevice)
{
    companion object
    {
        // Audio configuration constants
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
        private const val DEFAULT_BUFFER_SIZE = 8192

        // Audio sources to test for USB compatibility
        private val AUDIO_SOURCES_TO_TEST = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.DEFAULT,
            // Note: More far-fetched...
            7, // AUDIO_SOURCE_RADIO_TUNER (hidden constant)
            1999 // AUDIO_SOURCE_HOTWORD (if available)
        )
    }

    private val sequenceNumber = AtomicLong(0)
    private var audioRecord: AudioRecord? = null

    // Calculate buffer size
    private val minBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private val bufferSize: Int by lazy {
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR)
        {
            DEFAULT_BUFFER_SIZE
        }
        else
        {
            minBufferSize * BUFFER_SIZE_MULTIPLIER
        }
    }

    /**
     * Attempts to initialize AudioRecord for a given USB audio device.
     * Tests multiple audio sources to find one that works.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun initializeAudioRecord(): AudioRecordResult
    {
        Timber.d("Attempting to initialize AudioRecord for USB device: ${usbDevice.productName}")

        // Test different audio sources
        for (audioSource in AUDIO_SOURCES_TO_TEST)
        {
            val result = tryAudioSource(audioSource)

            when (result)
            {
                is AudioRecordResult.Success ->
                {
                    Timber.i("Successfully initialized AudioRecord with source: $audioSource")
                    return result
                }
                is AudioRecordResult.Failed ->
                {
                    Timber.d("Audio source $audioSource failed: ${result.error}")
                }
            }
        }

        // If all sources fail, try to get some diagnostic info
        return AudioRecordResult.Failed(
            AudioRecordInitializationException(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                "IllegalStateException:No compatible audio source found for USB device"
            )
        )
    }

    /**
     * Tests a specific audio source for compatibility.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun tryAudioSource(audioSource: Int): AudioRecordResult
    {
        return try {
            Timber.d("Testing audio source: $audioSource")

            // Check minimum buffer size for this configuration
            val testMinBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (testMinBufferSize == AudioRecord.ERROR_BAD_VALUE || testMinBufferSize == AudioRecord.ERROR)
            {
                return AudioRecordResult.Failed(
                    UnsupportedAudioConfigurationException(SAMPLE_RATE, 1, 16)
                )
            }

            // Create the AudioRecord instance
            val testAudioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(testMinBufferSize * BUFFER_SIZE_MULTIPLIER, DEFAULT_BUFFER_SIZE)
            )

            // Check if initialization was successful
            when (testAudioRecord.state)
            {
                AudioRecord.STATE_INITIALIZED ->
                {
                    audioRecord = testAudioRecord
                    Timber.d("AudioRecord initialized successfully with source $audioSource")
                    AudioRecordResult.Success(testAudioRecord, audioSource)
                }
                else ->
                {
                    testAudioRecord.release()
                    AudioRecordResult.Failed(
                        AudioRecordInitializationException(
                            audioSource,
                            SAMPLE_RATE,
                            "IllegalStateException: AudioRecord state: ${testAudioRecord.state}"
                        )
                    )
                }
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Exception testing audio source $audioSource")
            AudioRecordResult.Failed(
                AudioRecordInitializationException(audioSource, SAMPLE_RATE, exception.message.toString())
            )
        }
    }

    /**
     * Starts recording audio and returns a Flow of audio data.
     */
    fun startRecording(): Flow<AudioData> = callbackFlow {
        val currentAudioRecord = audioRecord ?: throw AudioRecordInitializationException(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, "Attempted to start recording when AudioRecorder is not initialized.")

        Timber.d("Starting audio recording from USB device: ${usbDevice.productName}")

        try
        {
            // Start recording
            currentAudioRecord.startRecording()

            // Verify recording state
            if (currentAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING)
            {
                throw AudioRecordInitializationException(currentAudioRecord.audioSource, SAMPLE_RATE, "Illegal state Exception: Failed to start recording, state: ${currentAudioRecord.recordingState}")
            }

            Timber.i("Audio recording started successfully")

            // Create a buffer for reading audio data
            val buffer = ShortArray(bufferSize / 2) // Divide by two for 16-bit samples

            // Main recording loop
            while (currentCoroutineContext().isActive)
            {
                val samplesRead = currentAudioRecord.read(buffer, 0, buffer.size)

                when
                {
                    samplesRead > 0 ->
                    {
                        // Successfully read audio data
                        val timestamp = System.currentTimeMillis()
                        val sequence = sequenceNumber.incrementAndGet()

                        // Create a copy of the buffer with the actual samples read
                        val samples = buffer.copyOf(samplesRead)

                        // Create an AudioData object
                        val audioData = AudioData(
                            samples = samples,
                            timestamp = timestamp,
                            sampleRate = SAMPLE_RATE,
                            channelCount = 1,
                            sequenceNumber = sequence
                        )

                        // Send to Flow
                        trySend(audioData)

                        // Log progress occassionally
                        if (sequence % 1000L == 0L)
                        {
                            Timber.d("Audio recording progress: ${sequence} buffers, latest: ${samples.size} samples")
                        }
                    }

                    samplesRead == 0 ->
                    {
                        // No data available, continue
                        Timber.v("No audio data available")
                    }

                    else ->
                    {
                        // Error occurred
                        val errorMessage = when (samplesRead)
                        {
                            AudioRecord.ERROR_INVALID_OPERATION -> "Invalid operation"
                            AudioRecord.ERROR_BAD_VALUE -> "Bad value"
                            AudioRecord.ERROR_DEAD_OBJECT -> "Dead object - device may have been disconnected"
                            AudioRecord.ERROR -> "Generic error"
                            else -> "Unknown error code: $samplesRead"
                        }

                        Timber.e("AudioRecord read error: $errorMessage")

                        // For dead object error, the device was likely disconnected
                        if (samplesRead == AudioRecord.ERROR_DEAD_OBJECT)
                        {
                            close(Exception("USB audio device disconnected"))
                        }
                        else
                        {
                            close(Exception("AudioRecord read failed: $errorMessage"))
                        }
                        break
                    }
                }
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during audio recording")
            close(exception)
        }
        finally
        {
            // Stop recording
            try
            {
                if (currentAudioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    currentAudioRecord.stop()
                    Timber.d("Audio recording stopped")
                }
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

    /**
     * Stops audio recording
     */
    fun stopRecording()
    {
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    record.stop()
                    Timber.d("Audio recording stopped")
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error stopping audio recording")
            }
        }
    }

    /**
     * Releases AudioRecord resources
     */
    fun release()
    {
        audioRecord?.let { record ->
            try
            {
                record.release()
                Timber.d("AudioRecord resources released")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error releasing AudioRecord")
            }
        }
        audioRecord = null
    }

    /**
     * Gets information about the current audio configuration
     */
    fun getAudioInfo(): AudioRecordInfo?
    {
        val record = audioRecord ?: return null

        return AudioRecordInfo(
            state = record.state,
            recordingState = record.recordingState,
            sampleRate = SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSize = bufferSize,
            minBufferSize = minBufferSize
        )
    }
}

/**
 * The result of an AudioRecord initialization attempt.
 */
sealed class AudioRecordResult
{
    /**
     * Indicates whether this result represents a successful AudioRecord initialization
     */
    abstract val isSuccess: Boolean

    /**
     * The error associated with this result, null if successful
     */
    abstract val error: Exception?

    data class Success(val audioRecord: AudioRecord, val audioSource: Int): AudioRecordResult()
    {
        override val isSuccess = true
        override val error: Exception? = null
    }

    data class Failed(override val error: Exception): AudioRecordResult()
    {
        override val isSuccess = false
    }
}

/**
 * Information about AudioRecord configuration.
 */
data class AudioRecordInfo(
    val state: Int,
    val recordingState: Int,
    val sampleRate: Int,
    val channelConfig: Int,
    val audioFormat: Int,
    val bufferSize: Int,
    val minBufferSize: Int
)
{
    val isInitialized: Boolean
        get() = state == AudioRecord.STATE_INITIALIZED

    val isRecording: Boolean
        get() = recordingState == AudioRecord.RECORDSTATE_RECORDING
}