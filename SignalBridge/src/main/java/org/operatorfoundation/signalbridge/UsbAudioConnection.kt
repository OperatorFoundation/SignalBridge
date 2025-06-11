package org.operatorfoundation.signalbridge

import kotlinx.coroutines.flow.Flow
import org.operatorfoundation.signalbridge.models.AudioData
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.RecordingState
import org.operatorfoundation.signalbridge.models.UsbAudioDevice

/**
 * Represents an active connection to a USB audio device.
 *
 * This interface provides methods for recording audio from the connected USB device,
 * monitoring audio levels, and managing recording state.
 *
 * Example usage:
 * ```kotlin
 * connection.startRecording().collect { audioData ->
 *      // Process audio samples
 *      val samples = audioData.samples
 *      val timestamp = audioData.timestamp
 *      processAudioSamples(samples, timestamp)
 * }
 *
 * // Monitor audio levels
 * connection.getAudioLevel().collect { levelInfo ->
 *      updateUI(levelInfo.currentLevel, levelInfo.peakLevel)
 * }
 * ```
 */
interface UsbAudioConnection
{
    /**
     * The USB audio device associated with this connection.
     */
    val device: UsbAudioDevice

    /**
     * Starts recording audio from the USB device.
     *
     * @return Flow of AudioData containing raw audio samples and metadata.
     *      The flow will continue emitting until stopRecording() is called
     *      or an error occurs.
     * @throws IllegalStateException if already recording or device is disconnected
     */
    fun startRecording(): Flow<AudioData>

    /**
     * Stops audio recording.
     *
     * This will stop the audio recording and clean up audio resources.
     * The recording flow will complete normally.
     */
    suspend fun stopRecording()

    /**
     * Monitors real time audio levels.
     *
     * This provides audio level information that can be used for
     * VU meters, level indicators, or automatic gain control.
     *
     * @return Flow of AudioLevelInfo containing current peak audio levels
     */
    fun getAudioLevel(): Flow<AudioLevelInfo>

    /**
     * Monitors the current recording state.
     *
     * @return Flow that emits recording state changes
     */
    fun getRecordingState(): Flow<RecordingState>

    /**
     * Checks if the device is currently connected and available.
     *
     * @return true if device is connected and ready for operations, false if not
     */
    suspend fun isDeviceConnected(): Boolean

    /**
     * Disconnects from the USB audio device.
     *
     * This will stop any active recording, release all audio resources,
     * amd close the connection to the device.
     */
    suspend fun disconnect()
}