# SignalBridge Audio Library for Android

A Kotlin library for USB audio input on Android devices, built with coroutines and Flow for real-time audio processing.

## Features

- **USB Audio Device Discovery**: Automatic detection and enumeration of USB audio devices
- **Permission Management**: Handling of USB device permissions with system dialogs
- **Real-time Audio Input**: Stream audio data using Kotlin Flow with minimal latency
- **Audio Level Monitoring**: Real-time RMS, peak, and average level calculations
- **Audio Playback**: Optional playback through phone speakers for monitoring
- **Comprehensive Error Handling**: Detailed error reporting with custom exception hierarchy

## Requirements

- **Minimum SDK**: API 26 (Android 8.0)
- **Permissions**: `android.permission.RECORD_AUDIO`
- **Hardware**: USB host mode support, USB audio device

## Installation (Pre-release)

⚠️ **This is a pre-release version** - APIs may change during active development.

### Step 1: Add JitPack Repository

In your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 2: Add Dependency

Latest development version:

```kotlin
dependencies {
    implementation("com.github.OperatorFoundation.SignalBridgeDemo:SignalBridge:main-SNAPSHOT")
}
```

## Quick Start

### 1. Add Permissions

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.usb.host" android:required="true" />
```

### 2. Initialize the Library

```kotlin
val audioManager = UsbAudioManager.create(context)
```

### 3. Discover USB Audio Devices

```kotlin
audioManager.discoverDevices().collect { devices ->
    devices.forEach { device ->
        println("Found: ${device.displayName}")
    }
}
```

### 4. Connect to a Device

```kotlin
val result = audioManager.connectToDevice(device)
if (result.isSuccess) {
    val connection = result.getOrNull()!!
    // Device is connected and ready
}
```

### 5. Record Audio

```kotlin
connection.startRecording().collect { audioData ->
    // Process audio samples
    val samples = audioData.samples // ShortArray of 16-bit audio
    val timestamp = audioData.timestamp
    // Your audio processing here
}
```

## API Reference

### UsbAudioManager

Main interface for USB audio operations.

```kotlin
interface UsbAudioManager {
    fun discoverDevices(): Flow<List<UsbAudioDevice>>
    suspend fun connectToDevice(device: UsbAudioDevice): Result<UsbAudioConnection>
    fun getConnectionStatus(): Flow<ConnectionStatus>
    suspend fun cleanup()
    
    companion object {
        fun create(context: Context): UsbAudioManager
    }
}
```

### UsbAudioConnection

Manages audio recording from a connected USB device.

```kotlin
interface UsbAudioConnection {
    val device: UsbAudioDevice
    fun startRecording(): Flow<AudioData>
    suspend fun stopRecording()
    fun getAudioLevel(): Flow<AudioLevelInfo>
    fun getRecordingState(): Flow<RecordingState>
    suspend fun setPlaybackEnabled(enabled: Boolean)
    fun getPlaybackEnabled(): Flow<Boolean>
    suspend fun disconnect()
}
```

### Data Models

#### AudioData
```kotlin
data class AudioData(
    val samples: ShortArray,      // 16-bit audio samples
    val timestamp: Long,          // System timestamp (ms)
    val sampleRate: Int,          // 48000 Hz
    val channelCount: Int,        // 1 (mono)
    val sequenceNumber: Long      // Buffer sequence number
)
```

#### UsbAudioDevice
```kotlin
data class UsbAudioDevice(
    val deviceId: Int,
    val productName: String,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val capabilities: AudioCapabilities
) {
    val displayName: String       // Formatted device name
    val uniqueId: String          // Unique device identifier
}
```

#### AudioLevelInfo
```kotlin
data class AudioLevelInfo(
    val currentLevel: Float,      // Current RMS level (0.0-1.0)
    val peakLevel: Float,         // Peak level with hold (0.0-1.0)
    val averageLevel: Float,      // Rolling average (0.0-1.0)
    val timestamp: Long
) {
    fun isClipping(threshold: Float = 0.95f): Boolean
    fun isSilent(threshold: Float = 0.01f): Boolean
}
```

## Advanced Usage

### Audio Processing Pipeline

```kotlin
class AudioProcessor {
    fun processAudio(audioData: AudioData) {
        val samples = audioData.samples
        
        // Apply your custom processing
        val processedSamples = applyFilter(samples)
        
        // Analyze audio content
        val spectrum = performFFT(processedSamples)
        
        // Extract features
        val features = extractAudioFeatures(spectrum)
    }
}
```

### Multiple Device Management

```kotlin
class MultiDeviceManager {
    private val connections = mutableMapOf<Int, UsbAudioConnection>()
    
    suspend fun connectAll(devices: List<UsbAudioDevice>) {
        devices.forEach { device ->
            audioManager.connectToDevice(device).getOrNull()?.let { connection ->
                connections[device.deviceId] = connection
            }
        }
    }
}
```

### Error Handling

```kotlin
try {
    val result = audioManager.connectToDevice(device)
    // Handle success
} catch (e: UsbAudioException) {
    when (e) {
        is DeviceNotFoundException -> handleDeviceNotFound()
        is PermissionDeniedException -> handlePermissionDenied()
        is AudioRecordInitializationException -> handleAudioFailure()
        else -> handleGenericError(e)
    }
}
```

## Audio Configuration

The library uses these audio settings:

- **Sample Rate**: 48 kHz
- **Bit Depth**: 16-bit
- **Channels**: Mono (1 channel)
- **Format**: PCM signed integers

## Performance

- **Latency**: ~20-50ms depending on device
- **CPU Usage**: Optimized for real-time processing
- **Memory**: Efficient buffer management with reuse
- **Threading**: Background audio processing with coroutines

## Device Compatibility

### Tested Devices (This testing is in-process and not yet complete)
- USB-C audio adapters **Not tested**
- Analog audio dongles **Not tested**
- Development boards (Teensy, etc.) - **Complete**

### Known Limitations
- Requires USB Audio Class (UAC) compliance
- Some devices may route audio output incorrectly
- Buffer sizes may need adjustment for specific devices

## Troubleshooting

### Common Issues

**Device not detected**
- Verify USB host mode support
- Check USB cable supports data transfer
- Ensure device is UAC-compliant

**Permission denied**
- Grant permission when system dialog appears
- Check app has RECORD_AUDIO permission

**No audio data**
- Verify device is actually generating audio
- Check audio levels are above noise floor
- Test with known working audio source

**High latency**
- Reduce buffer sizes (trade-off with stability)
- Close other audio applications
- Use high-priority audio processing

## Contributing

We are a small team, contributions are always appreciated, but responses may take us some time!

## License

MIT License - see LICENSE file for details.

## Changelog

### v1.0.0
- Initial release
- USB audio device discovery
- Real-time audio recording
- Audio level monitoring
- Permission management
- Error handling
