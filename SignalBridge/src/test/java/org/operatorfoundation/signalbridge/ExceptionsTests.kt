package org.operatorfoundation.signalbridge

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

import org.operatorfoundation.signalbridge.exceptions.*

class ExceptionsTests
{
    @Test
    fun `device not found exception`() {
        // When
        val exception = DeviceNotFoundException(123)

        // Then
        assertEquals(123, exception.deviceId)
        assertTrue(exception.message!!.contains("123"))
    }

    @Test
    fun `permission denied exception`() {
        // When
        val exception = PermissionDeniedException("Test Device")

        // Then
        assertEquals("Test Device", exception.deviceName)
        assertTrue(exception.message!!.contains("Test Device"))
    }

    @Test
    fun `audio configuration exception`() {
        // When
        val exception = UnsupportedAudioConfigurationException(96000, 8, 24)

        // Then
        assertEquals(96000, exception.requestedSampleRate)
        assertEquals(8, exception.requestedChannelCount)
        assertEquals(24, exception.requestedBitDepth)
        assertTrue(exception.message!!.contains("96000"))
    }
}