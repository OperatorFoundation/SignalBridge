package org.operatorfoundation.signalbridge.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.operatorfoundation.signalbridge.models.AudioCapabilities
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber

/**
 * Mock implementation of USB device discovery
 * This simulates real device discovery behavior for testing and development
 */
internal class MockUsbDeviceDiscovery
{
    private val mockDevices = listOf(
        UsbAudioDevice(
            deviceId = 1,
            productName = "USB Audio Adapter",
            manufacturerName = "Generic",
            vendorId = 0x0D8C,
            productId = 0x0014,
            capabilities = AudioCapabilities.createDefault()
        ),
        UsbAudioDevice(
            deviceId = 2,
            productName = "Professional Audio Interface",
            manufacturerName = "BEHRINGER",
            vendorId = 0x1397,
            productId = 0x0507,
            capabilities = AudioCapabilities(
                supportedSampleRates = listOf(12000, 48000, 96000),
                supportedChannelCounts = listOf(1, 2),
                supportedBitDepths = listOf(16, 24),
                maxLatencyMs = 20,
                supportsInput = true,
                supportsOutput = true
            )
        )
    )

    /**
     * Simulates device discovery
     */
    fun discoverAudioDevices(): Flow<List<UsbAudioDevice>> = flow {
        Timber.d("Mock device discovery started")

        // Initial empty state
        emit(emptyList())
        delay(500) // Simulate discovery time

        // Emit first device
        emit(listOf(mockDevices[0]))
        delay(1000)

        // Emit all devices
        emit(mockDevices)
        delay(2000)

        // Simulate device disconnection
        emit(listOf(mockDevices[1]))

        Timber.d("Mock device discovery completed initial cycle")
    }

    /**
     * Gets as mock USB device by ID
     */
    fun getMockDeviceById(deviceId: Int): UsbAudioDevice?
    {
        return mockDevices.find { it.deviceId == deviceId }
    }

    /**
     * Simulates checking if a device is still connected
     */
    fun isDeviceConnected(deviceId: Int): Boolean
    {
        return mockDevices.any { it.deviceId == deviceId }
    }

    fun cleanup()
    {
        Timber.d("Mock device discovery cleanup")
    }
}