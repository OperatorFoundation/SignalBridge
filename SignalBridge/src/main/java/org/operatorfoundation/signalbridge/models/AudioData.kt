package org.operatorfoundation.signalbridge.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents audio data captured from a USB audio device.
 *
 * @property samples Raw audio samples as 16-bit signed integers
 * @property timestamp System timestamp when the audio was captured (in milliseconds)
 * @property sampleRate Sample rate of the audio data (typically 12000 Hz)
 * @property channelCount Number of audio channels (typically 1 for mono)
 * @property sequenceNumber Sequential number for this audio buffer (for tracking continuity)
 */
data class AudioData(
    val samples: ShortArray,
    val timestamp: Long,
    val sampleRate: Int,
    val channelCount: Int = MONO_CHANNEL_COUNT,
    val sequenceNumber: Long = 0L
)
{
    companion object
    {
        const val MONO_CHANNEL_COUNT = 1
        const val STEREO_CHANNEL_COUNT = 2
        const val DEFAULT_SAMPLE_RATE = 12000
        const val BITS_PER_SAMPLE = 16
    }

    /**
     * Duration of this audio buffer in milliseconds.
     */
    val durationMs: Double
        get() = (samples.size.toDouble() / sampleRate) * 1000.0

    /**
     * Number of samples in this buffer.
     */
    val sampleCount: Int
        get() = samples.size

    override fun equals(other: Any?): Boolean
    {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioData

        if (!samples.contentEquals(other.samples)) return false
        if (timestamp != other.timestamp) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (sequenceNumber != other.sequenceNumber) return false

        return true
    }

    override fun hashCode(): Int
    {
        var result = samples.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + sequenceNumber.hashCode()
        return result
    }
}
