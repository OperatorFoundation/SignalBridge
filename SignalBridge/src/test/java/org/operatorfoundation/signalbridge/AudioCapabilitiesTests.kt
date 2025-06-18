package org.operatorfoundation.signalbridge

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

import org.operatorfoundation.signalbridge.models.AudioCapabilities


class AudioCapabilitiesTests
{
    @Test
    fun `default capabilities`() {
        // When
        val capabilities = AudioCapabilities.createDefault()

        // Then
        assertTrue(48000 in capabilities.supportedSampleRates)
        assertTrue(12000 in capabilities.supportedSampleRates)
        assertTrue(1 in capabilities.supportedChannelCounts)
        assertTrue(16 in capabilities.supportedBitDepths)
        assertTrue(capabilities.supportsInput)
        assertEquals(50, capabilities.maxLatencyMs)
    }

    @Test
    fun `supports configuration - valid`() {
        // Given
        val capabilities = AudioCapabilities.createDefault()

        // When & Then
        assertTrue(capabilities.supportsConfiguration(12000, 1, 16))
    }

    @Test
    fun `supports configuration - invalid sample rate`() {
        // Given
        val capabilities = AudioCapabilities.createDefault()

        // When & Then
        assertFalse(capabilities.supportsConfiguration(96000, 1, 16))
    }

    @Test
    fun `supports configuration - invalid channel count`() {
        // Given
        val capabilities = AudioCapabilities.createDefault()

        // When & Then
        assertFalse(capabilities.supportsConfiguration(48000, 8, 16))
    }
}