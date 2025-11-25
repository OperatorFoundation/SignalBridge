package org.operatorfoundation.signalbridge.internal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * USB Permission management
 * Handles USB device permissions with system dialogs
 */
internal class UsbPermissionManager(private val context: Context, private val usbManager: UsbManager)
{
    companion object
    {
        private const val ACTION_USB_PERMISSION = "org.operatorfoundation.signalbridge.usbaudio.USB_PERMISSION"
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 30000L // 30 seconds
    }

    /**
     * Checks if permission is granted for the specified USB device.
     */
    fun hasPermission(device: UsbDevice): Boolean
    {
        val hasPermission = usbManager.hasPermission(device)
        Timber.d("Permission check for device ${device.productName}: $hasPermission")
        return hasPermission
    }

    /**
     * Requests permission for the specified USB device.
     *
     * Shows system permission dialog and waits for a user response.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean
    {
        Timber.d("Requesting permission for device: ${device.productName}")

        // Check if we already have permission
        if (hasPermission(device))
        {
            Timber.d("Permission already granted for device: ${device.productName}")
            return true
        }

        // Use a timeout to prevent indefinite waiting
        val result = withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) {
            requestPermissionInternal(device)
        }

        return when (result)
        {
            null ->
                {
                Timber.w("Permission request timed out for device: ${device.productName}")
                false
            }

            else ->
            {
                Timber.d("Permission request completed for device: ${device.productName}, granted: $result")
                result
            }
        }
    }

    /**
     * Internal permission request.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun requestPermissionInternal(device: UsbDevice): Boolean = suspendCancellableCoroutine { continuation ->
        var receiver: BroadcastReceiver? = null

        try
        {
            // Create a broadcast receiver for permission responses
            receiver = object : BroadcastReceiver()
            {
                override fun onReceive(context: Context, intent: Intent)
                {
                    if (intent.action == ACTION_USB_PERMISSION)
                    {
                        val responseDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val permissionGranted = responseDevice?.let { usbManager.hasPermission(it) } ?: false

                        Timber.d("Permission response received for ${responseDevice?.productName}: granted? $permissionGranted")

                        // Verify that this is for the correct device
                        if (responseDevice?.deviceId == device.deviceId)
                        {
                            // Unregister the receiver
                            try
                            {
                                context.unregisterReceiver(this)
                            }
                            catch (error: IllegalArgumentException)
                            {
                                Timber.d("Tried to unregister a permission receiver that was already unregistered.")
                            }

                            // Resume coroutine with result
                            if (continuation.isActive)
                            {
                                continuation.resume(permissionGranted) {  handleCancellation(device, receiver) }
                            }
                        }
                        else
                        {
                            Timber.w("Received a permission response for a different device: ${responseDevice?.productName}, granted? $permissionGranted")
                        }
                    }
                }
            }

            // Register the receiver for permission responses
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            context.registerReceiver(receiver, filter)

            // Create pending intent for permission request
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            }
            else
            {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId, // Use the device ID as a unique request code
                Intent(ACTION_USB_PERMISSION),
                pendingIntentFlags
            )

            // Set up cancellation handling
            continuation.invokeOnCancellation {
                handleCancellation(device, receiver)
            }

            // Request permission from system
            Timber.d("Sending permission request to system for device: ${device.productName}")
            usbManager.requestPermission(device, permissionIntent)
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to request permission for device: ${device.productName}")

            // Clean up
            try
            {
                receiver?.let { context.unregisterReceiver(it) }
            }
            catch (error: java.lang.IllegalArgumentException)
            {
                Timber.d("Tried to unregister a permission receiver, on request failure, that was already unregistered.")
            }

            if (continuation.isActive)
            {
                continuation.resume(false) { handleCancellation(device, receiver) }
            }
        }
    }

    private fun handleCancellation(device: UsbDevice, receiver: BroadcastReceiver?)
    {
        Timber.d("Permission request cancelled for device: ${device.productName}")
        try
        {
            receiver?.let { context.unregisterReceiver(it) }
        }
        catch (error: java.lang.IllegalArgumentException)
        {
            Timber.d("Tried to unregister a permission receiver, on cancellation, that was already unregistered.")
        }
    }

    /**
     * Requests permissions for multiple devices.
     * Processes requests sequentially to avoid overwhelming the user.
     */
    suspend fun requestPermissions(devices: List<UsbDevice>): Map<UsbDevice, Boolean>
    {
        val results = mutableMapOf<UsbDevice, Boolean>()

        Timber.d("Requesting permissions for ${devices.size} devices.")

        for (device in devices)
        {
            val granted = requestPermission(device)
            results[device] = granted

            if (!granted)
            {
                Timber.w("Permission denied for device: ${device.productName}")
                // Continue with remaining devices
            }
            else
            {
                Timber.d("Permission granted for device: ${device.productName}")
            }
        }

        val grantedCount = results.values.count { it }
        Timber.i("Permission results: $grantedCount/${devices.size} devices granted.")

        return results
    }

    /**
     * Gets all USB devices that have permission granted.
     */
    fun getPermittedDevices(): List<UsbDevice>
    {
        return usbManager.deviceList.values.filter { device ->
            hasPermission(device)
        }
    }

    /**
     * Gets all USB devices that do not have permissions granted.
     */
    fun getUnpermittedDevices(): List<UsbDevice>
    {
        return usbManager.deviceList.values.filter { device ->
            !hasPermission(device)
        }
    }

    /**
     * Checks if any USB devices need permissions
     */
    fun hasUnpermittedDevices(): Boolean
    {
        return usbManager.deviceList.values.any { device ->
            !hasPermission(device)
        }
    }

    /**
     * Gets permission status for all devices
     */
    fun getPermissionStatus(): Map<UsbDevice, Boolean>
    {
        return usbManager.deviceList.values.associateWith { device ->
            hasPermission(device)
        }
    }

    /**
     * Cleanup resources
     */
    fun cleanup()
    {
        Timber.d("USB permission manager cleanup completed")
        // Note: Individual permission request receivers are cleaned up in their respective methods
    }

}