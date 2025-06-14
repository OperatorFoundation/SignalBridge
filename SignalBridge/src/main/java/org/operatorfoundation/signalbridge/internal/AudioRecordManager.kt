package org.operatorfoundation.signalbridge.internal

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

import org.operatorfoundation.signalbridge.exceptions.AudioRecordException

/**
 * Pure AudioRecord wrapper - handles only AudioRecord lifecycle and streaming
 *
 * Responsibilities:
 * - AudioRecord initialization with source testing
 * - Raw audio sample streaming
 * - AudioRecord lifecycle management
 *
 * Does NOT handle:
 * - USB device management
 * - Audio level processing
 * - State management beyond AudioRecord
 * - Audio output/playback
 */
internal class AudioRecordManager
{
    companion object
    {
        // Audio configuration constants
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4

        // Audio sources to test for compatibility
        private val AUDIO_SOURCES_TO_TEST = listOf(
            MediaRecorder.AudioSource.DEFAULT,               // Default source
            MediaRecorder.AudioSource.UNPROCESSED,           // Unprocessed audio (API 24+)
            MediaRecorder.AudioSource.MIC,                   // Standard microphone
            7, // AUDIO_SOURCE_RADIO_TUNER (hidden constant, might work for some USB devices)
        )
    }

    // AudioRecord instance and configuration
    private var audioRecord: AudioRecord? = null
    private var workingAudioSource: Int = -1
    private var bufferSize: Int = 0

    /**
     * Tests multiple audio sources and initializes AudioRecord with the first working one
     */
    suspend fun initialize(): AudioRecordInitResult
    {
        Timber.d("Initializing AudioRecord with compatibility testing")

        // Test each audio source until we find one that works
        for (audioSource in AUDIO_SOURCES_TO_TEST)
        {
            Timber.d("Testing audio source: $audioSource")

            val result = tryInitializeWithSource(audioSource)
            if (result.isSuccess)
            {
                workingAudioSource = audioSource
                Timber.i("AudioRecord initialized successfully with source: $audioSource")
                return result
            }
            else
            {
                Timber.d("Audio source $audioSource failed: ${result.errorMessage}")
            }
        }

        // All sources failed - this means AudioRecord cannot access USB audio
        Timber.e("All audio sources failed - AudioRecord cannot access USB audio")
        return AudioRecordInitResult.AllSourcesFailed(
            testedSources = AUDIO_SOURCES_TO_TEST,
            errorMessage = "No compatible audio source found for USB audio device"
        )
    }

