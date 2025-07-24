package org.operatorfoundation.signalbridge

import android.content.Intent
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.RecordingState
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import java.io.File


/**
 * UI state data class containing all the information needed by the UI.
 */
data class MainUiState(
    // Device Management
    val availableDevices: List<UsbAudioDevice> = emptyList(),
    val connectedDevice: UsbAudioDevice? = null,

    // Audio monitoring
    val audioLevel: AudioLevelInfo? = null,
    val isReceivingAudio: Boolean = false,

    // WSPR Station Status
    val isWSPRStationActive: Boolean = false,
    val stationState: WSPRStationState? = null,
    val cycleInformation: WSPRCycleInformation? = null,

    // Results and Messages
    val decodeResults: List<WSPRDecodeResult> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,

    // File Management
    val lastGeneratedFile: File? = null,
    val pendingShareIntent: Intent? = null
){
    /** True if there are any error conditions that need user attention */
    val hasErrors: Boolean
        get() = errorMessage != null || stationState is WSPRStationState.Error

    /** True if the system is ready for WSPR operations. */
    val isReadyForOperation: Boolean
        get() = connectedDevice != null && isWSPRStationActive && !hasErrors

    /** Human-readable summary of system state */
    val systemStatusSummary: String
        get() = when {
            hasErrors -> errorMessage ?: "Unknown error"
            !isReadyForOperation -> "Not ready - ${statusMessage ?: "Unknown status"}"
            cycleInformation != null -> cycleInformation.currentStateDescription
            else -> statusMessage ?: "System active"
        }

    /** True if a WSPR file is available for sharing */
    val hasFileToShare: Boolean
        get() = lastGeneratedFile != null && lastGeneratedFile.exists()
}
