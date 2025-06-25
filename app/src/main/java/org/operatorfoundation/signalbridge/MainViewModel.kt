package org.operatorfoundation.signalbridge

import android.app.Application
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

import timber.log.Timber

import org.operatorfoundation.signalbridge.exceptions.UsbAudioException
import org.operatorfoundation.signalbridge.models.*
import org.operatorfoundation.audiocoder.CJarInterface
import org.operatorfoundation.audiocoder.WSPRBandplan
import org.operatorfoundation.audiocoder.WSPRProcessor
import org.operatorfoundation.audiocoder.WSPRFileManager



/**
 * ViewModel for the main demo activity.
 *
 * This ViewModel manages the state of USB audio device discovery, connection,
 * and recording operations. It serves as the business logic layer between
 * the UI and the USB Audio Library.
 */
class MainViewModel(application: Application) : AndroidViewModel(application)
{
    companion object
    {
        // UI formatting constants
        const val BUFFER_TIME_DECIMAL_PLACES = 1
        const val DEFAULT_CALLSIGN = "Q0QQQ"
        const val DEFAULT_GRID_SQUARE = "FN20"
        const val DEFAULT_POWER_DBM = "30"

        // UI spacing constants
        const val SECTION_PADDING_DP = 16
        const val ELEMENT_SPACING_DP = 12
        const val BUTTON_SPACING_DP = 8

        // Audio Processing Constants
        private const val LOG_INTERVAL_BUFFERS = 1000L          // Log every 1000 audio buffers
        private const val SIGNIFICANT_AUDIO_THRESHOLD = 0.5f    // 50% of max amplitude

        // UI Update Constants
        private const val BUFFER_STATUS_DECIMAL_PLACES = 1      // Show 1 decimal place for buffer time

        // Audio Statistics Constants
        private const val SAMPLE_RATE_HZ = 12000                // Expected sample rate
        private const val MAX_AMPLITUDE_16BIT = Short.MAX_VALUE.toFloat()
    }

    // USB Audio Library components
    private val audioManager: UsbAudioManager = UsbAudioManager.create(application)
    private var currentConnection: UsbAudioConnection? = null

    // UI State
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // WSPR
    val wsprProcessor = WSPRProcessor()

    init {
        // Start device discovery when ViewModel is created
        startDeviceDiscovery()
        observeConnectionStatus()
    }