    /**
     * Attempts to initialize AudioRecord with a specific audio source
     */
    @SuppressLint("MissingPermission")
    private fun tryInitializeWithSource(audioSource: Int): AudioRecordInitResult
    {
        return try
        {
            // Calculate buffer size for this configuration
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR)
            {
                return AudioRecordInitResult.SourceFailed(
                    audioSource = audioSource,
                    errorMessage = "Invalid audio configuration for source $audioSource"
                )
            }

            // Use optimal buffer size
            bufferSize = calculateOptimalBufferSize(minBufferSize)

            // Create AudioRecord instance
            val testAudioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            // Check if initialization was successful
            if (testAudioRecord.state == AudioRecord.STATE_INITIALIZED)
            {
                audioRecord = testAudioRecord

                val recordInfo = AudioRecordInfo(
                    state = testAudioRecord.state,
                    audioSource = audioSource,
                    sampleRate = SAMPLE_RATE,
                    channelConfig = CHANNEL_CONFIG,
                    audioFormat = AUDIO_FORMAT,
                    bufferSize = bufferSize,
                    minBufferSize = minBufferSize
                )

                AudioRecordInitResult.Success(
                    audioRecordInfo = recordInfo,
                    workingAudioSource = audioSource
                )
            }
            else
            {
                // AudioRecord failed to initialize
                testAudioRecord.release()
                AudioRecordInitResult.SourceFailed(
                    audioSource = audioSource,
                    errorMessage = "AudioRecord state: ${testAudioRecord.state}"
                )
            }

        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Exception testing audio source $audioSource")

            AudioRecordInitResult.SourceFailed(
                audioSource = audioSource,
                errorMessage = "Exception: ${exception.message}",
                cause = exception
            )
        }
    }

    /**
     * Starts recording and returns a flow of raw audio samples
     * This is a pure data stream with no processing or state management
     */
    fun startRecording(): Flow<RawAudioSamples> = callbackFlow {
        val record = audioRecord
            ?: throw IllegalStateException("Unable to start recording, AudioRecord is not initialized - call initialize() first")

        Timber.d("Starting AudioRecord streaming")

        try
        {
            // Start AudioRecord
            record.startRecording()

            // Verify recording started
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING)
            {
                throw IllegalStateException("Failed to start recording, state: ${record.recordingState}")
            }

            // Create buffer for reading samples
            val sampleBuffer = ShortArray(bufferSize / 2) // Divide by 2 for 16-bit samples

            Timber.i("AudioRecord streaming started, buffer size: ${sampleBuffer.size} samples")

            // Main streaming loop - just read and emit raw samples
            while (coroutineContext.isActive)
            {
                val samplesRead = record.read(sampleBuffer, 0, sampleBuffer.size)

                when
                {
                    samplesRead > 0 ->
                    {
                        // Successfully read audio samples
                        val rawSamples = RawAudioSamples(
                            samples = sampleBuffer.copyOf(samplesRead),
                            timestamp = System.currentTimeMillis(),
                            sampleRate = SAMPLE_RATE
                        )

                        // Emit raw samples - no processing here
                        trySend(rawSamples)
                    }

                    samplesRead == 0 ->
                    {
                        // No data available, continue
                        continue
                    }

                    else ->
                    {
                        // AudioRecord error occurred
                        val errorMessage = getAudioRecordErrorName(samplesRead)
                        Timber.e("AudioRecord read error: $errorMessage")

                        // For critical errors, close the stream
                        if (samplesRead == AudioRecord.ERROR_DEAD_OBJECT)
                        {
                            close(AudioRecordException("USB audio device disconnected"))
                        }
                        else
                        {
                            close(AudioRecordException("AudioRecord read failed: $errorMessage"))
                        }

                        break
                    }
                }
            }

        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during AudioRecord streaming")
            close(exception)
        }
        finally
        {
            // Stop recording on cleanup
            try
            {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    record.stop()
                    Timber.d("AudioRecord streaming stopped")
                }
            }
            catch (e: Exception)
            {
                Timber.e(e, "Error stopping AudioRecord")
            }
        }

        awaitClose {
            Timber.d("AudioRecord streaming flow closed")
        }

    }.flowOn(Dispatchers.IO)

    /**
     * Stops the current recording session.
     * This will allow the recording Flow to complete normally.
     */
    fun stopRecording()
    {
        val record = audioRecord ?: return

        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            {
                record.stop()
                Timber.d("AudioRecord recording stopped.")
            }
            else
            {
                Timber.d("AudioRecord was not recording, state: ${record.recordingState}")
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error stopping audio record")
        }
    }

    /**
     * Checks if AudioRecord is currently recording.
     */
    fun isRecording(): Boolean
    {
        return audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    /**
     * Gets information about the current AudioRecord configuration
     *
     * @return AudioRecordInfo or none if there is no AudioRecord instance
     */
    fun getAudioRecordInfo(): AudioRecordInfo?
    {
        val record = audioRecord ?: return null

        return AudioRecordInfo(
            state = record.state,
            audioSource = workingAudioSource,
            sampleRate = SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSize = bufferSize,
            minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        )
    }

    /**
     * Checks if AudioRecord is currently initialized and ready
     */
    fun isInitialized(): Boolean
    {
        return audioRecord?.state == AudioRecord.STATE_INITIALIZED
    }

    /**
     * Releases AudioRecord resources
     * Should be called when audio recording is no longer needed
     */
    fun release()
    {
        try
        {
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                {
                    record.stop()
                }

                record.release()
                Timber.d("AudioRecord resources released")
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error releasing AudioRecord resources")
        }
        finally
        {
            audioRecord = null
            workingAudioSource = -1
            bufferSize = 0
        }
    }

    /**
     * Calculates optimal buffer size based on minimum requirements
     */
    private fun calculateOptimalBufferSize(minBufferSize: Int): Int
    {
        return when {
            minBufferSize <= 0 -> 8192 // Fallback for invalid values
            else -> minBufferSize * BUFFER_SIZE_MULTIPLIER
        }
    }

    /**
     * Converts AudioRecord error codes to human-readable names
     */
    private fun getAudioRecordErrorName(errorCode: Int): String
    {
        return when (errorCode)
        {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioRecord.ERROR -> "ERROR"
            else -> "Unknown error code: $errorCode"
        }
    }
}

/**
 * Raw audio samples with minimal metadata - no processing applied
 * This represents pure audio data from AudioRecord
 */
data class RawAudioSamples(
    val samples: ShortArray,
    val timestamp: Long,
    val sampleRate: Int
)
{
    override fun equals(other: Any?): Boolean
    {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawAudioSamples

        if (!samples.contentEquals(other.samples)) return false
        if (timestamp != other.timestamp) return false
        if (sampleRate != other.sampleRate) return false

        return true
    }

    override fun hashCode(): Int
    {
        var result = samples.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sampleRate
        return result
    }
}

/**
 * Result of AudioRecord initialization attempt
 */
sealed class AudioRecordInitResult
{
    abstract val isSuccess: Boolean
    abstract val errorMessage: String?

    data class Success(
        val audioRecordInfo: AudioRecordInfo,
        val workingAudioSource: Int
    ) : AudioRecordInitResult()
    {
        override val isSuccess = true
        override val errorMessage: String? = null
    }

    data class SourceFailed(
        val audioSource: Int,
        override val errorMessage: String,
        val cause: Exception? = null
    ) : AudioRecordInitResult()
    {
        override val isSuccess = false
    }

    data class AllSourcesFailed(
        val testedSources: List<Int>,
        override val errorMessage: String
    ) : AudioRecordInitResult()
    {
        override val isSuccess = false
    }
}

/**
 * Information about AudioRecord configuration and state
 */
data class AudioRecordInfo(
    val state: Int,
    val audioSource: Int,
    val sampleRate: Int,
    val channelConfig: Int,
    val audioFormat: Int,
    val bufferSize: Int,
    val minBufferSize: Int
)
{
    val isInitialized: Boolean
        get() = state == AudioRecord.STATE_INITIALIZED

    override fun toString(): String
    {
        return "AudioRecordInfo(state=$state, source=$audioSource, " +
                "sampleRate=$sampleRate, bufferSize=$bufferSize, " +
                "minBufferSize=$minBufferSize, initialized=$isInitialized)"
    }
}