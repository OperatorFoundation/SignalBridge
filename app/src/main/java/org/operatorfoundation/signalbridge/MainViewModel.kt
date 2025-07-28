package org.operatorfoundation.signalbridge

import android.app.Application
import android.content.Intent
import android.os.Message
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.operatorfoundation.audiocoder.*
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber
import java.io.File

/**
 * ViewModel for the main demo activity.
 *
 * This ViewModel manages the state of USB audio device discovery, connection,
 * and recording operations. It serves as the business logic layer between
 * the UI and the USB Audio Library.
 */
class MainViewModel(application: Application) : AndroidViewModel(application)
{
    // ========== Library Components ==========
    private val wsprFileManager = WSPRFileManager(application)
    private val usbAudioManager = UsbAudioManager.create(application)
    private var currentUsbConnection: UsbAudioConnection? = null
    private var wsprStation: WSPRStation? = null

    // ========== UI State ==========
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init
    {
        startDeviceDiscovery()
    }

    // ========== USB Device Management ==========

    /**
     * Starts continuous discovery of USB audio devices.
     * Updates UI state with available devices as they are found.
     */
    private fun startDeviceDiscovery()
    {
        viewModelScope.launch {
            try
            {
                usbAudioManager.discoverDevices().collect { devices ->
                    Timber.d("Discovered ${devices.size} USB audio devices")
                    _uiState.update { it.copy(availableDevices = devices) }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during device discovery")
                updateErrorMessage("Device discovery failed: ${exception.message}")
            }
        }
    }

    /**
     * Connects to a USB audio device and starts WSPR station operation.
     *
     * This method:
     * 1. Establishes USB audio connection
     * 2. Creates a WSPR audio source wrapper
     * 3. Initializes and starts the WSPR station
     * 4. Begins automatic WSPR timing and decoding
     */
    fun connectToDevice(device: UsbAudioDevice)
    {
        viewModelScope.launch {
            try
            {
                // 1: Connect
                val connectionResult = usbAudioManager.connectToDevice(device)

                if (connectionResult.isFailure)
                {
                    val errorMessage = "Connection failed: ${connectionResult.exceptionOrNull()?.message}"
                    updateErrorMessage(errorMessage)
                    return@launch
                }

                val usbConnection = connectionResult.getOrThrow()
                currentUsbConnection = usbConnection

                startAudioLevelMonitoring()

                // 2: Create the WSPR audio source
                val audioSource = SignalBridgeWSPRAudioSource(
                    usbAudioConnection = usbConnection,
                    bufferConfiguration = AudioBufferConfiguration.createDefault()
                )

                // 3: Create and configure the WSPR station
                val stationConfiguration = WSPRStationConfiguration.createForBand("20m") // Most popular band
                wsprStation = WSPRStation(audioSource, stationConfiguration)

                // 4: Start WSPR station with automatic operation
                val stationStartResult = wsprStation!!.startStation()

                if (stationStartResult.isFailure)
                {
                    val errorMessage = "WSPR station failed to start: ${stationStartResult.exceptionOrNull()?.message}"
                    updateErrorMessage(errorMessage)
                    return@launch
                }

                // 5: Begin monitoring WSPR station
                startWSPRStationMonitoring()

                updateStatusMessage("WSPR station active on ${device.displayName}")
                _uiState.update {
                    it.copy(
                        connectedDevice = device,
                        isWSPRStationActive = true
                    )
                }

                Timber.i("WSPR station successfully started on device: ${device.displayName}")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Failed to connect and start WSPR station")
                updateErrorMessage("Connection failed: ${exception.message}")
            }
        }
    }

    /**
     * Disconnects from the current device and stops WSPR station operation.
     */
    fun disconnect()
    {
        viewModelScope.launch {
            try
            {
                updateStatusMessage("Disconnecting...")

                // Stop WSPR station
                wsprStation?.stopStation()
                wsprStation = null

                // Disconnect USB audio
                currentUsbConnection?.disconnect()
                currentUsbConnection = null

                _uiState.update {
                    it.copy(
                        connectedDevice = null,
                        isWSPRStationActive = false,
                        stationState = null,
                        cycleInformation = null,
                        decodeResults = emptyList()
                    )
                }

                updateStatusMessage("Disconnected successfully")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during disconnection")
                updateErrorMessage("Disconnect failed: ${exception.message}")
            }
        }
    }

    private fun startAudioLevelMonitoring()
    {
        val connection = currentUsbConnection ?: return

        viewModelScope.launch {
            connection.getAudioLevel().collect { levelInfo ->
                _uiState.update {
                    it.copy(
                        audioLevel = levelInfo,
                        isReceivingAudio = levelInfo.currentLevel > 0.01f // 1% threshold
                    )
                }
            }
        }
    }

    // ========== WSPR Encoding and File Management ==========

    /**
     * Generates a WSPR signal and saves it as a WAV file.
     *
     * This method:
     * 1. Validates the input parameters
     * 2. Generates the WSPR signal using the AudioCoder library
     * 3. Saves the signal as a WAV file with metadata
     * 4. Updates the UI state with the result
     *
     * @param callsign Amateur radio callsign (e.g., "Q0QQQ")
     * @param gridSquare Maidenhead grid square (e.g., "FN31")
     * @param power Transmit power in dBm (0-60)
     */
    fun encodeWSPRSignal(callsign: String, gridSquare: String, power: Int)
    {
        viewModelScope.launch {
            try
            {
                updateStatusMessage("Generating WSPR signal for $callsign...")

                // Validate input parameters
                val validationResult = validateWSPRParameters(callsign, gridSquare, power)
                if (validationResult != null) {
                    updateErrorMessage(validationResult)
                    return@launch
                }

                // Generate WSPR signal on background thread
                val wsprAudioData = withContext(Dispatchers.IO) {
                    CJarInterface.WSPREncodeToPCM(
                        callsign.trim().uppercase(),
                        gridSquare.trim().uppercase(),
                        power,
                        0, // No frequency offset
                        false // Use USB mode (not LSB)
                    )
                }

                if (wsprAudioData == null || wsprAudioData.isEmpty())
                {
                    updateErrorMessage("Failed to generate WSPR signal - invalid parameters or encoding error")
                    return@launch
                }

                // Save as WAV file with metadata
                val savedFile = withContext(Dispatchers.IO)
                {
                    wsprFileManager.saveWsprAsWav(
                        audioData = wsprAudioData,
                        callsign = callsign.trim().uppercase(),
                        gridSquare = gridSquare.trim().uppercase(),
                        power = power
                    )
                }

                if (savedFile != null)
                {
                    // Update UI state with successful generation
                    _uiState.update { currentState ->
                        currentState.copy(
                            lastGeneratedFile = savedFile,
                            statusMessage = "WSPR signal generated: ${savedFile.name}",
                            errorMessage = null
                        )
                    }

                    Timber.i("WSPR signal generated successfully: ${savedFile.name}")

                }
                else
                {
                    updateErrorMessage("Failed to save WSPR file to storage")
                }

            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error generating WSPR signal")
                updateErrorMessage("WSPR generation failed: ${exception.message}")
            }
        }
    }

    /**
     * Shares the last generated WSPR file using the Android share intent.
     *
     * This method:
     * 1. Checks if a file is available to share
     * 2. Creates a share intent with proper file permissions
     * 3. Launches the system share dialog
     * 4. Handles any errors gracefully
     */
    fun shareLastWSPRFile()
    {
        viewModelScope.launch {
            try
            {
                val fileToShare = _uiState.value.lastGeneratedFile

                if (fileToShare == null || !fileToShare.exists())
                {
                    updateErrorMessage("No WSPR file available to share")
                    return@launch
                }

                updateStatusMessage("Preparing to share ${fileToShare.name}...")

                // Create share intent on background thread
                val shareIntent = withContext(Dispatchers.IO) {
                    wsprFileManager.shareWsprFile(fileToShare)
                }

                if (shareIntent != null)
                {
                    // Verify that the intent can be handled by the system
                    val packageManager = getApplication<Application>().packageManager
                    val canHandleIntent = shareIntent.resolveActivity(packageManager) != null

                    if (canHandleIntent)
                    {
                        // Create chooser to ensure user gets options
                        val chooserIntent = Intent.createChooser(shareIntent, "Share WSPR Signal")
                        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        // Update UI state with pending share intent
                        _uiState.update { currentState ->
                            currentState.copy(
                                pendingShareIntent = chooserIntent,
                                statusMessage = "Opening share dialog..."
                            )
                        }

                        Timber.i("Share intent created for file: ${fileToShare.name}")
                    }
                    else
                    {
                        updateErrorMessage("No apps available to share WSPR files")
                    }
                }
                else
                {
                    updateErrorMessage("Failed to create share intent for WSPR file")
                }

            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error sharing WSPR file")
                updateErrorMessage("File sharing failed: ${exception.message}")
            }
        }
    }

    /**
     * Gets a list of all previously generated WSPR files.
     * Useful for file management and history display.
     *
     * @return List of WSPR files in chronological order (newest first)
     */
    fun getSavedWSPRFiles(): List<File>
    {
        return try
        {
            wsprFileManager.getSavedWsprFiles()
                .sortedByDescending { it.lastModified() }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error retrieving saved WSPR files")
            emptyList()
        }
    }

    /**
     * Deletes a specific WSPR file from storage.
     *
     * @param file The file to delete
     * @return true if deletion was successful, false otherwise
     */
    fun deleteWSPRFile(file: File): Boolean
    {
        return try
        {
            val deleted = wsprFileManager.deleteWsprFile(file)

            if (deleted)
            {
                // Update UI state if this was the last generated file
                _uiState.update { currentState ->
                    if (currentState.lastGeneratedFile == file)
                    {
                        currentState.copy(
                            lastGeneratedFile = null,
                            statusMessage = "File deleted: ${file.name}"
                        )
                    }
                    else
                    {
                        currentState
                    }
                }
            }

            deleted
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error deleting WSPR file: ${file.name}")
            false
        }
    }

    /**
     * Clears the pending share intent after it has been processed.
     * Called by the UI after the share dialog is displayed.
     */
    fun clearPendingShareIntent()
    {
        _uiState.update { it.copy(pendingShareIntent = null) }
    }

    // ========== WSPR Station Monitoring ==========

    /**
     * Starts monitoring the WSPR station state and results.
     * Updates the UI with real-time information about station operation.
     */
    private fun startWSPRStationMonitoring()
    {
        val station = wsprStation ?: return

        // Monitor station operational state
        viewModelScope.launch {
            station.stationState.collect { state ->
                Timber.d("=== WSPR Station State Change: ${state::class.simpleName} ===")
                _uiState.update { it.copy(stationState = state) }

                // Update status message based on station state
                val statusMessage = when (state)
                {
                    is WSPRStationState.Running -> "WSPR station running - monitoring for signals"
                    is WSPRStationState.WaitingForNextWindow -> "Next decode in ${state.windowInfo.secondsUntilWindow}s"
                    is WSPRStationState.CollectingAudio -> "Collecting WSPR audio..."
                    is WSPRStationState.ProcessingAudio -> "Decoding WSPR signals..."
                    is WSPRStationState.DecodeCompleted -> "Decode complete: ${state.decodedSignalCount} signal(s) found"
                    is WSPRStationState.Error -> state.errorDescription
                    else -> "WSPR station: ${state::class.simpleName}"
                }

                if (state is WSPRStationState.Error)
                {
                    updateErrorMessage(statusMessage)
                }
                else
                {
                    updateStatusMessage(statusMessage)
                }
            }
        }

        // Monitor decode results
        viewModelScope.launch {
            station.decodeResults.collect { results ->
                _uiState.update { it.copy(decodeResults = results) }

                if (results.isNotEmpty())
                {
                    Timber.i("Received ${results.size} WSPR decode results")
                    results.forEach { result ->
                        Timber.i("WSPRL ${result.createSummaryLine()}")
                    }
                }
            }
        }

        // Monitor cycle information for UI display
        viewModelScope.launch {
            station.cycleInformation.collect { cycleInformation ->
                _uiState.update { it.copy(cycleInformation = cycleInformation) }
            }
        }
    }

    // ========== Manual Operations ==========

    /**
     * Triggers an immediate WSPR decode if timing conditions are suitable.
     * This respects WSPR protocol timing and will only decode during valid windows
     */
    fun triggerManualDecode()
    {
        viewModelScope.launch {
            val station = wsprStation
            if (station == null)
            {
                updateErrorMessage("No WSPR station active")
                return@launch
            }

            updateStatusMessage("Attempting manual WSPR decode...")

            val decodeResult = station.requestImmediateDecode()

            if (decodeResult.isSuccess)
            {
                val results = decodeResult.getOrThrow()
                updateStatusMessage("Manual decode complete: ${results.size} signal(s) found")
            }
            else
            {
                val errorMessage = "Manual decode failed: ${decodeResult.exceptionOrNull()?.message}"
                updateErrorMessage(errorMessage)
            }
        }
    }

    /**
     * Gets current status information from both libraries for diagnostics.
     */
    fun getDiagnosticInformation(): String
    {
        return buildString {
            appendLine("=== WSPR Demo Diagnostic Information ===")
            appendLine()

            // USB Audio Status
            appendLine("SignalBridge USB Audio:")
            val connectedDevice = _uiState.value.connectedDevice
            if (connectedDevice != null)
            {
                appendLine("  Connected Device: ${connectedDevice.displayName}")
                appendLine("  Device ID: ${connectedDevice.deviceId}")
                appendLine("  USB Connection: Active")
            }
            else
            {
                appendLine("  USB Connection: None")
            }
            appendLine()

            // WSPR Station Status
            appendLine("AudioCoder WSPR Station:")
            val stationState = _uiState.value.stationState
            if (stationState != null)
            {
                appendLine("  Station State: ${stationState::class.simpleName}")

                when (stationState)
                {
                    is WSPRStationState.WaitingForNextWindow ->
                        appendLine("  Next Window: ${stationState.windowInfo.humanReadableDescription}")
                    is WSPRStationState.Error ->
                        appendLine("  Error: ${stationState.errorDescription}")
                    else -> {}
                }
            }
            else
            {
                appendLine("  WSPR Station: Not active")
            }
            appendLine()

            // Cycle information
            val cycleInformation = _uiState.value.cycleInformation

            if (cycleInformation != null)
            {
                appendLine("WSPR Timing:")
                appendLine("  Cycle Position: ${cycleInformation.cyclePositionSeconds}s / 120s")
                appendLine("  Transmission Window: ${if (cycleInformation.isInTransmissionWindow) "Yes" else "No"}")
                appendLine("  Decode Window Open: ${if (cycleInformation.isDecodeWindowOpen) "Yes" else "No"}")
                appendLine("  ${cycleInformation.nextDecodeWindowInfo.humanReadableDescription}")
            }
            appendLine()

            // Recent results
            val results = _uiState.value.decodeResults
            appendLine("Recent WSPR Decodes: ${results.size}")
            results.take(5).forEach { result ->
                appendLine("  ${result.createSummaryLine()}")
            }
        }
    }

    // ========== Helper Methods ==========
    private fun updateStatusMessage(message: String)
    {
        _uiState.update { it.copy(statusMessage = message, errorMessage = null) }
    }

    private fun updateErrorMessage(message: String)
    {
        _uiState.update { it.copy(errorMessage = message) }
    }

    /**
     * Validates WSPR encoding parameters.
     *
     * @param callsign Amateur radio callsign
     * @param gridSquare Maidenhead grid square
     * @param power Power level in dBm
     * @return Error message if validation fails, null if parameters are valid
     */
    private fun validateWSPRParameters(callsign: String, gridSquare: String, power: Int): String?
    {
        // Validate callsign
        val trimmedCallsign = callsign.trim()

        if (trimmedCallsign.isEmpty())
        {
            return "Callsign cannot be empty"
        }

        if (trimmedCallsign.length > 6)
        {
            return "Callsign too long (maximum 6 characters)"
        }

        if (!trimmedCallsign.matches(Regex("^[A-Z0-9/]+$")))
        {
            return "Callsign contains invalid characters (use A-Z, 0-9, / only)"
        }

        // Validate grid square
        val trimmedGridSquare = gridSquare.trim()
        if (trimmedGridSquare.isEmpty())
        {
            return "Grid square cannot be empty"
        }

        if (trimmedGridSquare.length < 4)
        {
            return "Grid square too short (minimum 4 characters)"
        }

        if (trimmedGridSquare.length > 6)
        {
            return "Grid square too long (maximum 6 characters)"
        }

        if (!trimmedGridSquare.matches(Regex("^[A-Z]{2}[0-9]{2}[A-Z]{0,2}$")))
        {
            return "Invalid grid square format (use format like FN31 or FN31pr)"
        }

        // Validate power level
        if (power < 0 || power > 60)
        {
            return "Power level must be between 0 and 60 dBm"
        }

        // Check if power level is a valid WSPR power encoding
        val validPowerLevels = listOf(0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60)
        if (!validPowerLevels.contains(power))
        {
            return "Power level $power is not a valid WSPR encoding (use: ${validPowerLevels.joinToString(", ")})"
        }

        return null // All validation passed
    }

    // ========== Cleanup ==========

    /**
     * Cleans up resources when the ViewModel is destroyed.
     */
    override fun onCleared()
    {
        super.onCleared()

        viewModelScope.launch {
            try
            {
                wsprStation?.stopStation()
                currentUsbConnection?.disconnect()
                usbAudioManager.cleanup()
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during ViewModel cleanup")
            }
        }
    }

}