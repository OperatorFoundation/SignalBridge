package org.operatorfoundation.signalbridge.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import org.operatorfoundation.signalbridge.models.AudioCapabilities
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber

internal class UsbDeviceDiscovery(private val context: Context, private val usbManager: UsbManager)
{
    companion object
    {
        // USB Audio class codes
        private const val USB_CLASS_AUDIO = 1
        private const val USB_SUBCLASS_AUDIOCONTROL = 1
        private const val USB_SUBCLASS_AUDIOSTREAMING = 2

        // Known USB audio device vendors (TODO: expand based on testing)
        private val KNOWN_AUDIO_VENDORS = setOf(
            0x046D, // Logitech
            0x0B05, // ASUS
            0x1B3F, // Generalplus Technology Inc.
            0x08BB, // Texas Instruments
            0x1397, // BEHRINGER International GmbH
            0x0D8C, // C-Media Electronics Inc.
            0x0582, // Roland Corporation
            0x0944, // KORG, Inc.
            0x0763, // M-Audio
            0x1235, // Focusrite-Novation
            0x0499, // Yamaha Corporation
        )

        // Known high quality audio vendord (TODO: update based on testing)
        private val professionalVendors = setOf(
            0x1397, // BEHRINGER
            0x0582, // Roland
            0x0944, // KORG
            0x0763, // M-Audio
            0x1235, // Focusrite-Novation
            0x0499, // Yamaha
        )

        // Audio related keywords for product name matching
        private val AUDIO_KEYWORDS = listOf(
            "audio", "sound", "microphone", "mic", "headset",
            "speaker", "dac", "interface", "mixer"
        )
    }

