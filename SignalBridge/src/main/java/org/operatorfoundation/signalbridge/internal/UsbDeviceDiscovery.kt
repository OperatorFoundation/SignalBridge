package org.operatorfoundation.signalbridge.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
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
        val usbReceiver = object : BroadcastReceiver() {
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

}