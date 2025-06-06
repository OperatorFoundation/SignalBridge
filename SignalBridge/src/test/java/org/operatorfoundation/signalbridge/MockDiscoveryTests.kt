package org.operatorfoundation.signalbridge

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

import org.operatorfoundation.signalbridge.internal.MockUsbDeviceDiscovery

class MockDiscoveryTests
{
    @Test
    fun `mock device by id`() {
        // Given
        val discovery = MockUsbDeviceDiscovery()

        // When
        val device = discovery.getMockDeviceById(1)

        // Then
        assertNotNull(device)
        assertEquals(1, device.deviceId)
        assertEquals("USB Audio Adapter", device.productName)
    }

    @Test
    fun `mock device not found`() {
        // Given
        val discovery = MockUsbDeviceDiscovery()

        // When
        val device = discovery.getMockDeviceById(999)

        // Then
        assertNull(device)
    }

    @Test
    fun `device connection check`() {
        // Given
        val discovery = MockUsbDeviceDiscovery()

        // When & Then
        assertTrue(discovery.isDeviceConnected(1))
        assertTrue(discovery.isDeviceConnected(2))
        assertFalse(discovery.isDeviceConnected(999))
    }
}