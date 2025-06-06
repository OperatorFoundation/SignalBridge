package org.operatorfoundation.signalbridge

import org.junit.Test
import org.operatorfoundation.signalbridge.models.AudioCapabilities
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import kotlin.test.assertEquals
import kotlin.test.assertTrue


/**
 *
 */
class UsbAudioDeviceTests
{
    @Test
    fun `create device with all properties`()
    {
        // Given
        val capabilities = AudioCapabilities.createDefault()
        val productName = "Test Device"
        val manufacturerName = "Test Manufacturer"
        val deviceId = 123
        val vendorId = 0x1234
        val productId = 0x5678

        // When
        val device = UsbAudioDevice(
            deviceId = deviceId,
            productName = productName,
            manufacturerName = manufacturerName,
            vendorId = vendorId,
            productId = productId,
            capabilities = capabilities
        )

        // Then
        assertEquals(deviceId, device.deviceId)
        assertEquals(productName, device.productName)
        assertEquals(manufacturerName, device.manufacturerName)
        assertEquals(vendorId, device.vendorId)
        assertEquals(productId, device.productId)
        assertEquals(capabilities, device.capabilities)
    }

    @Test
    fun `displayName with manufacturer`()
    {
        // Given
        val deviceId = 1
        val productName = "Audio Device"
        val manufacturerName = "ACME Corp"
        val vendorId = 0x1234
        val productId = 0x5678
        val capabilities = AudioCapabilities.createDefault()
        val device = UsbAudioDevice(deviceId, productName, manufacturerName, vendorId, productId, capabilities)

        // When & Then
        assertEquals("$manufacturerName $productName", device.displayName)
    }

    @Test
    fun `displayName without manufacturer`()
    {
        // Given
        val deviceId = 1
        val productName = "Audio Device"
        val vendorId = 0x1234
        val productId = 0x5678
        val capabilities = AudioCapabilities.createDefault()
        val device = UsbAudioDevice(deviceId, productName, manufacturerName = null, vendorId, productId, capabilities)

        // When & Then
        assertEquals(productName, device.displayName)
    }

    @Test
    fun `uniqueId generation`()
    {
        // Given
        val deviceId = 123
        val productName = "Audio Device"
        val manufacturerName = "ACME Corp"
        val vendorId = 0x1234
        val productId = 0x5678
        val capabilities = AudioCapabilities.createDefault()
        val device = UsbAudioDevice(deviceId, productName, manufacturerName, vendorId, productId, capabilities)

        // When & Then
        assertEquals("4660_22136_123", device.uniqueId)
    }

    @Test
    fun `mock device creation`() {
        // When
        val mockDevice = UsbAudioDevice.createMockDevice()

        // Then
        assertEquals(1, mockDevice.deviceId)
        assertEquals("Mock USB Audio Device", mockDevice.productName)
        assertEquals("Mock Manufacturer", mockDevice.manufacturerName)
        assertTrue(mockDevice.capabilities.supportsInput)
    }
}