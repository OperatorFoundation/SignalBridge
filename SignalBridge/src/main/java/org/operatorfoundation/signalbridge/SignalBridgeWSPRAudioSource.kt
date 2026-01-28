package org.operatorfoundation.signalbridge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.operatorfoundation.audiocoder.AudioResampler
import org.operatorfoundation.audiocoder.WSPRAudioSource
import org.operatorfoundation.audiocoder.WSPRAudioSourceException
import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE
import org.operatorfoundation.audiocoder.models.WSPRAudioSourceStatus
import org.operatorfoundation.signalbridge.internal.AudioSourcePerformanceStatistics
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * SignalBridge implementation of AudioCoder's WSPRAudioSource interface for USB audio devices.
 *
 * This class bridges the SignalBridge USB audio library with the AudioCoder WSPR library,
 * providing an interface for WSPR stations to access USB audio.
 *
 * This implementation handles the impedence mismatch between:
 * - SignalBridge: FLow-based real-time audio streaming
 * - AudioCoder: Chunk-based audio reading for WSPR decode timing
 *
 * Usage:
 * val connection = // ... USBAudioConnection from SignalBridge
 * val audioSource = SignalBridgeWSPRAudioSource(connection)
 * val station = WSPRStation(audioSource)
 * station.startStation()
 *
 * @param usbAudioConnection Active USB audio connection from SignalBridge
 * @param bufferConfiguration Optional configuration for audio buffering behavior
 */
