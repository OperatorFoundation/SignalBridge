package org.operatorfoundation.signalbridge.internal

import kotlinx.coroutines.delay
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber

/**
 * Mock implementation of USB permission management
 * This simulates permission handling for testing without real USB operations
 */
internal class MockUsbPermissionManager
{
    // Track permission states for mock devices
    private val grantedPermissions = mutableSetOf<Int>()

    /**
     * Simulates checking if permission is granted for a device
     */
    fun hasPermission(device: UsbAudioDevice): Boolean
    {
        val hasPermission = device.deviceId in grantedPermissions
        Timber.d("Permission check for device ${device.displayName}: $hasPermission")
        return hasPermission
    }

    /**
     * Simulates requesting permission for a device
     * This will auto grant after a delay to simulate user interaction
     */
    suspend fun requestPermission(device: UsbAudioDevice): Boolean
    {
        Timber.d("Requesting permission for device: ${device.displayName}")

        // Check if already granted
        if (hasPermission(device))
        {
            Timber.d("Permission already granted for device: ${device.displayName}")
            return true
        }

        // Simulate user interaction delay
        delay(1500)

        // For now, auto grant permission (90% success rate for testing)
        val granted = (1..10).random() <= 9

        if (granted)
        {
            grantedPermissions.add(device.deviceId)
            Timber.d("Permission granted for device: ${device.displayName}")
        }
        else
        {
            Timber.d("Permission denied for device: ${device.displayName}")
        }

        return granted
    }

    /**
     * Simulates bulk permission requests
     */
    suspend fun requestPermissions(devices: List<UsbAudioDevice>): Map<UsbAudioDevice, Boolean>
    {
        val results = mutableMapOf<UsbAudioDevice, Boolean>()

        for (device in devices)
        {
            val granted = requestPermission(device)
            results[device] = granted
        }

        return results
    }

    /**
     * Gets all devices with granted permissions
     */
    fun getPermittedDevices(allDevices: List<UsbAudioDevice>): List<UsbAudioDevice>
    {
        return allDevices.filter { hasPermission(it) }
    }

    /**
     * Gets all devices without permission
     */
    fun getUnpermittedDevices(allDevices: List<UsbAudioDevice>): List<UsbAudioDevice>
    {
        return allDevices.filter { !hasPermission(it) }
    }

    /**
     * Revokes permission for a device (for testing)
     */
    fun revokePermission(device: UsbAudioDevice)
    {
        grantedPermissions.remove(device.deviceId)
        Timber.d("Permission revoked for device: ${device.displayName}")
    }

    /**
     * Revokes all permissions (for testing)
     */
    fun revokeAllPermissions()
    {
        grantedPermissions.clear()
        Timber.d("All permission revoked")
    }

    fun cleanup()
    {
        Timber.d("Mock permission manager cleanup")
    }

}