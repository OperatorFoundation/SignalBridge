package org.operatorfoundation.signalbridge

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.operatorfoundation.audiocoder.*
import org.operatorfoundation.audiocoder.models.WSPRStationConfiguration
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.transmission.SerialConnection
import org.operatorfoundation.transmission.SerialConnectionFactory
import timber.log.Timber
import java.io.File

/**
 * ViewModel that integrates three libraries:
 * - SignalBridge: USB audio device management
 * - AudioCoder: WSPR processing and timing
 * - TransmissionAndroid: Serial communication with custom radio
 *
 * This ViewModel manages USB audio input as well as serial radio transmission.
 */
class MainViewModel(application: Application) : AndroidViewModel(application)
{
    // ========== Library Components ==========

    // AudioCoder components
    private val wsprFileManager = WSPRFileManager(application)
    private var wsprStation: WSPRStation? = null

    // SignalBridge components
    private val usbAudioManager = UsbAudioManager.create(application)
    private var currentUsbConnection: UsbAudioConnection? = null

    // TransmissionAndroid components
    private val serialConnectionFactory = SerialConnectionFactory(application)
    private var currentSerialConnection: SerialConnection? = null

    // ========== UI State ==========
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init
    {
        startUSBAudioDeviceDiscovery()
        startSerialDeviceDiscovery()
    }

    // ========== USB Audio Device Management (Signal Bridge) ==========

