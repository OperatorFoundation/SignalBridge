package org.operatorfoundation.signalbridge

import kotlinx.coroutines.*
import org.operatorfoundation.audiocoder.common.AudioResampler
import org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
import org.operatorfoundation.signalbridge.exceptions.AudioRecordInitializationException
import org.operatorfoundation.signalbridge.exceptions.AudioRecordingException
import org.operatorfoundation.signalbridge.exceptions.DeviceNotFoundException
import org.operatorfoundation.signalbridge.internal.AudioSourcePerformanceStatistics
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Abstract base class for SignalBridge USB audio source implementations.
 *
 * Provides the complete audio buffering and collection pipeline shared by all
 * audio source implementations. Subclasses implement a specific audio source
 * interface (e.g. [org.operatorfoundation.audiocoder.wspr.WSPRAudioSource] or
 * [org.operatorfoundation.audiocoder.mfsk.MFSKAudioSource]) and inherit all
 * buffering, resampling, and lifecycle behaviour from this class.
 *
 * @param usbAudioConnection  Active USB audio connection from SignalBridge.
 * @param targetSampleRate    Sample rate in Hz that the audio source interface requires.
 *                            Incoming audio is resampled to this rate if necessary.
 * @param bufferConfiguration Buffer size and overflow behaviour.
 */
