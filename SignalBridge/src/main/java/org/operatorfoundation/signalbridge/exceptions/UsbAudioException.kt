package org.operatorfoundation.signalbridge.exceptions

abstract class UsbAudioException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class DeviceNotFoundException(
    val deviceId: Int,
    message: String = "USB audio device with ID $deviceId not found"
) : UsbAudioException(message)

class PermissionDeniedException(
    val deviceName: String,
    message: String = "Permission denied for USB device: $deviceName"
) : UsbAudioException(message)

class DeviceDisconnectedException(
    val deviceName: String,
    message: String = "USB audio device disconnected: $deviceName"
) : UsbAudioException(message)

class AudioRecordInitializationException(
    val audioSource: Int,
    val sampleRate: Int,
    message: String = "Failed to initialize AudioRecord with source $audioSource and sample rate $sampleRate",
    cause: Throwable? = null
) : UsbAudioException(message, cause)

class UnsupportedAudioConfigurationException(
    val requestedSampleRate: Int,
    val requestedChannelCount: Int,
    val requestedBitDepth: Int,
    message: String = "Unsupported audio configuration: ${requestedSampleRate}Hz, $requestedChannelCount channels, ${requestedBitDepth}bit"
) : UsbAudioException(message)

class AudioRecordingException(
    val recordingState: String,
    message: String,
    cause: Throwable? = null
) : UsbAudioException(message, cause)

class AudioRecordException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ConnectionTimeoutException(
    val timeoutMs: Long,
    message: String = "Connection attempt timed out after ${timeoutMs}ms"
) : UsbAudioException(message)

class RecordingAlreadyActiveException(
    message: String = "Audio recording is already active"
) : UsbAudioException(message)

class RecordingNotActiveException(
    message: String = "No active audio recording to stop"
) : UsbAudioException(message)