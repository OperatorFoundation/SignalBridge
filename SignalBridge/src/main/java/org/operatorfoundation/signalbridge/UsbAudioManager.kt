package org.operatorfoundation.signalbridge

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.operatorfoundation.signalbridge.internal.UsbAudioManagerImpl
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.UsbAudioDevice

/**
 * The main interface for USB audio operations.
 *
 * This is the primary entry point for the USB audio library. It provides methods
 * for discovering USB audio devices, managing connections, and monitoring connection status.
 *
 * Example usage:
 * ```kotlin
 * val audioManager = UsbAudioManager.create(context)
 *
 * // Discover available devices
 * audioManager.discoverDevices().collect { devices ->
 *      devices.forEach { device ->
 *          println("Found device: ${device.productName}")
 *      }
 * }
 *
 * // Connect to a device
 * val result = audioManager.connectToDevice(selectedDevice)
 * if (result.isSuccess)
 * {
 *      val connection = result.getOrNull()
 *      // Start recording audio
 * }
 *
 * ```
 */
interface UsbAudioManager
{
    /**
     * Discovers all connected USB audio devices.
     *
     * @return Flow that emits a list of available USB audio devices.
     *      The Flow will emit new lists when devices are connected/disconnected.
     */
    fun discoverDevices(): Flow<List<UsbAudioDevice>>

    /**
     * Attempts to connect to the specified audio device.
     *
     * This method will handle permission requests if necessary and establish
     * a connection to the device for audio operations.
     *
     * @param device The USB audio device to connect to
     * @return Result containing UsbAudioConnection on success or exception on failure
     */
    suspend fun connectToDevice(device: UsbAudioDevice): Result<UsbAudioConnection>

    /**
     * Monitors the current connection status.
     *
     * @return Flow that emits connection status updates
     */
    fun getConnectionStatus(): Flow<ConnectionStatus>

    /**
     * Releases all resources and cleans up the manager.
     * Call this when the manager is no longer needed.
     */
    suspend fun cleanup()

    companion object
    {
        /**
         * Creates a new instance of UsbAudioManager.
         *
         * @param context Android context, this should be the application context
         * @return UsbAudioManager instance
         */
        fun create(context: Context): UsbAudioManager
        {
            return UsbAudioManagerImpl(context.applicationContext)
        }
    }
}