    /**
     * Starts continuous discovery of USB audio devices.
     */
    private fun startUSBAudioDeviceDiscovery()
    {
        viewModelScope.launch {
            try
            {
                usbAudioManager.discoverDevices().collect { devices ->
                    Timber.d("Discovered ${devices.size} USB audio devices")
                    _uiState.update { it.copy(availableUsbDevices = devices) }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during USB audio device discovery")
                updateErrorMessage("USB audio device discovery failed: ${exception.message}")
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
    fun connectToUSBAudioDevice(device: UsbAudioDevice)
    {
        viewModelScope.launch {
            try
            {
                // 1: Connect to audio device
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

                updateStatusMessage("USB audio connected - WSPR station active")
                _uiState.update {
                    it.copy(
                        connectedUsbDevice = device,
                        isWSPRStationActive = true,
                        decodeResults = emptyList(), // Clear previous results
                        decodeFailures = emptyList() // Clear previous failures
                    )
                }

                Timber.i("WSPR station successfully listening to USB device: ${device.displayName}")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Failed to connect to USB audio device and start WSPR monitoring station")
                updateErrorMessage("USB audio connection failed: ${exception.message}")
            }
        }
    }

    /**
     * Disconnects from the current USB audio device and stops WSPR monitoring station operation.
     */
    fun disconnectUSBAudio()
    {
        viewModelScope.launch {
            try
            {
                updateStatusMessage("Disconnecting USB audio...")

                // Stop WSPR station
                wsprStation?.stopStation()
                wsprStation = null

                // Disconnect USB audio
                currentUsbConnection?.disconnect()
                currentUsbConnection = null

                _uiState.update {
                    it.copy(
                        connectedUsbDevice = null,
                        isWSPRStationActive = false,
                        stationState = null,
                        cycleInformation = null,
                        decodeResults = emptyList(),
                        decodeFailures = emptyList(),
                        audioLevel = null,
                        isReceivingAudio = false
                    )
                }

                updateStatusMessage("USB audio disconnected")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during USB disconnection")
                updateErrorMessage("USB disconnect failed: ${exception.message}")
            }
        }
    }

    /**
     * Monitors audio levels from the USB connection.
     */
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

    // ========== WSPR Station Monitoring (AudioCoder) ==========

    /**
     * Starts monitoring the WSPR station state and results.
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

                    val failure = DecodeFailure(
                        id = System.currentTimeMillis(),
                        timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                        reason = state.errorDescription,
                        cyclePosition = _uiState.value.cycleInformation?.cyclePositionSeconds ?: 0,
                        audioLevel = "${((_uiState.value.audioLevel?.currentLevel ?: 0f) * 100).toInt()}%"
                    )

                    _uiState.update { currentState ->
                        currentState.copy(
                            decodeFailures = (listOf(failure) + currentState.decodeFailures).take(20)
                        )
                    }
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

    /**
     * Triggers an immediate WSPR decode if timing conditions are suitable.
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

                val failure = DecodeFailure(
                    id = System.currentTimeMillis(),
                    timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                    reason = "Manual decode: ${decodeResult.exceptionOrNull()?.message}",
                    cyclePosition = _uiState.value.cycleInformation?.cyclePositionSeconds ?: 0,
                    audioLevel = "${((_uiState.value.audioLevel?.currentLevel ?: 0f) * 100).toInt()}%"
                )

                _uiState.update { currentState ->
                    currentState.copy(
                        decodeFailures = (listOf(failure) + currentState.decodeFailures).take(20)
                    )
                }
            }
        }
    }

    // ========== Serial Device Management (TransmissionAndroid) ==========
    /**
     * Starts discovery of serial devices for custom radio connection.
     * Continuously monitors for device connection/disconnection.
     */

    private fun startSerialReadLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("Starting serial read loop")
            while (currentSerialConnection != null) {
                try {
                    val data = currentSerialConnection?.readAvailable()
                    if (data != null && data.isNotEmpty()) {
                        val message = data.decodeToString().trim()
                        Timber.d("Arduino response: $message")

                        // Update UI or process response
                        withContext(Dispatchers.Main) {
                            // Add to transmission history or update status
                            updateStatusMessage("Arduino: $message")
                        }
                    }
                    delay(50) // Check every 50ms
                } catch (e: Exception) {
                    Timber.e(e, "Serial read loop error")
                    break
                }
            }
            Timber.d("Serial read loop ended")
        }
    }

    private fun startSerialDeviceDiscovery()
    {
        viewModelScope.launch {
            try
            {
                // Poll for devices periodically
                while (true) {
                    val devices = withContext(Dispatchers.IO) {
                        serialConnectionFactory.findAvailableDevices()
                    }

                    val currentDeviceCount = _uiState.value.availableSerialDevices.size

                    if (devices.size != currentDeviceCount) {
                        Timber.d("Serial device count changed: ${currentDeviceCount} -> ${devices.size}")
                        _uiState.update { it.copy(availableSerialDevices = devices) }
                    }

                    // Poll every 2 seconds for new devices
                    delay(2000)
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during serial device discovery")
                updateErrorMessage("Serial device discovery failed: ${exception.message}")

                // Retry after a delay
                delay(5000)
                startSerialDeviceDiscovery() // Restart discovery
            }
        }
    }

    /**
     * Connects to a serial device for custom radio communication.
     */
    fun connectToSerialDevice(driver: UsbSerialDriver)
    {
        viewModelScope.launch {
            try
            {
                updateStatusMessage("Connecting to custom radio...")

                serialConnectionFactory.createConnection(driver.device).collect { state ->
                    when (state)
                    {
                        is SerialConnectionFactory.ConnectionState.Connected -> {
                            currentSerialConnection = state.connection

                            // START THE READ LOOP
                            startSerialReadLoop()

                            _uiState.update {
                                it.copy(
                                    connectedSerialDevice = driver,
                                    serialConnectionState = state
                                )
                            }

                            updateStatusMessage("Connected to custom radio: ${driver.device.deviceName}")
                            Timber.i("Successfully connected to serial device: ${driver.device.deviceName}")
                        }

                        is SerialConnectionFactory.ConnectionState.Error -> {
                            val errorMessage = "Serial connection failed: ${state.message}"
                            updateErrorMessage(errorMessage)
                            Timber.e("Serial connection failed: ${state.message}")
                        }

                        is SerialConnectionFactory.ConnectionState.RequestingPermission -> {
                            updateStatusMessage("Requesting USB permission for radio...")
                        }

                        is SerialConnectionFactory.ConnectionState.Connecting -> {
                            updateStatusMessage("Establishing serial connection...")
                        }

                        else -> { /* Handle other states if needed */ }
                    }
                }
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error connecting to serial device")
                updateErrorMessage("Serial connection failed: ${exception.message}")
            }
        }
    }

    /**
     * Disconnects from the current serial device.
     */
    fun disconnectSerial()
    {
        viewModelScope.launch {
            try
            {
                currentSerialConnection?.close()
                currentSerialConnection = null
                serialConnectionFactory.disconnect()

                _uiState.update {
                    it.copy(
                        connectedSerialDevice = null,
                        serialConnectionState = SerialConnectionFactory.ConnectionState.Disconnected,
                        isTransmitting = false,
                        transmissionStatus = "idle"
                    )
                }

                updateStatusMessage("Disconnected from custom radio")
                Timber.i("Disconnected from serial device")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during serial disconnection")
                updateErrorMessage("Serial disconnect failed: ${exception.message}")
            }
        }
    }

    // ========== WSPR Encoding and Transmissions ==========
    /**
     * Generates a WSPR signal and optionally sends transmission command to custom radio.
     * Works with or without serial connection - always generates and saves WAV file.
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
                _uiState.update { it.copy(isTransmitting = true, transmissionStatus = "generating") }
                updateStatusMessage("Generating WSPR signal for $callsign...")

                // Validate input parameters
                val validationResult = validateWSPRParameters(callsign, gridSquare, power)
                if (validationResult != null) {
                    updateErrorMessage(validationResult)
                    _uiState.update { it.copy(isTransmitting = false, transmissionStatus = "idle") }
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
                    _uiState.update { it.copy(isTransmitting = false, transmissionStatus = "idle") }
                    return@launch
                }

                // Save as WAV file for sharing/debugging
                val savedFile = withContext(Dispatchers.IO)
                {
                    wsprFileManager.saveWsprAsWav(
                        audioData = wsprAudioData,
                        callsign = callsign.trim().uppercase(),
                        gridSquare = gridSquare.trim().uppercase(),
                        power = power
                    )
                }

                _uiState.update { it.copy(lastGeneratedFile = savedFile) }

                // If custom transmitter is connected, attempt transmission
                if (currentSerialConnection != null)
                {
                    _uiState.update { it.copy(transmissionStatus = "sending") }
                    updateStatusMessage("Sending WSPR command to transmitter...")

                    // Send transmission command to custom radio via serial
                    // FIXME: Send correct command and values
                    val success = withContext(Dispatchers.IO) {
                        val command = "WSPR_TX:${callsign.trim().uppercase()},${gridSquare.trim().uppercase()},${power}"
                        currentSerialConnection?.writeWithLengthPrefix(command.toByteArray(), 16) ?: false
                    }

                    if (success)
                    {
                        _uiState.update { it.copy(transmissionStatus = "transmitting") }
                        updateStatusMessage("Custom radio transmitting WSPR signal...")

                        // FIXME: Wait for response from transmitter
                        // Simulate transmission time (in real implementation, listen for radio feedback)
                        withContext(Dispatchers.IO) {
                            Thread.sleep(3000) // Shortened for demo
                        }

                        // Add to transmission history
                        val newTransmission = TransmissionRecord(
                            id = System.currentTimeMillis(),
                            timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
                            callsign = callsign.trim().uppercase(),
                            gridSquare = gridSquare.trim().uppercase(),
                            power = power,
                            status = "success"
                        )

                        _uiState.update { currentState ->
                            currentState.copy(
                                transmissionStatus = "complete",
                                transmissionHistory = (listOf(newTransmission) + currentState.transmissionHistory).take(20)
                            )
                        }

                        updateStatusMessage("WSPR transmission complete")
                    }
                    else
                    {
                        updateErrorMessage("Failed to send command to transmitter")
                        _uiState.update { it.copy(transmissionStatus = "complete") } // Still generated file successfully
                        updateStatusMessage("WSPR signal generated (radio command failed)")
                    }
                }
                else
                {
                    // No radio connected - just generated file
                    _uiState.update { it.copy(transmissionStatus = "complete") }
                    updateStatusMessage("WSPR signal generated and saved to file")
                }

                // Reset status after delay
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(transmissionStatus = "idle", isTransmitting = false) }

                Timber.i("WSPR signal generation completed")
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during WSPR generation")
                updateErrorMessage("WSPR generation failed: ${exception.message}")
                _uiState.update { it.copy(isTransmitting = false, transmissionStatus = "idle") }
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

    // ========== Diagnostics and Utilities ==========

    /**
     * Gets current status information from both libraries for diagnostics.
     */
    fun getDiagnosticInformation(): String
    {
        return buildString {
            appendLine("=== WSPR Demo Diagnostic Information ===")
            appendLine()


            // SignalBridge USB Audio Status
            appendLine("SignalBridge USB Audio:")
            val connectedUsbDevice = _uiState.value.connectedUsbDevice
            if (connectedUsbDevice != null)
            {
                appendLine("  Connected Device: ${connectedUsbDevice.displayName}")
                appendLine("  Device ID: ${connectedUsbDevice.deviceId}")
                appendLine("  USB Connection: Active")

                val audioLevel = _uiState.value.audioLevel
                if (audioLevel != null)
                {
                    appendLine("  Audio Level: ${(audioLevel.currentLevel * 100).toInt()}%")
                    appendLine("  Peak Level: ${(audioLevel.peakLevel * 100).toInt()}%")
                    appendLine("  Receiving Audio: ${_uiState.value.isReceivingAudio}")
                }
            }
            else
            {
                appendLine("  USB Connection: None")
                appendLine("  Available Devices: ${_uiState.value.availableUsbDevices.size}")
            }
            appendLine()

            // AudioCoder WSPR Station Status
            appendLine("AudioCoder WSPR Station:")
            val stationState = _uiState.value.stationState
            if (stationState != null)
            {
                appendLine("  Station State: ${stationState::class.simpleName}")
                appendLine("  Station Active: ${_uiState.value.isWSPRStationActive}")

                when (stationState)
                {
                    is WSPRStationState.WaitingForNextWindow ->
                        appendLine("  Next Window: ${stationState.windowInfo.humanReadableDescription}")
                    is WSPRStationState.Error ->
                        appendLine("  Error: ${stationState.errorDescription}")
                    else -> {}
                }

                val cycleInfo = _uiState.value.cycleInformation
                if (cycleInfo != null)
                {
                    appendLine("  Cycle Position: ${cycleInfo.cyclePositionSeconds}s / 120s")
                    appendLine("  Decode Window: ${if (cycleInfo.isDecodeWindowOpen) "OPEN" else "CLOSED"}")
                    appendLine("  Transmission Window: ${if (cycleInfo.isInTransmissionWindow) "ACTIVE" else "INACTIVE"}")
                }

                appendLine("  Successful Decodes: ${_uiState.value.decodeResults.size}")
                appendLine("  Decode Failures: ${_uiState.value.decodeFailures.size}")
            }
            else
            {
                appendLine("  WSPR Station: Not active")
            }
            appendLine()

            appendLine("TransmissionAndroid Serial:")
            val connectedSerialDevice = _uiState.value.connectedSerialDevice
            if (connectedSerialDevice != null)
            {
                appendLine("  Connected Device: ${connectedSerialDevice.device.deviceName}")
                appendLine("  Vendor ID: ${String.format("0x%04X", connectedSerialDevice.device.vendorId)}")
                appendLine("  Product ID: ${String.format("0x%04X", connectedSerialDevice.device.productId)}")
                appendLine("  Serial Connection: Active")
                appendLine("  Connection State: ${_uiState.value.serialConnectionState::class.simpleName}")
            }
            else
            {
                appendLine("  Serial Connection: None")
                appendLine("  Available Devices: ${_uiState.value.availableSerialDevices.size}")
            }
            appendLine("  Transmissions: ${_uiState.value.transmissionHistory.size}")
            appendLine()

            // Recent activity
            appendLine("Recent Activity:")
            _uiState.value.decodeResults.take(3).forEach { result ->
                appendLine("  RX: ${result.createSummaryLine()}")
            }
            _uiState.value.transmissionHistory.take(3).forEach { tx ->
                appendLine("  TX: ${tx.callsign} ${tx.gridSquare} ${tx.power}dBm at ${tx.timestamp}")
            }

            if (_uiState.value.decodeFailures.isNotEmpty()) {
                appendLine()
                appendLine("Recent Decode Failures:")
                _uiState.value.decodeFailures.take(5).forEach { failure ->
                    appendLine("  ${failure.timestamp}: ${failure.reason}")
                }
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
        if (trimmedCallsign.isEmpty()) return "Callsign cannot be empty"
        if (trimmedCallsign.length > 6) return "Callsign too long (maximum 6 characters)"
        if (!trimmedCallsign.matches(Regex("^[A-Z0-9/]+$"))) return "Callsign contains invalid characters"

        // Validate grid square
        val trimmedGridSquare = gridSquare.trim()
        if (trimmedGridSquare.isEmpty()) return "Grid square cannot be empty"
        if (trimmedGridSquare.length < 4) return "Grid square too short (minimum 4 characters)"
        if (trimmedGridSquare.length > 6) return "Grid square too long (maximum 6 characters)"
        if (!trimmedGridSquare.matches(Regex("^[A-Z]{2}[0-9]{2}[A-Z]{0,2}$"))) return "Invalid grid square format"

        // Validate power level
        if (power < 0 || power > 60) return "Power level must be between 0 and 60 dBm"

        val validPowerLevels = listOf(0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60)
        if (!validPowerLevels.contains(power)) {
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
                currentSerialConnection?.close()
                usbAudioManager.cleanup()
            }
            catch (exception: Exception)
            {
                Timber.e(exception, "Error during ViewModel cleanup")
            }
        }
    }


    // MARK: Test functions

    /**
     * Test function that sends a predetermined sequence of commands to the microcontroller.
     * Each command waits for a response before proceeding to the next one.
     */
    fun runCommandSequenceTest()
    {
        viewModelScope.launch {
            try
            {
                val connection = currentSerialConnection
                if (connection == null) {
                    Timber.w("No active connection for command sequence test")
                    return@launch
                }

                Timber.i("=== Starting Command Sequence Test ===")

                // Predefined command sequence
                val commandSequence = listOf(
                    "2",
                    "1",
                    "11NIFK",
                    "3",
                    "aa00aa",
                    "2",
                    "0",
                    "4",
                    "14095600",
                    "5",
                    "q",
                    "q",
                    "3",
                    "1"
                )

                var sequenceSuccess = true

                // Send each command and wait for response
                for ((index, command) in commandSequence.withIndex()) {
                    try {
                        Timber.i("Sequence [${index + 1}/${commandSequence.size}]: Sending '$command'")

                        // Send the command
                        val sendSuccess = withContext(Dispatchers.IO) {
                            connection.write(command)
                        }

                        if (!sendSuccess) {
                            Timber.e("Failed to send command: $command")
                            sequenceSuccess = false
                            break
                        }

                        Timber.d("→ Sent: $command")

                        // Wait for response with timeout
                        val response = waitForResponse(connection, timeoutMs = 2000)

                        if (response != null) {
                            Timber.d("← Response: $response")
                        } else {
                            Timber.w("No response received for command: $command")
                            // Continue anyway - some commands might not respond
                        }

                        // Brief pause between commands
                        delay(500) // 500ms delay between commands

                    } catch (e: Exception) {
                        Timber.e(e, "Error during command sequence at step ${index + 1}: $command")
                        sequenceSuccess = false
                        break
                    }
                }

                // Send final 'q' command after all responses received
                if (sequenceSuccess) {
                    Timber.i("Sequence complete, sending final 'q' command...")

                    try {
                        val finalSendSuccess = withContext(Dispatchers.IO) {
                            connection.write("q")
                        }

                        if (finalSendSuccess) {
                            Timber.d("→ Final command sent: q")

                            val finalResponse = waitForResponse(connection, timeoutMs = 2000)
                            if (finalResponse != null) {
                                Timber.d("← Final response: $finalResponse")
                            }

                            Timber.i("=== Command Sequence Test COMPLETED ===")
                        } else {
                            Timber.e("Failed to send final 'q' command")
                        }

                    } catch (e: Exception) {
                        Timber.e(e, "Error sending final command")
                    }
                } else {
                    Timber.e("=== Command Sequence Test FAILED ===")
                }

            } catch (e: Exception) {
                Timber.e(e, "Fatal error during command sequence test")
            }
        }
    }

    /**
     * Waits for a response from the serial connection with a specified timeout.
     *
     * @param connection The serial connection to read from
     * @param timeoutMs Timeout in milliseconds
     * @return The received response string, or null if timeout/error
     */
    private suspend fun waitForResponse(connection: SerialConnection, timeoutMs: Long): String? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val buffer = StringBuilder()
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        val data = connection.readAvailable()

                        if (data != null && data.isNotEmpty()) {
                            for (byte in data) {
                                val char = byte.toInt().toChar()

                                when {
                                    char == '\n' || char == '\r' -> {
                                        if (buffer.isNotEmpty()) {
                                            return@withTimeoutOrNull buffer.toString().trim()
                                        }
                                    }
                                    char.isISOControl().not() && char != '\u0000' -> {
                                        buffer.append(char)
                                    }
                                }
                            }

                            // If we got some data but no line ending yet, continue reading
                            if (buffer.isNotEmpty()) {
                                delay(10) // Small delay before checking for more data
                            }
                        } else {
                            delay(50) // No data available, wait a bit
                        }

                    } catch (e: Exception) {
                        Timber.w("Error while waiting for response: ${e.message}")
                        delay(100)
                    }
                }

                // Timeout - return partial data if any
                if (buffer.isNotEmpty()) {
                    buffer.toString().trim()
                } else {
                    null
                }
            }
        }
    }

    /**
     * Alternative version that waits for any response (not necessarily line-terminated)
     * Useful if your microcontroller doesn't send newlines
     */
    private suspend fun waitForAnyResponse(connection: SerialConnection, timeoutMs: Long): String? {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                val buffer = StringBuilder()
                val startTime = System.currentTimeMillis()
                var lastDataTime = startTime

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        val data = connection.readAvailable()

                        if (data != null && data.isNotEmpty()) {
                            lastDataTime = System.currentTimeMillis()

                            for (byte in data) {
                                val char = byte.toInt().toChar()
                                if (char.isISOControl().not() && char != '\u0000') {
                                    buffer.append(char)
                                }
                            }
                        } else {
                            // If we have data and haven't received more for 200ms, consider it complete
                            if (buffer.isNotEmpty() &&
                                System.currentTimeMillis() - lastDataTime > 200) {
                                return@withTimeoutOrNull buffer.toString().trim()
                            }
                            delay(50)
                        }

                    } catch (e: Exception) {
                        Timber.w("Error while waiting for any response: ${e.message}")
                        delay(100)
                    }
                }

                // Return whatever we got
                if (buffer.isNotEmpty()) {
                    buffer.toString().trim()
                } else {
                    null
                }
            }
        }
    }

}