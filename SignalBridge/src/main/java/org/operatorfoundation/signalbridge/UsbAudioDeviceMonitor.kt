package org.operatorfoundation.signalbridge

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.operatorfoundation.signalbridge.internal.UsbDeviceDiscovery
import org.operatorfoundation.signalbridge.models.UsbAudioDevice

/**
 * Lightweight monitor for USB audio device availability.
 *
 * Use this for checking device availability without creating connections.
 * Multiple callers can safely use this simultaneously - it does not acquire
 * exclusive access to USB audio devices.
 *
 * For actual audio recording/playback, use [UsbAudioManager] which handles
 * connections. Note: Only one [UsbAudioConnection] should be active at a time,
 * as USB audio devices typically support only one reader.
 *
 * Example usage:
 * ```kotlin
 * // One-shot check for input devices
 * val hasInputDevice = UsbAudioDeviceMonitor.isDeviceAvailable(
 *     context,
 *     requireInput = true
 * )
 *
 * // Get list of available devices
 * val devices = UsbAudioDeviceMonitor.getAvailableDevices(context)
 *
 * // Observe availability changes (for UI updates)
 * UsbAudioDeviceMonitor.observeAvailability(context, requireInput = true)
 *     .collect { available ->
 *         updateButtonVisibility(available)
 *     }
 * ```
 */
object UsbAudioDeviceMonitor
{
    /**
     * Observes USB audio device availability.
     *
     * Returns a Flow that emits `true` when matching devices are available,
     * `false` otherwise. The Flow automatically registers for USB attach/detach
     * events while collected and cleans up when collection stops.
     *
     * @param context Android context (application context recommended)
     * @param requireInput If true, only devices with input capability are considered
     * @param requireOutput If true, only devices with output capability are considered
     * @return Flow emitting availability state, distinct until changed
     */
    fun observeAvailability(
        context: Context,
        requireInput: Boolean = false,
        requireOutput: Boolean = false
    ): Flow<Boolean>
    {
        return observeDevices(context, requireInput, requireOutput)
            .map { devices -> devices.isNotEmpty() }
            .distinctUntilChanged()
    }

    /**
     * Observes available USB audio devices.
     *
     * Returns a Flow that emits the current list of matching devices whenever
     * USB devices are attached or detached. The Flow automatically registers
     * for USB events while collected and cleans up when collection stops.
     *
     * @param context Android context (application context recommended)
     * @param requireInput If true, only devices with input capability are included
     * @param requireOutput If true, only devices with output capability are included
     * @return Flow emitting list of available devices
     */
    fun observeDevices(
        context: Context,
        requireInput: Boolean = false,
        requireOutput: Boolean = false
    ): Flow<List<UsbAudioDevice>>
    {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val discovery = UsbDeviceDiscovery(appContext, usbManager)

        return discovery.discoverAudioDevices()
            .map { devices -> filterDevices(devices, requireInput, requireOutput) }
    }

    /**
     * Checks if any matching USB audio device is currently available.
     *
     * This is a one-shot check that returns immediately. For continuous
     * monitoring, use [observeAvailability] instead.
     *
     * @param context Android context
     * @param requireInput If true, only devices with input capability are considered
     * @param requireOutput If true, only devices with output capability are considered
     * @return true if at least one matching device is available
     */
    fun isDeviceAvailable(
        context: Context,
        requireInput: Boolean = false,
        requireOutput: Boolean = false
    ): Boolean
    {
        return getAvailableDevices(context, requireInput, requireOutput).isNotEmpty()
    }

    /**
     * Gets the current list of available USB audio devices.
     *
     * This is a one-shot check that returns immediately. For continuous
     * monitoring, use [observeDevices] instead.
     *
     * @param context Android context
     * @param requireInput If true, only devices with input capability are included
     * @param requireOutput If true, only devices with output capability are included
     * @return List of available devices matching the criteria
     */
    fun getAvailableDevices(
        context: Context,
        requireInput: Boolean = false,
        requireOutput: Boolean = false
    ): List<UsbAudioDevice>
    {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val discovery = UsbDeviceDiscovery(appContext, usbManager)

        val allDevices = discovery.getCurrentAudioDevices()
        return filterDevices(allDevices, requireInput, requireOutput)
    }

    /**
     * Filters devices based on capability requirements.
     *
     * @param devices List of devices to filter
     * @param requireInput If true, device must support audio input
     * @param requireOutput If true, device must support audio output
     * @return Filtered list containing only devices matching all requirements
     */
    private fun filterDevices(
        devices: List<UsbAudioDevice>,
        requireInput: Boolean,
        requireOutput: Boolean
    ): List<UsbAudioDevice>
    {
        return devices.filter { device ->
            val inputOk = !requireInput || device.capabilities.supportsInput
            val outputOk = !requireOutput || device.capabilities.supportsOutput
            inputOk && outputOk
        }
    }
}