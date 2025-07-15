package org.operatorfoundation.signalbridge.models

import org.operatorfoundation.audiocoder.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE

/**
 * Configuration parameters for audio buffering behavior.
 * Controls memory usage and performance characteristics of the audio source.
 */
data class AudioBufferConfiguration(
    /** Maximum number of audio samples to buffer before removing old data */
    val maximumBufferSamples: Int,

    /** Target buffer size for optimal operation (samples) */
    val targetBufferSamples: Int,

    /** Minimum buffer size before warning about underrun (samples) */
    val minimumBufferSamples: Int
)
{
    companion object
    {
        /**
         * Creates a default configuration suitable for most WSPR operations.
         *
         * Buffer sizes are calculated to provide:
         *  - 5 minute maximum buffering (to prevent memory issues)
         *  - 3 minute target buffering (for WSPR timing + wiggle room)
         *  - 30 seconds minimum buffering (to prevent underruns)
         */
        fun createDefault(): AudioBufferConfiguration
        {
            val sampleRate = WSPR_REQUIRED_SAMPLE_RATE

            return AudioBufferConfiguration(
                maximumBufferSamples = sampleRate * 300, // 5 minutes
                targetBufferSamples = sampleRate * 180, // 3 minutes
                minimumBufferSamples = sampleRate * 30 // 30 seconds
            )
        }

        /**
         * Creates a configuration optimized for low-memory environments.
         * Reduce buffer size while keeping within the bounds of functional operation.
         */
        fun createLowMemoryConfiguration(): AudioBufferConfiguration
        {
            val sampleRate = WSPR_REQUIRED_SAMPLE_RATE

            return AudioBufferConfiguration(
                maximumBufferSamples = sampleRate * 150, // 2.5 minutes
                targetBufferSamples = sampleRate * 120, // 2 minutes
                minimumBufferSamples = sampleRate * 15 // 15 seconds
            )
        }

        /**
         * Creates a configuration for high-performance environments.
         */
        fun createHighPerformanceConfiguration(): AudioBufferConfiguration
        {
            val sampleRate = WSPR_REQUIRED_SAMPLE_RATE

            return AudioBufferConfiguration(
                maximumBufferSamples = sampleRate * 600, // 10 minutes
                targetBufferSamples = sampleRate * 300,  // 5 minutes
                minimumBufferSamples = sampleRate * 60   // 1 minute
            )
        }
    }
}
