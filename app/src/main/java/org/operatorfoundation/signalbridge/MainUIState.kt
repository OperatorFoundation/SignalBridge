package org.operatorfoundation.signalbridge

import android.content.Intent
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.RecordingState
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import java.io.File


/**
 * UI state data class containing all the information needed by the UI.
 */
data class MainUiState(
    val availableDevices: List<UsbAudioDevice> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val connectedDevice: UsbAudioDevice? = null,
    val recordingState: RecordingState = RecordingState.Stopped,
    val isPlaybackEnabled: Boolean = false,
    val audioLevel: AudioLevelInfo? = null,
    val errorMessage: String? = null,
    val diagnosticResults: String? = null,
    val wsprLevel: AudioLevelInfo? = null,
    val wsprResults: List<WSPRDecodeResult> = emptyList(),
    val lastWsprFile: File? = null,
    val pendingShareIntent: Intent? = null
)
