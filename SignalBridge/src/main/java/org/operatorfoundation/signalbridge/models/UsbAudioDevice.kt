package org.operatorfoundation.signalbridge.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UsbAudioDevice(
    val deviceId: Int,
    val productName: String,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val capabilities: AudioCapabilities
) : Parcelable
{
    val displayName: String
        get() = if (manufacturerName.isNullOrBlank())
        {
            productName
        }
        else
        {
            "$manufacturerName $productName"
        }

    val uniqueId: String
        get() = "${vendorId}_${productId}_$deviceId"

    companion object
    {
        /**
         * Creates a mock device for testing implementation
         */
        fun createMockDevice(
            deviceId: Int = 1,
            productName: String = "Mock USB Audio Device"
        ): UsbAudioDevice
        {
            return UsbAudioDevice(
                deviceId = deviceId,
                productName = productName,
                manufacturerName = "Mock Manufacturer",
                vendorId = 0x1234,
                productId = 0x5678,
                capabilities = AudioCapabilities.createDefault()
            )
        }
    }
}