abstract class SignalBridgeAudioSource(
    protected val usbAudioConnection: UsbAudioConnection,
    protected val targetSampleRate: Int,
    protected val bufferConfiguration: AudioBufferConfiguration
)
{
    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private val audioSampleBuffer      = ConcurrentLinkedQueue<Short>()
    private var audioCollectionJob: Job? = null
    private var audioResampler: AudioResampler? = null
    private var lastChunkLogTime       = 0L

    /**
     * Current operational status. Updated as conditions change.
     * Exposed to subclasses so they can copy it with updated descriptions.
     */
    protected var currentStatus: AudioSourceStatus =
        AudioSourceStatus.createNonOperationalStatus("Not initialized")

    private val performanceStatistics = AudioSourcePerformanceStatistics()

    // -------------------------------------------------------------------------
    // Shared interface implementation
    // -------------------------------------------------------------------------

    open suspend fun initialize(): Result<Unit>
    {
        if (audioCollectionJob?.isActive == true) return Result.success(Unit)

        return try
        {
            Timber.d("${this::class.simpleName}: initializing")

            if (!usbAudioConnection.isDeviceConnected())
            {
                val errorMessage = "USB audio device is not connected"
                currentStatus = AudioSourceStatus.createNonOperationalStatus(errorMessage)
                return Result.failure(DeviceNotFoundException(-1, errorMessage))
            }

            audioSampleBuffer.clear()
            performanceStatistics.reset()

            startBackgroundAudioCollection()

            currentStatus = AudioSourceStatus.createNonOperationalStatus("USB audio streaming active")

            Timber.i("${this::class.simpleName}: initialized successfully")
            Result.success(Unit)
        }
        catch (exception: Exception)
        {
            val errorMessage = "Failed to initialize: ${exception.message}"
            currentStatus = AudioSourceStatus.createNonOperationalStatus(errorMessage)
            Timber.e(exception, errorMessage)
            Result.failure(
                AudioRecordInitializationException(0, targetSampleRate, cause = exception)
            )
        }
    }

    open suspend fun readAudioChunk(durationMs: Long): ShortArray
    {
        return try
        {
            val requiredSampleCount  = calculateRequiredSampleCount(durationMs)
            val availableSampleCount = audioSampleBuffer.size

            performanceStatistics.recordReadRequest(requiredSampleCount, availableSampleCount)

            val now = System.currentTimeMillis()
            if (now - lastChunkLogTime >= 5000)
            {
                Timber.d("CHUNK: requested=$requiredSampleCount, available=$availableSampleCount")
                lastChunkLogTime = now
            }

            if (availableSampleCount < requiredSampleCount)
            {
                val availableSamples = extractAvailableAudioSamples(availableSampleCount)
                performanceStatistics.recordPartialRead(availableSamples.size)
                return availableSamples
            }

            val requestedSamples = extractAvailableAudioSamples(requiredSampleCount)
            performanceStatistics.recordSuccessfulRead(requestedSamples.size)
            requestedSamples
        }
        catch (exception: Exception)
        {
            performanceStatistics.recordReadError()
            Timber.e(exception, "Error reading audio chunk for ${durationMs}ms")
            throw AudioRecordingException(
                "reading",
                "Failed to read audio chunk: ${exception.message}",
                exception
            )
        }
    }

    open suspend fun flushBuffer()
    {
        audioSampleBuffer.clear()
        Timber.d("${this::class.simpleName}: buffer flushed")
    }

    open suspend fun cleanup()
    {
        Timber.d("${this::class.simpleName}: cleaning up")

        try
        {
            audioCollectionJob?.cancel()
            audioCollectionJob?.join()
            audioCollectionJob = null

            audioSampleBuffer.clear()

            audioResampler?.reset()
            audioResampler = null

            currentStatus = AudioSourceStatus.createNonOperationalStatus("Cleaned up")

            Timber.i("${this::class.simpleName}: cleanup complete")
        }
        catch (exception: Exception)
        {
            Timber.w(exception, "${this::class.simpleName}: error during cleanup")
        }
    }

    open suspend fun getSourceStatus(): AudioSourceStatus
    {
        val bufferStatus = if (currentStatus.isOperational)
        {
            val bufferDurationMs         = calculateBufferDurationMilliseconds()
            val bufferUtilizationPercent = calculateBufferUtilizationPercentage()
            val resamplerInfo            = audioResampler?.let { " [Resampling]" } ?: ""
            "Streaming: ${bufferDurationMs}ms buffered (${bufferUtilizationPercent}% full)${resamplerInfo}"
        }
        else
        {
            currentStatus.statusDescription
        }

        return currentStatus.copy(
            statusDescription = bufferStatus,
            lastUpdated       = System.currentTimeMillis()
        )
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun startBackgroundAudioCollection()
    {
        audioCollectionJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try
            {
                Timber.d("${this@SignalBridgeAudioSource::class.simpleName}: starting background audio collection")

                usbAudioConnection.startRecording().collect { audioData ->

                    if (audioResampler == null && audioData.sampleRate != targetSampleRate)
                    {
                        audioResampler = AudioResampler(audioData.sampleRate, targetSampleRate)
                        Timber.i("${this@SignalBridgeAudioSource::class.simpleName}: resampling ${audioData.sampleRate}Hz -> ${targetSampleRate}Hz")
                    }

                    val processedSamples = if (audioResampler != null)
                        audioResampler!!.resample(audioData.samples)
                    else
                        audioData.samples

                    audioSampleBuffer.addAll(processedSamples.toList())
                    performanceStatistics.recordSamplesReceived(processedSamples.size)

                    maintainBufferSize()

                    if (performanceStatistics.shouldLogStatistics())
                        logBufferStatistics()
                }
            }
            catch (exception: Exception)
            {
                val errorMessage = "Background audio collection failed: ${exception.message}"
                currentStatus = AudioSourceStatus.createNonOperationalStatus(errorMessage)
                Timber.e(exception, errorMessage)
            }
        }
    }

    private fun maintainBufferSize()
    {
        val currentBufferSize = audioSampleBuffer.size
        val maximumBufferSize = bufferConfiguration.maximumBufferSamples

        if (currentBufferSize > maximumBufferSize)
        {
            val samplesToRemove = currentBufferSize - maximumBufferSize
            repeat(samplesToRemove) { audioSampleBuffer.poll() }
            performanceStatistics.recordBufferOverflow(samplesToRemove)
            Timber.v("${this::class.simpleName}: removed $samplesToRemove old samples")
        }
    }

    private fun extractAvailableAudioSamples(sampleCount: Int): ShortArray
    {
        val extractedSamples = ShortArray(minOf(sampleCount, audioSampleBuffer.size))
        for (i in extractedSamples.indices)
            extractedSamples[i] = audioSampleBuffer.poll() ?: 0
        return extractedSamples
    }

    private fun calculateRequiredSampleCount(durationMs: Long): Int =
        ((durationMs / 1000.0) * targetSampleRate).toInt()

    private fun calculateBufferDurationMilliseconds(): Long =
        (audioSampleBuffer.size * 1000L) / targetSampleRate

    private fun calculateBufferUtilizationPercentage(): Int =
        ((audioSampleBuffer.size.toDouble() / bufferConfiguration.maximumBufferSamples.toDouble()) * 100.0).toInt()

    private fun logBufferStatistics()
    {
        Timber.d("${this::class.simpleName}: ${calculateBufferDurationMilliseconds()}ms buffered " +
                "(${calculateBufferUtilizationPercentage()}%), ${performanceStatistics.getStatisticsSummary()}")
    }
}