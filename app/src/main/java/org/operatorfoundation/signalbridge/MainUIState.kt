package org.operatorfoundation.signalbridge

import android.content.Intent
import com.hoho.android.usbserial.driver.UsbSerialDriver
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.transmission.SerialConnectionFactory
import java.io.File


/**
 * UI state data class containing all information needed by the Demo app UI.
 * This integrates state from three libraries:
 * - SignalBridge (USB audio)
 * - AudioCoder (WSPR processing)
 * - TransmissionAndroid (Serial communication)
 */
data class MainUiState(
    // ========== USB Audio Device Management (SignalBridge) ==========
    val availableUsbDevices: List<UsbAudioDevice> = emptyList(),
    val connectedUsbDevice: UsbAudioDevice? = null,

    // Audio monitoring
    val audioLevel: AudioLevelInfo? = null,
    val isReceivingAudio: Boolean = false,

    // ========== WSPR Station Status (AudioCoder) ==========
    val isWSPRStationActive: Boolean = false,
    val stationState: WSPRStationState? = null,
    val cycleInformation: WSPRCycleInformation? = null,

    // WSPR Results and Failures
    val decodeResults: List<WSPRDecodeResult> = emptyList(),
    val decodeFailures: List<DecodeFailure> = emptyList(),

    // ========== Serial Device Management (TransmissionAndroid) ==========
    val availableSerialDevices: List<UsbSerialDriver> = emptyList(),
    val connectedSerialDevice: UsbSerialDriver? = null,
    val serialConnectionState: SerialConnectionFactory.ConnectionState = SerialConnectionFactory.ConnectionState.Disconnected,

    // ========== WSPR Transmission ==========
    val isTransmitting: Boolean = false,
    val transmissionStatus: String = "idle", // idle, generating, sending, transmitting, complete, error
    val transmissionHistory: List<TransmissionRecord> = emptyList(),

    // ========== File Management ==========
    val lastGeneratedFile: File? = null,
    val pendingShareIntent: Intent? = null,

    // ========== Status Messages ==========
    val statusMessage: String? = null,
    val errorMessage: String? = null
){
    /** True if there are any error conditions that need user attention */
    val hasErrors: Boolean
        get() = errorMessage != null || stationState is WSPRStationState.Error

    /** True if USB audio side is ready for receive operations */
    val isReadyForReceive: Boolean
        get() = connectedUsbDevice != null &&
                isWSPRStationActive &&
                !hasErrors

    /** True if serial side is ready for transmit operations */
    val isReadyForTransmit: Boolean
        get() = connectedSerialDevice != null &&
                serialConnectionState is SerialConnectionFactory.ConnectionState.Connected &&
                !hasErrors

    /** True if the system is ready for WSPR operations (either RX or TX or both) */
    val isReadyForOperation: Boolean
        get() = (isReadyForReceive || isReadyForTransmit)

    /** Human-readable summary of system state */
    val systemStatusSummary: String
        get() = when {
            hasErrors -> errorMessage ?: "Unknown error"
            isReadyForReceive && isReadyForTransmit -> "Both RX and TX ready - full WSPR station operational"
            isReadyForReceive -> "WSPR receive ready - connect custom radio for transmission"
            isReadyForTransmit -> "WSPR transmit ready - connect USB audio for receiving"
            isWSPRStationActive -> "WSPR station active - ${cycleInformation?.currentStateDescription ?: "monitoring"}"
            connectedUsbDevice != null -> "USB audio connected - starting WSPR station..."
            connectedSerialDevice != null -> "Custom radio connected - ready for WSPR transmission"
            else -> statusMessage ?: "Connect USB audio device or custom radio to begin"
        }

    /** True if a WSPR file is available for sharing */
    val hasFileToShare: Boolean
        get() = lastGeneratedFile != null && lastGeneratedFile.exists()

    /** Count of total WSPR activity (successful decodes + transmissions) */
    val totalWSPRActivity: Int
        get() = decodeResults.size + transmissionHistory.size

    /** Most recent activity status for quick reference */
    val recentActivitySummary: String
        get() = when
        {
            transmissionHistory.isNotEmpty() && decodeResults.isNotEmpty() ->
                "Last TX: ${transmissionHistory.first().timestamp} • Last RX: ${decodeResults.first().formattedDecodeTime}"

            transmissionHistory.isNotEmpty() ->
                "Last transmission: ${transmissionHistory.first().timestamp}"

            decodeResults.isNotEmpty() ->
                "Last decode: ${decodeResults.first().formattedDecodeTime}"

            else -> "No WSPR activity yet"
        }
}

/**
 * Extension functions for state management
 */

/** Extension to check if decode window timing is critical */
val MainUiState.isInCriticalDecodeWindow: Boolean
    get() = cycleInformation?.let { cycle ->
        cycle.isDecodeWindowOpen ||
                (cycle.cyclePositionSeconds >= 105 && !cycle.isDecodeWindowOpen) // 5 seconds before decode window
    } ?: false

/** Extension to get next important timing event */
val MainUiState.nextTimingEvent: String?
    get() = cycleInformation?.nextDecodeWindowInfo?.humanReadableDescription

/** Extension to check if system needs attention */
val MainUiState.needsAttention: Boolean
    get() = hasErrors ||
            (isWSPRStationActive && decodeFailures.size > 5) || // Many decode failures
            (connectedUsbDevice != null && !isReceivingAudio) || // USB connected but no audio
            (isTransmitting && transmissionStatus == "error") // Transmission failed

/** Extension to get attention reason */
val MainUiState.attentionReason: String?
    get() = when {
        hasErrors -> errorMessage
        decodeFailures.size > 5 -> "Multiple decode failures - check audio input"
        connectedSerialDevice != null && !isReceivingAudio -> "USB audio connected but no signal detected"
        isTransmitting && transmissionStatus == "error" -> "Transmission failed - check custom radio"
        else -> null
    }

/** Extension to get connection health status */
val MainUiState.connectionHealth: String
    get() = when {
        isReadyForReceive && isReadyForTransmit -> "Complete - Both RX and TX operational"
        isReadyForReceive -> "Receive Only - USB audio active"
        isReadyForTransmit -> "Transmit Only - Custom radio connected"
        connectedUsbDevice != null -> "USB Audio - Starting WSPR station"
        connectedSerialDevice != null -> "Serial Radio - Ready for commands"
        else -> "No devices connected"
    }
