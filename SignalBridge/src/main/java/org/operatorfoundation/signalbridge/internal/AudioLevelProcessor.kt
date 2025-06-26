package org.operatorfoundation.signalbridge.internal

import android.window.TrustedPresentationThresholds
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * This class handles audio level calculations.
 *
 * Responsibilities:
 * - RMS (Root Mean Square) level calculation
 * - Peak level detection with hold time
 * - Rolling average level calculation
 * - Audio level normalization
 */
internal class AudioLevelProcessor
{
    companion object
    {
        // Peak hold time - how long to maintain peak level display
        private const val PEAK_HOLD_TIME_MS = 1000L

        // Rolling average window size - number of samples to include in average
        private const val AVERAGE_WINDOW_SIZE = 4096

        // Minimum level threshold for noise gate (optional)
        private const val NOISE_GATE_THRESHOLD = 0.001f
    }

    // State for peak level tracking
    private var peakLevel = 0f
    private var peakTimestamp = 0L

    // Rolling window for average calculation
    private val recentSamples = mutableListOf<Float>()

    // Statistics for debugging/monitoring
    private var totalSamplesProcessed = 0L
    private var lastResetTimestamp = System.currentTimeMillis()

    /**
     * Processes audio samples and returns comprehensive level information.
     * This is the main entry point for all audio level calculations.
     *
     * @param samples Raw audio samples from AudioRecord as ShortArray
     * @param timestamp Timestamp when samples were captured as Long
     * @return Complete audio level information as AudioLevelInfo
     */
    fun processAudioLevel(samples: ShortArray, timestamp: Long): AudioLevelInfo
    {
        if (samples.isEmpty())
        {
            Timber.v("Empty samples array received.")
            return AudioLevelInfo(0f, 0f, 0f, timestamp)
        }

        // Calculate current RMS level from samples
        val currentLevel = calculateRmsLevel(samples)

        // Update peak level with hold time logic
        updatePeakLevel(currentLevel, timestamp)

        // Calculate the rolling average level
        val averageLevel = updateAverageLevel(samples)

        // Update processing statistics
        totalSamplesProcessed += samples.size

        // Create AudioLevelInfo object
        val audioLevelInfo = AudioLevelInfo(
            currentLevel = currentLevel,
            peakLevel = peakLevel,
            averageLevel = averageLevel,
            timestamp = timestamp
        )

        // Log occasionally for debugging
        if (totalSamplesProcessed % 100000L == 0L)
        {
            Timber.v(audioLevelInfo.toString())
        }

        return audioLevelInfo
    }

    /**
     * Calculate RMS (Root Mean Square) level from audio samples.
     * RMS provides a good representation of perceived loudness.
     *
     * @param samples: Raw 16-bit audio samples
     * @return Normalized RMS level between 0.0 and 1.0
     */
    private fun calculateRmsLevel(samples: ShortArray): Float
    {
        if (samples.isEmpty()) return 0f

        // Convert samples to normalized floating point values (-1.0 to 1.0)
        val normalizedSamples = samples.map { sample ->
            sample.toFloat() / Short.MAX_VALUE
        }

        // Calculate sum of squares
        val sumSquares = normalizedSamples.map { sample ->
            sample * sample
        }.sum()

        // Calculate RMS (square root of mean of squares)
        val rmsLevel = sqrt(sumSquares / samples.size)

        // Apply noise gate and ensure bounds
        val gatedLevel = if (rmsLevel < NOISE_GATE_THRESHOLD) 0f else rmsLevel

        return gatedLevel.coerceIn(0f, 1f)
    }

    /**
     * Updates peak level with hold time logic.
     * Peak level is held for a specified duration to provide stable display.
     *
     * @param currentLevel Current audio level
     * @param timestamp Current timestamp
     */
    private fun updatePeakLevel(currentLevel: Float, timestamp: Long)
    {
        val timeSinceLastPeak = timestamp - peakTimestamp

        if (currentLevel > peakLevel)
        {
            // New peak detected
            peakLevel = currentLevel
            peakTimestamp = timestamp
//            Timber.v("New peak level detected %.3f", peakLevel)
        }
        else if (timeSinceLastPeak > PEAK_HOLD_TIME_MS)
        {
            // Peak hold time expired, decay towards to current level
            peakLevel = currentLevel
            peakTimestamp = timestamp
//            Timber.v("Peak level hold expired, reset to: %.3f", peakLevel)
        }

        // Ensure peak is never lower than the current level
        peakLevel = maxOf(peakLevel, currentLevel)
    }

