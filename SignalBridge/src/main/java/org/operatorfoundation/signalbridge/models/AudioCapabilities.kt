package org.operatorfoundation.signalbridge.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AudioCapabilities(
    val supportedSampleRates: List<Int>,
    val supportedChannelCounts: List<Int>,
    val supportedBitDepths: List<Int>,
    val maxLatencyMs: Int,
    val supportsInput: Boolean,
    val supportsOutput: Boolean
) : Parcelable
{
    companion object
    {
        fun createDefault(): AudioCapabilities = AudioCapabilities(
            supportedSampleRates = listOf(12000, 48000),
            supportedChannelCounts = listOf(1, 2),
            supportedBitDepths = listOf(16),
            maxLatencyMs = 50,
            supportsInput = true,
            supportsOutput = false
        )
    }

    fun supportsConfiguration(sampleRate: Int, channelCount: Int, bitDepth: Int): Boolean
    {
        return sampleRate in supportedSampleRates &&
                channelCount in supportedChannelCounts &&
                bitDepth in supportedBitDepths
    }

}