    /**
     * Starts continuous discovery of USB audio devices.
     */
    private fun startDeviceDiscovery() {
        viewModelScope.launch {
            try {
                audioManager.discoverDevices().collect { devices ->
                    Timber.d("Discovered ${devices.size} USB audio devices")
                    _uiState.update { currentState ->
                        currentState.copy(
                            availableDevices = devices,
                            errorMessage = null // Clear any previous discovery errors
                        )
                    }
                }
            } catch (exception: Exception) {
                Timber.e(exception, "Error during device discovery")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Device discovery failed: ${exception.message}"
                    )
                }
            }
        }
    }

    /**
     * Observes connection status changes from the audio manager.
     */
    private fun observeConnectionStatus() {
        viewModelScope.launch {
            audioManager.getConnectionStatus().collect { status ->
                Timber.d("Connection status changed: $status")
                _uiState.update { currentState ->
                    currentState.copy(connectionStatus = status)
                }
            }
        }
    }

    /**
     * Connects to the specified USB audio device.
     *
     * @param device The device to connect to
     */
    fun connectToDevice(device: UsbAudioDevice)
    {
        viewModelScope.launch {
            try
            {
                Timber.d("Attempting to connect to device: ${device.displayName}")

                // If already connected to a device, disconnect first
                if (currentConnection != null)
                {
                    Timber.d("Disconnecting from current device before connecting to new device")
                    currentConnection?.disconnect()
                    currentConnection = null
                }

                // Clear any previous errors
                _uiState.update { it.copy(errorMessage = null) }

                val result = audioManager.connectToDevice(device)

                if (result.isSuccess)
                {
                    val connection = result.getOrNull()!!
                    currentConnection = connection

                    // Start observing playback state
                    observePlaybackState(currentConnection!!)

                    _uiState.update { currentState ->
                        currentState.copy(
                            connectedDevice = device,
                            errorMessage = null
                        )
                    }

                    // Start observing connection-specific states
                    observeRecordingState(connection)
                    observeAudioLevels(connection)

                    Timber.i("Successfully connected to device: ${device.displayName}")
                }
                else
                {
                    val exception = result.exceptionOrNull()
                    val errorMessage = when (exception) {
                        is UsbAudioException -> exception.message ?: "Unknown USB audio error"
                        else -> "Connection failed: ${exception?.message}"
                    }

                    Timber.e(exception, "Failed to connect to device: ${device.displayName}")
                    _uiState.update { currentState ->
                        currentState.copy(errorMessage = errorMessage)
                    }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Unexpected error during device connection")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Unexpected error: ${exception.message}"
                    )
                }
            }
        }
    }

    /**
     * Observes playback state changes from the connection
     */
    private fun observePlaybackState(connection: UsbAudioConnection)
    {
        viewModelScope.launch {
            try
            {
                connection.getPlaybackEnabled().collect { enabled ->
                    _uiState.update { currentState ->
                        currentState.copy(isPlaybackEnabled = enabled)
                    }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error observing playback state")
            }
        }
    }

    /**
     * Disconnects from the currently connected device.
     */
    fun disconnect()
    {
        viewModelScope.launch {
            try {
                Timber.d("Disconnecting from current device")

                currentConnection?.disconnect()
                currentConnection = null

                _uiState.update { currentState ->
                    currentState.copy(
                        connectedDevice = null,
                        recordingState = RecordingState.Stopped,
                        audioLevel = null,
                        errorMessage = null
                    )
                }

                Timber.i("Successfully disconnected from device")

            } catch (exception: Exception) {
                Timber.e(exception, "Error during device disconnection")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Disconnect failed: ${exception.message}"
                    )
                }
            }
        }
    }

    /**
     * Starts audio recording from the connected device.
     */
    fun startRecording() {
        val connection = currentConnection
        if (connection == null) {
            _uiState.update { it.copy(errorMessage = "No device connected for recording") }
            return
        }

        viewModelScope.launch {
            try
            {
                Timber.d("Starting audio recording")

                connection.startRecording().collect { audioData ->
                    // In a real application, you would process the audio data here
                    // For the demo, we just log basic information
                    if (audioData.sequenceNumber % 100L == 0L) { // Log every 100th buffer
                        Timber.d("Received audio buffer: ${audioData.samples.size} samples, " +
                                "sequence: ${audioData.sequenceNumber}")
                    }

                    // You could save to file, process for radio signals, etc.
                    processAudioData(audioData)
                }

            } catch (exception: Exception) {
                Timber.e(exception, "Error during audio recording")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Recording failed: ${exception.message}",
                        recordingState = RecordingState.Error(
                            message = exception.message ?: "Unknown recording error",
                            cause = exception
                        )
                    )
                }
            }
        }
    }

    /**
     * Stops audio recording.
     */
    fun stopRecording() {
        viewModelScope.launch {
            try {
                Timber.d("Stopping audio recording")
                currentConnection?.stopRecording()

            } catch (exception: Exception) {
                Timber.e(exception, "Error stopping audio recording")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Failed to stop recording: ${exception.message}"
                    )
                }
            }
        }
    }

    /**
     * Toggles audio playback through phone speakers
     */
    fun togglePlayback()
    {
        viewModelScope.launch {
            val connection = currentConnection
            if (connection == null)
            {
                _uiState.update { it.copy(errorMessage = "No device connected for playback") }
                return@launch
            }

            try
            {
                val currentPlaybackState = _uiState.value.isPlaybackEnabled
                val newPlaybackState = !currentPlaybackState

                connection.setPlaybackEnabled(newPlaybackState)

                _uiState.update { currentState ->
                    currentState.copy(isPlaybackEnabled = newPlaybackState)
                }

                Timber.d("Audio playback toggled: $newPlaybackState")

            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error toggling audio playback")
                _uiState.update { currentState ->
                    currentState.copy(errorMessage = "Failed to toggle playback: ${exception.message}")
                }
            }
        }
    }

    /**
     * Runs comprehensive AudioRecord diagnostics
     */
    fun runAudioRecordDiagnostics()
    {
        viewModelScope.launch {
            val device = _uiState.value.connectedDevice
            if (device == null)
            {
                _uiState.update { it.copy(errorMessage = "No device connected for diagnostics") }
                return@launch
            }

            try
            {
                Timber.d("Running AudioRecord diagnostics for device: ${device.displayName}")

                // Use public interface method
                val diagnostics = audioManager.getAudioRecordDiagnostics(device)

                // Log results
                Timber.i("=== AudioRecord Diagnostics ===")
                Timber.i("Device: ${diagnostics.deviceName}")
                Timber.i("Compatible: ${diagnostics.isCompatible}")
                Timber.i("Summary: ${diagnostics.summary}")

                diagnostics.audioRecordInfo?.let { info ->
                    Timber.i("AudioRecord State: ${info.state}")
                    Timber.i("Audio Source: ${info.audioSource}")
                    Timber.i("Sample Rate: ${info.sampleRate}")
                    Timber.i("Buffer Size: ${info.bufferSize}")
                }

                // Update UI
                val resultMessage = buildString {
                    appendLine("AudioRecord Diagnostics:")
                    appendLine("Device: ${diagnostics.deviceName}")
                    appendLine("Compatible: ${if (diagnostics.isCompatible) "✅ YES" else "❌ NO"}")
                    appendLine("Summary: ${diagnostics.summary}")

                    diagnostics.audioRecordInfo?.let { info ->
                        appendLine("Buffer Size: ${info.bufferSize}")
                        appendLine("Sample Rate: ${info.sampleRate}")
                    }
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        diagnosticResults = resultMessage,
                        errorMessage = if (!diagnostics.isCompatible) diagnostics.summary else null
                    )
                }

            } catch (exception: Exception)
            {
                Timber.e(exception, "Error running AudioRecord diagnostics")
                _uiState.update { currentState ->
                    currentState.copy(errorMessage = "Diagnostic failed: ${exception.message}")
                }
            }
        }
    }

    fun testSystemAudio()
    {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(errorMessage = "Creating test tone...") }

                val sampleRate = 48000
                val durationSeconds = 1.0
                val frequency = 440.0
                val amplitude = 0.3

                // Calculate buffer size
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val bufferSize = maxOf(minBufferSize, 8192)

                // Create AudioTrack
                val audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                    _uiState.update { it.copy(errorMessage = "Playing 440Hz tone...") }

                    // Start playback
                    audioTrack.play()

                    // Generate and write tone in chunks
                    val chunkSize = 4800 // 0.1 seconds worth
                    val totalSamples = (sampleRate * durationSeconds).toInt()
                    var samplesWritten = 0

                    while (samplesWritten < totalSamples) {
                        val remainingSamples = totalSamples - samplesWritten
                        val currentChunkSize = minOf(chunkSize, remainingSamples)
                        val chunk = ShortArray(currentChunkSize)

                        for (i in 0 until currentChunkSize) {
                            val sampleIndex = samplesWritten + i
                            val time = sampleIndex.toDouble() / sampleRate
                            val sample = (sin(2 * PI * frequency * time) * Short.MAX_VALUE * amplitude).toInt()
                            chunk[i] = sample.toShort()
                        }

                        val written = audioTrack.write(chunk, 0, chunk.size)
                        if (written < 0) {
                            _uiState.update { it.copy(errorMessage = "AudioTrack write error: $written") }
                            break
                        }

                        samplesWritten += written
                        kotlinx.coroutines.delay(50) // Small delay between chunks
                    }

                    // Wait for playback to finish
                    kotlinx.coroutines.delay(200)

                    audioTrack.stop()
                    audioTrack.release()

                    _uiState.update { it.copy(errorMessage = "Test tone complete! Did you hear 440Hz beep?") }

                } else {
                    _uiState.update { it.copy(errorMessage = "AudioTrack init failed: ${audioTrack.state}") }
                    audioTrack.release()
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Test failed: ${e.message}") }
            }
        }
    }

    // Clear error message
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Clear diagnostic results  
    fun clearDiagnostics() {
        _uiState.update { it.copy(diagnosticResults = null) }
    }

    /**
     * Observes recording state changes from the connection.
     */
    private fun observeRecordingState(connection: UsbAudioConnection) {
        viewModelScope.launch {
            try {
                connection.getRecordingState().collect { state ->
                    Timber.d("Recording state changed: $state")
                    _uiState.update { currentState ->
                        currentState.copy(recordingState = state)
                    }
                }
            } catch (exception: Exception) {
                Timber.e(exception, "Error observing recording state")
            }
        }
    }

    /**
     * Observes audio level changes from the connection.
     */
    private fun observeAudioLevels(connection: UsbAudioConnection) {
        viewModelScope.launch {
            try {
                connection.getAudioLevel().collect { levelInfo ->
                    _uiState.update { currentState ->
                        currentState.copy(audioLevel = levelInfo)
                    }

                    // Log clipping warnings
                    if (levelInfo.isClipping()) {
                        Timber.w("Audio clipping detected: ${levelInfo.currentLevel}")
                    }
                }
            } catch (exception: Exception) {
                Timber.e(exception, "Error observing audio levels")
            }
        }
    }

    /**
     * Processes incoming audio data.
     *
     * This is where you would implement your custom audio processing logic
     * for radio applications, signal analysis, etc., in this case we will process WSPR signals.
     *
     * @param audioData The audio data to process
     */
    private fun processAudioData(audioData: AudioData)
    {
        // Add the audio samples to the WSPR processor
        wsprProcessor.addSamples(audioData.samples)

        // Update the UI with the buffer status
        val bufferDurationSeconds = wsprProcessor.getBufferDurationSeconds()
        _uiState.update { it.copy(audioBufferSeconds = bufferDurationSeconds) }

        // Example processing - calculate basic statistics
        val samples = audioData.samples
        val maxAmplitude = samples.maxOrNull()?.toFloat() ?: 0f
        val minAmplitude = samples.minOrNull()?.toFloat() ?: 0f
        val averageAmplitude = samples.map { it.toFloat() }.average().toFloat()

        // Log statistics occasionally for demo purposes
        if (audioData.sequenceNumber % LOG_INTERVAL_BUFFERS == 0L)
        {
            val bufferSeconds = String.format("%.${BUFFER_STATUS_DECIMAL_PLACES}f", bufferDurationSeconds)
            Timber.d("Audio stats - Max: $maxAmplitude, Min: $minAmplitude, Avg: $averageAmplitude")
            Timber.d("WSPR buffer: ${bufferSeconds}s (ready: ${wsprProcessor.isReadyForDecode()})")
        }

        // In a real radio application, you might:
        // - Apply digital filters
        // - Perform FFT analysis for frequency domain processing
        // - Decode digital modes (WSPR, FT8, etc.)
        // - Apply noise reduction algorithms
        // - Save to file for later analysis

        // Example: Detect significant audio events
//        if (maxAmplitude > MAX_AMPLITUDE_16BIT * SIGNIFICANT_AUDIO_THRESHOLD)
//        {
//            Timber.d("Significant audio event detected at sequence ${audioData.sequenceNumber}")
//        }
    }

    // MARK: WSPR Functions
    /**
     * Generates a WSPR Signal and saves it as a WAV file.
     *
     * TODO: Communication over USB to a device.
     */
    fun encodeWSPR(callsign: String, gridSquare: String, power: Int)
    {
        viewModelScope.launch {
            try
            {
                _uiState.update { it.copy(errorMessage = "Generating WSPR signal...") }

                // Generate WSPR signal using AudioCoder library
                val wsprAudioData = CJarInterface.WSPREncodeToPCM(
                    callsign,
                    gridSquare,
                    power,
                    0, // No offset
                    false // Not LSB mode
                )

                if (wsprAudioData != null && wsprAudioData.isNotEmpty())
                {
                    val fileManager = WSPRFileManager(getApplication())
                    val savedFile = fileManager.saveWsprAsWav(
                        wsprAudioData,
                        callsign,
                        gridSquare,
                        power
                    )

                    if (savedFile != null)
                    {
                        _uiState.update { currentState ->
                            currentState.copy(
                                errorMessage = "WSPR signal saved: ${savedFile.name}",
                                lastWsprFile = savedFile
                            )
                        }

                        Timber.i("WSPR signal saved: ${savedFile.name}")
                    }
                    else
                    {
                        _uiState.update {
                            it.copy(errorMessage = "Failed to save WSPR file")
                        }
                    }
                }
                else
                {
                    _uiState.update { it.copy(errorMessage = "Failed to generate WSPR signal") }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error generating WSPR signal")
                _uiState.update {
                    it.copy(errorMessage = "WSPR generation failed: ${exception.message}")
                }
            }
        }
    }

    /**
     * Shares the last generated WSPR file.
     */
    fun shareWsprFile()
    {
        val file = _uiState.value.lastWsprFile

        if (file != null && file.exists())
        {
            val fileManager = WSPRFileManager(getApplication())
            val shareIntent = fileManager.shareWsprFile(file)

            if (shareIntent != null)
            {
                _uiState.update { it.copy(pendingShareIntent = shareIntent) }
            }
            else
            {
                _uiState.update { it.copy(errorMessage = "Failed to create the share intent") }
            }
        }
        else
        {
            _uiState.update { it.copy(errorMessage = "No WSPR file available to share") }
        }
    }

    /**
     * Clears the pending share intent.
     */
    fun clearPendingShare()
    {
        _uiState.update { it.copy(pendingShareIntent = null) }
    }

    /**
     * Decodes WSPR Signals from recent audio data.
     */
    fun decodeWSPR()
    {
        viewModelScope.launch {
            try
            {
                if (!wsprProcessor.isReadyForDecode())
                {
                    val minimumSeconds = wsprProcessor.getMinimumBufferSeconds()
                    val currentSeconds = wsprProcessor.getBufferDurationSeconds()
                    val formattedMinimum = String.format("%.${BUFFER_STATUS_DECIMAL_PLACES}f", minimumSeconds)
                    val formattedCurrent = String.format("%.${BUFFER_STATUS_DECIMAL_PLACES}f", currentSeconds)

                    _uiState.update {
                        it.copy(errorMessage = "Need ${formattedMinimum}s for decode, have ${formattedCurrent}s")
                    }

                    return@launch
                }

                val bufferDuration = String.format("%.${BUFFER_STATUS_DECIMAL_PLACES}f", wsprProcessor.getBufferDurationSeconds())
                _uiState.update { it.copy(errorMessage = "Decoding WSPR from ${bufferDuration}s buffer...") }

                val wsprMessages = wsprProcessor.decodeBufferedWSPR(
                    dialFrequencyMHz = WSPRBandplan.getDefaultFrequency(),
                    useLowerSideband = false
                )

                // Convert and display results
                val results = wsprMessages?.map { message ->
                    WSPRDecodeResult(
                        callsign = message.callsign ?: "Unknown",
                        gridSquare = message.gridsquare ?: "Unknown",
                        power = message.power,
                        snr = message.snr,
                        frequency = message.freq,
                        message = message.msg?: ""
                    )
                } ?: emptyList()

                _uiState.update {
                    it.copy(
                        wsprResults = results,
                        errorMessage = if (results.isNotEmpty())
                        {
                            "🎯 Decoded ${results.size} WSPR signal(s)!"
                        }
                        else
                        {
                            "No WSPR signals found in buffer"
                        }
                    )
                }

            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error decoding WSPR signals")
                _uiState.update {
                    it.copy(errorMessage = "WSPR decode failed: ${exception.message}")
                }
            }
        }
    }

    /**
     * Clears WSPR results
     */
    fun clearWSPRResults() {
        _uiState.update { it.copy(wsprResults = emptyList()) }
    }

    /**
     * Cleans up resources when the ViewModel is destroyed.
     */
    fun cleanup() {
        viewModelScope.launch {
            try {
                Timber.d("Cleaning up MainViewModel")

                // Stop any active recording
                currentConnection?.stopRecording()
                currentConnection?.disconnect()
                currentConnection = null

                // Clean up audio manager
                audioManager.cleanup()

                Timber.d("MainViewModel cleanup completed")

            } catch (exception: Exception) {
                Timber.e(exception, "Error during ViewModel cleanup")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}

/**
 * WSPR Decode result for UI.
 */
data class WSPRDecodeResult(
    val callsign: String,
    val gridSquare: String,
    val power: Int,
    val snr: Float,
    val frequency: Double,
    val message: String
)