    /**
     * Discover USB audio devices with live monitoring
     */
    fun discoverAudioDevices(): Flow<List<UsbAudioDevice>> = callbackFlow {
        Timber.d("Starting USB audio device discovery")

        // Create broadcast receiver for USB events
        val usbReceiver = object : BroadcastReceiver()
        {
            override fun onReceive(context: Context?, intent: Intent)
            {
                when (intent.action)
                {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        device?.let {
                            Timber.d("USB device attached: ${it.productName} (${it.vendorId}:${it.productId}")
                            trySend(getCurrentAudioDevices())
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    {
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        device?.let {
                            Timber.d("USB device detached: ${it.productName} (${it.vendorId}:${it.productId})")
                            trySend(getCurrentAudioDevices())
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)

        // Send initial device list
        trySend(getCurrentAudioDevices())

        // Cleanup when flow is canceled
        awaitClose {
            Timber.d("Stopping USB audio device discovery")

            try
            {
                context.unregisterReceiver(usbReceiver)
            }
            catch (e: IllegalArgumentException)
            {
                Timber.d("USB Receiver was already unregistered")
            }
        }
    }.conflate()

    /**
     * Gets the current list of connected USB devices
     */
    private fun getCurrentAudioDevices(): List<UsbAudioDevice>
    {
        val deviceList = usbManager.deviceList
        Timber.d("Scanning ${deviceList.size} USB devices for audio capabilities")

        val audioDevices = deviceList.values.mapNotNull { usbDevice ->
            analyzeDevice(usbDevice)?.let { createUsbAudioDevice(usbDevice, it) }
        }

        Timber.i("Found ${audioDevices.size} USB audio devices")
        audioDevices.forEach { device ->
            Timber.d("  - ${device.displayName} (${device.vendorId}:${device.productId})")
        }

        return audioDevices
    }

    /**
     * Analyzes an USB device to determine if it's an audio device
     * Returns audio capabilities if it's an audio device, null if it's not
     */
    private fun analyzeDevice(device: UsbDevice): AudioCapabilities?
    {
        Timber.d("Analyzing device: ${device.productName} (${device.vendorId} ${device.productId})")

        // Method 1: Check USB interface classes
        val hasAudioInterface = checkForAudioInterfaces(device)
        if (hasAudioInterface)
        {
            Timber.d("Device has USB audio interfaces")
            return determineCapabilitiesFromInterfaces(device)
        }

        // Method 2: Check known audio device vendors
        if (device.vendorId in KNOWN_AUDIO_VENDORS)
        {
            Timber.d("Device is from known audio vendor: 0x${device.vendorId.toString(16)}")
            return AudioCapabilities.createDefault().copy(
                maxLatencyMs = getExpectedLatencyForVendor(device.vendorId)
            )
        }

        // Method 3: Check product name for audio keywords
        val productName = device.productName?.lowercase() ?: ""
        val hasAudioKeyword = AUDIO_KEYWORDS.any { keyword ->
            productName.contains(keyword)
        }

        if (hasAudioKeyword)
        {
            Timber.d("Device has audio-related name: ${device.productName}")
            return AudioCapabilities.createDefault()
        }

        // Method 4: Check for specific device class (if available)
        if (device.deviceClass == USB_CLASS_AUDIO) {
            Timber.d("Device has audio device class")
            return AudioCapabilities.createDefault()
        }

        Timber.d("Device does not appear to be an audio device")
        return null
    }

    /**
     * Checks if device has USB audio interfaces
     */
    private fun checkForAudioInterfaces(device: UsbDevice): Boolean
    {
        for (i in 0 until device.interfaceCount)
        {
            val usbInterface = device.getInterface(i)
            if (isAudioInterface(usbInterface))
            {
                return true
            }
        }

        return false
    }

    /**
     * Determines if a USB interface is an audio interface
     */
    private fun isAudioInterface(usbInterface: UsbInterface): Boolean
    {
        return usbInterface.interfaceClass == USB_CLASS_AUDIO &&
                (usbInterface.interfaceSubclass == USB_SUBCLASS_AUDIOCONTROL ||
                        usbInterface.interfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING)
    }

    /**
     * Determines audio capabilities from USB interfaces.
     * TODO: This is a simplified implementation.
     */
    private fun determineCapabilitiesFromInterfaces(device: UsbDevice): AudioCapabilities
    {
        // TODO: Parse actual USB audio descriptors

        val isHighQualityDevice = isHighQualityAudioDevice(device)
        val defaultSampleRates = listOf(12000, 48000, 44100)
        val highQualitySampleRates = defaultSampleRates + listOf(96000, 192000)
        val defaultChannelCounts = listOf(1, 2)
        val highQualityChannelCounts = defaultChannelCounts + listOf(4, 8)
        val defaultBitDepths = listOf(16)
        val highQualityBitDepths = defaultBitDepths + listOf(24, 32)
        val defaultMaxLatency = 50
        val highQualityMaxLatency = 20
        val supportsOutput = hasAudioOutputInterface(device)
        val supportsInput = true

        if (isHighQualityDevice)
        {
            return AudioCapabilities(highQualitySampleRates, highQualityChannelCounts, highQualityBitDepths, highQualityMaxLatency, supportsInput, supportsOutput)
        }
        else
        {
            return AudioCapabilities(defaultSampleRates, defaultChannelCounts, defaultBitDepths, defaultMaxLatency, supportsInput, supportsOutput)
        }
    }

    /**
     * Checks if the device appears to be a high-quality audio interface
     */
    private fun isHighQualityAudioDevice(device: UsbDevice): Boolean
    {
        return device.vendorId in professionalVendors ||
                device.productName?.contains("professional", ignoreCase = true) == true ||
                device.productName?.contains("studio", ignoreCase = true) == true
    }

    /**
     * Checks if device has audio output capabilities
     */
    private fun hasAudioOutputInterface(device: UsbDevice): Boolean
    {
        // Stub, for now we'll assume most USB audio devices support output
        // TODO: This requires more detailed USB descriptor analysis for accuracy
        return true
    }

    /**
     * Determines the most likely sample rates for this USB audio device.
     * Uses heuristics based on device characteristics and system preferences.
     */
    fun detectLikelySampleRates(device: UsbDevice): List<Int>
    {
        val systemPreferred = getSystemPreferredSampleRate()

        // Base our guess on device characteristics
        val likelyRates = when {
            device.vendorId in professionalVendors -> {
                listOf(systemPreferred, 48000, 96000, 44100, 32000, 22050)
            }

            // Consumer devices usually stick to standard rates
            device.vendorId in KNOWN_AUDIO_VENDORS -> {
                listOf(systemPreferred, 48000, 44100, 32000)
            }

            // Unknown devices - test common rates, system preferred first
            else -> {
                listOf(systemPreferred, 48000, 44100, 32000, 22050)
            }
        }.distinct()

        Timber.d("Likely sample rates for ${device.productName}: $likelyRates")
        return likelyRates
    }

    /**
     * Gets the system's preferred sample rate for audio output.
     */
    private fun getSystemPreferredSampleRate(): Int
    {
        return try
        {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val rate = sampleRateStr?.toInt() ?: 48000

            Timber.d("System preferred sample rate: ${rate}Hz")
            rate
        }
        catch (exception: Exception)
        {
            Timber.w(exception, "Could not get system sample rate, using 48kHz default")
            48000 // Most common default
        }
    }

    /**
     * Gets expected latency based on vendor
     */
    private fun getExpectedLatencyForVendor(vendorId: Int): Int
    {
        return when (vendorId) {
            in professionalVendors -> 20 // Professional audio
            else -> 50 // Consumer audio
        }
    }

    /**
     * Creates UsbAudioDevice from UsbDevice
     */
    private fun createUsbAudioDevice(usbDevice: UsbDevice, capabilities: AudioCapabilities): UsbAudioDevice
    {
        return UsbAudioDevice(
            deviceId = usbDevice.deviceId,
            productName = usbDevice.productName ?: "Unknown USB Audio Device",
            manufacturerName = usbDevice.manufacturerName,
            vendorId = usbDevice.vendorId,
            productId = usbDevice.productId,
            capabilities = capabilities
        )
    }

    /**
     * Gets USB device by ID
     */
    fun getUsbDeviceById(deviceId: Int): UsbDevice?
    {
        return usbManager.deviceList.values.find { it.deviceId == deviceId }
    }

    /**
     * Cleanup resources
     */
    fun cleanup()
    {
        Timber.d("USB device discovery cleanup completed")
    }


}