class SignalBridgeWSPRAudioSource(
    private val usbAudioConnection: UsbAudioConnection,
    private val bufferConfiguration: AudioBufferConfiguration = AudioBufferConfiguration.createDefault()
) : WSPRAudioSource
{
    /**
     * Thread-safe circular buffer for audio samples.
     * Stores incoming audio data from the real-time stream for later chunk-based reading.
     */
    private val audioSampleBuffer = ConcurrentLinkedQueue<Short>()

    /**
     * Job controlling the background audio collection from SignalBridge.
     * Cancelled when the audio source is cleaned up.
     */
    private var audioCollectionJob: Job? = null

    /**
     * Current operational status of this audio source.
     * Updated as conditions change during operation.
     */
    private var currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus("Not initialized")

    /**
     * Statistics for monitoring audio source performance health.
     */
    private val performanceStatistics = AudioSourcePerformanceStatistics()

    /**
     * Audio resampler for converting USB device sample rates to WSPR's required 12kHz.
     */
    private var audioResampler: AudioResampler? = null

    private var lastChunkLogTime = 0L

    // ========== WSPRAudioSource Implementation ==========

    override suspend fun initialize(): Result<Unit>
    {
        return try
        {
            Timber.d("Initializing SignalBridge WSPR audio source")

            // Verify that the US connection is still valid
            if (!usbAudioConnection.isDeviceConnected())
            {
                val errorMessage = "USB audio device is not connected"
                currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus(errorMessage)
                return Result.failure(WSPRAudioSourceException.createInitializationFailure("USB device", Exception(errorMessage)))
            }

            // Clear any existing audio buffer
            audioSampleBuffer.clear()
            performanceStatistics.reset()

            // Start background audio collection
            startBackgroundAudioCollection()

            // Update status to operational
            currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus("USB audio streaming active")

            Timber.i("SignalBridge WSPR audio source initialized successfully")
            Result.success(Unit)
        }
        catch (exception: Exception)
        {
            val errorMessage = "Failed to initialize SignalBridge audio source: ${exception.message}"
            currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus(errorMessage)
            Timber.e(exception, errorMessage)
            Result.failure(WSPRAudioSourceException.createInitializationFailure("SignalBridge USB connection", exception))
        }
    }

    override suspend fun readAudioChunk(durationMs: Long): ShortArray
    {
        return try
        {
            val requiredSampleCount = calculateRequiredSampleCount(durationMs)
            val availableSampleCount = audioSampleBuffer.size

            performanceStatistics.recordReadRequest(requiredSampleCount, availableSampleCount)

            // Log once every 5 second during collection
            val now = System.currentTimeMillis()
            if (now - lastChunkLogTime >= 5000) {
                Timber.d("CHUNK: requested=$requiredSampleCount, available=$availableSampleCount")
            }

            if (availableSampleCount < requiredSampleCount)
            {
                // Not enough audio available - return what we have
                val availableSamples = extractAvailableAudioSamples(availableSampleCount)
                performanceStatistics.recordPartialRead(availableSamples.size)

                return availableSamples
            }

            // Extract the requested amount of audio
            val requestedSamples = extractAvailableAudioSamples(requiredSampleCount)
            performanceStatistics.recordSuccessfulRead(requestedSamples.size)

            requestedSamples
        }
        catch (exception: Exception)
        {
            performanceStatistics.recordReadError()
            Timber.e(exception, "Error reading audio chunk for ${durationMs}ms")
            throw WSPRAudioSourceException.createReadFailure(exception)
        }
    }

    override suspend fun cleanup()
    {
        Timber.d("Cleaning up SignalBridge WSPR audio source")

        try
        {
            // Stop background audio collection
            audioCollectionJob?.cancel()
            audioCollectionJob?.join() // Wait for graceful shutdown
            audioCollectionJob = null

            // Clear audio buffer
            audioSampleBuffer.clear()

            // Reset reseampler state
            audioResampler?.reset()
            audioResampler = null

            // Update status
            currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus("Cleaned up")

            Timber.i("SignalBridge WSPR audio source cleanup complete")

        }
        catch (exception: Exception)
        {
            Timber.w(exception, "Error during SignalBridge audio source cleanup")
            // Don't throw exceptions from cleanup - just log them
        }
    }

    override suspend fun getSourceStatus(): WSPRAudioSourceStatus
    {
        // Update status with current buffer information
        val bufferStatus = if (currentStatus.isOperational)
        {
            val bufferDurationMs = calculateBufferDurationMilliseconds()
            val bufferUtilizationPercent = calculateBufferUtilizationPercentage()
            val resamplerInfo = audioResampler?.let { " [Resampling]" } ?: ""

            "Streaming: ${bufferDurationMs}ms buffered (${bufferUtilizationPercent}% full)${resamplerInfo}"
        }
        else
        {
            currentStatus.statusDescription
        }

        return currentStatus.copy(
            statusDescription = bufferStatus,
            lastUpdated = System.currentTimeMillis()
        )
    }

    // ========== Private Implementation Methods ==========

    /**
     * Starts background collection of audio data from the Signal connection.
     *
     * This method creates a routine that continuously reads from the SignalBridge
     * audio flow and stores samples in our buffer for later chunk-based reading.
     *
     * The coroutines handles:
     *  - Real-time audio data collection
     *  - Buffer overflow prevention
     *  - Error recovery and reconnection
     *  - Performance monitoring and statistics
     */
    private fun startBackgroundAudioCollection()
    {
        audioCollectionJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                Timber.d("Starting background audio collection from SignalBridge")

                usbAudioConnection.startRecording().collect { audioData ->

                    // Create resampler if needed
                    if (audioResampler == null && audioData.sampleRate != WSPR_REQUIRED_SAMPLE_RATE)
                    {
                        audioResampler = AudioResampler(audioData.sampleRate, WSPR_REQUIRED_SAMPLE_RATE)
                        Timber.i("Created audio resampler: ${audioData.sampleRate}Hz -> ${WSPR_REQUIRED_SAMPLE_RATE}Hz")
                    }

                    // Resample if necessary
                    val processedSamples = if (audioResampler != null)
                    {
                        audioResampler!!.resample(audioData.samples)
                    }
                    else
                    {
                        audioData.samples // No resampling needed
                    }

                    // Add new samples to our buffer
                    audioSampleBuffer.addAll(processedSamples.toList())
                    performanceStatistics.recordSamplesReceived(processedSamples.size)

                    // Manage buffer size to prevent memory issues
                    maintainBufferSize()

                    // Log periodic statistics for monitoring
                    if (performanceStatistics.shouldLogStatistics())
                    {
                        logBufferStatistics()
                    }
                }
            }
            catch (exception: Exception)
            {
                val errorMessage = "Background audio collection failed: ${exception.message}"
                currentStatus = WSPRAudioSourceStatus.createNonOperationalStatus(errorMessage)
                Timber.e(exception, errorMessage)
            }
        }
    }

    /**
     * Maintains the audio buffer withing configured size limits.
     *
     * Removes the oldest samples when the buffer exceeds maximum size to prevent
     * unbounded memory growth while preserving recent audio for WSPR processing.
     */
    private fun maintainBufferSize()
    {
        val currentBufferSize = audioSampleBuffer.size
        val maximumBufferSize = bufferConfiguration.maximumBufferSamples

        if (currentBufferSize > maximumBufferSize)
        {
            val samplesToRemove = currentBufferSize - maximumBufferSize

            repeat(samplesToRemove)
            {
                audioSampleBuffer.poll() // Remove oldest samples
            }

            performanceStatistics.recordBufferOverflow(samplesToRemove)
            Timber.v("Buffer maintenance: removed ${samplesToRemove} old samples, buffer now ${audioSampleBuffer.size} samples")
        }
    }

    /**
     * Extracts the specified number of audio samples from the buffer.
     *
     * @param sampleCount The number of samples to extract
     * @return An array containing the requested samples, or fewer if there is insufficient data in the buffer
     */
    private fun extractAvailableAudioSamples(sampleCount: Int): ShortArray
    {
        val extractedSamples = ShortArray(minOf(sampleCount, audioSampleBuffer.size))

        for (i in extractedSamples.indices)
        {
            extractedSamples[i] = audioSampleBuffer.poll() ?: 0
        }

        return extractedSamples
    }

    /**
     * Calculates the number of audio samples needed for a given duration.
     *
     * @param durationMs Duration in milliseconds
     * @return The number of 16-bit samples required at a 12kHz sample rate
     */
    private fun calculateRequiredSampleCount(durationMs: Long): Int
    {
        return ((durationMs / 1000.0) * WSPR_REQUIRED_SAMPLE_RATE).toInt()
    }

    /**
     * Calculates the duration of audio currently in the buffer.
     *
     * @return Duration in milliseconds of buffered audio
     */
    private fun calculateBufferDurationMilliseconds(): Long
    {
        val sampleCount = audioSampleBuffer.size
        return (sampleCount * 1000L) / WSPR_REQUIRED_SAMPLE_RATE
    }

    /**
     * Calculate the current buffer utilization as a percentage.
     *
     * @return Percentage (0-100) of maximum buffer capacity currently used
     */
    private fun calculateBufferUtilizationPercentage(): Int
    {
        val currentSize = audioSampleBuffer.size
        val maximumSize = bufferConfiguration.maximumBufferSamples
        return ((currentSize.toDouble() / maximumSize.toDouble()) * 100.0).toInt()
    }

    /**
     * Logs current buffer and performance statistics for monitoring.
     */
    private fun logBufferStatistics()
    {
        val bufferDurationMs = calculateBufferDurationMilliseconds()
        val bufferUtilization = calculateBufferUtilizationPercentage()
        val stats = performanceStatistics.getStatisticsSummary()

        Timber.d("SignalBridge Audio Source Stats: ${bufferDurationMs}ms buffered (${bufferUtilization}%), ${stats}")
    }

    fun ShortArray.rmsPercent(): String
    {
        if (isEmpty()) return "empty"
        val rms = kotlin.math.sqrt(map { (it.toFloat() / Short.MAX_VALUE).let { n -> n * n } }.average())
        return "%.3f%%".format(rms * 100)
    }

}