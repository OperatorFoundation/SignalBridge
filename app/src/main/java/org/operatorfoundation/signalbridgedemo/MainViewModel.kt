package org.operatorfoundation.signalbridgedemo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.exceptions.UsbAudioException
import org.operatorfoundation.signalbridge.models.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.operatorfoundation.signalbridge.internal.UsbAudioManagerImpl
import timber.log.Timber

/**
 * ViewModel for the main demo activity.
 *
 * This ViewModel manages the state of USB audio device discovery, connection,
 * and recording operations. It serves as the business logic layer between
 * the UI and the USB Audio Library.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // USB Audio Library components
    private val audioManager: UsbAudioManager = UsbAudioManager.create(application)
    private var currentConnection: UsbAudioConnection? = null

    // UI State
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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
     * Manually refreshes the list of available devices.
     */
    fun refreshDevices()
    {
        try
        {
            Timber.d("Manually refreshing device list")
            // Device discovery is continuous, so this is mainly for UI feedback
            // Trigger a one-time scan
            val audioManagerImpl = audioManager as UsbAudioManagerImpl
            audioManagerImpl.discoverDevices()
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during manual device refresh")

            _uiState.update { currentState ->
                currentState.copy(errorMessage = "Device refresh failed: ${exception.message}")
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
     * Runs comprehensive AudioRecord diagnostics
     */
    fun runAudioRecordDiagnostics()
    {
        viewModelScope.launch {
            val device = _uiState.value.connectedDevice
            if (device == null) {
                _uiState.update { it.copy(errorMessage = "No device connected for diagnostics") }
                return@launch
            }

            try {
                Timber.d("Running Phase 2 AudioRecord diagnostics for device: ${device.displayName}")

                // Cast to implementation to access diagnostic methods
                val audioManagerImpl = audioManager as UsbAudioManagerImpl
                val diagnostics = audioManagerImpl.getAudioRecordDiagnostics(device)

                // Log comprehensive diagnostic information
                Timber.i("=== Phase 2 AudioRecord Diagnostics ===")
                Timber.i("Device: ${diagnostics.deviceName}")
                Timber.i("Device ID: ${diagnostics.deviceId}")
                Timber.i("Has Permission: ${diagnostics.hasPermission}")
                Timber.i("Compatible: ${diagnostics.isCompatible}")
                Timber.i("Summary: ${diagnostics.summary}")

                if (diagnostics.audioRecordInfo != null)
                {
                    val info = diagnostics.audioRecordInfo!!
                    Timber.i("AudioRecord State: ${info.state}")
                    Timber.i("Recording State: ${info.recordingState}")
                    Timber.i("Sample Rate: ${info.sampleRate}")
                    Timber.i("Buffer Size: ${info.bufferSize}")
                    Timber.i("Min Buffer Size: ${info.minBufferSize}")
                    Timber.i("Is Initialized: ${info.isInitialized}")
                }

                // Update UI with diagnostic results
                val resultMessage = buildString {
                    appendLine("AudioRecord Diagnostics:")
                    appendLine("Device: ${diagnostics.deviceName}")
                    appendLine("Compatible: ${if (diagnostics.isCompatible) "✅ YES" else "❌ NO"}")
                    appendLine("Summary: ${diagnostics.summary}")

                    if (diagnostics.audioRecordInfo != null)
                    {
                        val info = diagnostics.audioRecordInfo!!
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

            } catch (exception: Exception) {
                Timber.e(exception, "Error running AudioRecord diagnostics")
                _uiState.update { currentState ->
                    currentState.copy(
                        errorMessage = "Diagnostic failed: ${exception.message}"
                    )
                }
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
     * for radio applications, signal analysis, etc.
     *
     * @param audioData The audio data to process
     */
    private fun processAudioData(audioData: AudioData) {
        // Example processing - calculate basic statistics
        val samples = audioData.samples
        val maxAmplitude = samples.maxOrNull()?.toFloat() ?: 0f
        val minAmplitude = samples.minOrNull()?.toFloat() ?: 0f
        val avgAmplitude = samples.map { it.toFloat() }.average().toFloat()

        // Log statistics occasionally for demo purposes
        if (audioData.sequenceNumber % 1000L == 0L) {
            Timber.d("Audio stats - Max: $maxAmplitude, Min: $minAmplitude, Avg: $avgAmplitude")
        }

        // In a real radio application, you might:
        // - Apply digital filters
        // - Perform FFT analysis for frequency domain processing
        // - Decode digital modes (WSPR, FT8, etc.)
        // - Apply noise reduction algorithms
        // - Save to file for later analysis

        // Example: Save significant audio events
        if (maxAmplitude > Short.MAX_VALUE * 0.5f) { // Above 50% of max range
            Timber.d("Significant audio event detected at sequence ${audioData.sequenceNumber}")
            // Could trigger further processing or save this buffer
        }
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
 * UI state data class containing all the information needed by the UI.
 */
data class MainUiState(
    val availableDevices: List<UsbAudioDevice> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val connectedDevice: UsbAudioDevice? = null,
    val recordingState: RecordingState = RecordingState.Stopped,
    val audioLevel: AudioLevelInfo? = null,
    val errorMessage: String? = null,
    val diagnosticResults: String? = null  // Add diagnostic results
)