    /**
     * Maintains a rolling average with a fixed window size,
     * to provide a smooth representation of recent audio activity.
     *
     * @param samples New audio samples to add to the average
     * @return Rolling average level between 0.0 and 1.0
     */
    private fun updateAverageLevel(samples: ShortArray): Float
    {
        // Convert samples to absolute normalized values for averaging
        val absoluteNormalizedSamples = samples.map { sample ->
            abs(sample.toFloat() / Short.MAX_VALUE)
        }

        // Add new samples to the rolling window
        recentSamples.addAll(absoluteNormalizedSamples)

        // Maintain fixed window size by removing oldest samples
        while (recentSamples.size > AVERAGE_WINDOW_SIZE)
        {
            recentSamples.removeAt(0)
        }

        // Calculate the average of all samples in the window
        val averageLevel = if (recentSamples.isNotEmpty())
        {
            recentSamples.average().toFloat()
        }
        else { 0f }

        return averageLevel.coerceIn(0f, 1f)
    }

    /**
     * Completely resets the level tracking state.
     */
    fun reset()
    {
        Timber.d("Resetting audio level processor state.")

        peakLevel = 0f
        peakTimestamp = 0L
        recentSamples.clear()
        totalSamplesProcessed = 0L
        lastResetTimestamp = System.currentTimeMillis()
    }

    /**
     * Gets the current statistics for debugging.
     */
    fun getProcessorStats(): AudioLevelProcessorStats
    {
        val currentTime = System.currentTimeMillis()
        val runtimeMs = currentTime - lastResetTimestamp

        return AudioLevelProcessorStats(
            totalSamplesProcessed = totalSamplesProcessed,
            runtimeMs = runtimeMs,
            averageWindowSize = recentSamples.size,
            maxAverageWindowSize = AVERAGE_WINDOW_SIZE,
            currentPeakLevel = peakLevel,
            peakHoldTimeMs = PEAK_HOLD_TIME_MS,
            noiseGateThreshold = NOISE_GATE_THRESHOLD
        )
    }

    /**
     * Validates that audio level calculation results are reasonable.
     * Used for internal consistency checking.
     *
     * @param AudioLevelInfo
     * @return True if the levels are reasonable, false if not
     */
    private fun validateLevelInfo(levelInfo: AudioLevelInfo): Boolean
    {
        val isValid = levelInfo.currentLevel in 0f..1f &&
                levelInfo.peakLevel in 0f..1f &&
                levelInfo.averageLevel in 0f..1f &&
                levelInfo.peakLevel >= levelInfo.currentLevel

        if (!isValid)
        {
            Timber.w("Invalid audio level info detected: $levelInfo")
        }

        return isValid
    }

}


/**
 * Statistics about the audio level processor for debugging and monitoring
 */
data class AudioLevelProcessorStats(
    val totalSamplesProcessed: Long,
    val runtimeMs: Long,
    val averageWindowSize: Int,
    val maxAverageWindowSize: Int,
    val currentPeakLevel: Float,
    val peakHoldTimeMs: Long,
    val noiseGateThreshold: Float
)
{
    val samplesPerSecond: Double
        get() = if (runtimeMs > 0) (totalSamplesProcessed * 1000.0) / runtimeMs else 0.0

    val averageWindowUsage: Float
        get() = averageWindowSize.toFloat() / maxAverageWindowSize

    override fun toString(): String {
        return "AudioLevelProcessorStats(" +
                "samples=$totalSamplesProcessed, " +
                "runtime=${runtimeMs}ms, " +
                "sps=%.1f, ".format(samplesPerSecond) +
                "window=$averageWindowUsage, " +
                "peak=%.3f)".format(currentPeakLevel)
    }
}