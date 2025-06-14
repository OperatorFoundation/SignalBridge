package org.operatorfoundation.signalbridgedemo

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.ConnectionStatus.Connected
import org.operatorfoundation.signalbridge.models.ConnectionStatus.Connecting
import org.operatorfoundation.signalbridge.models.ConnectionStatus.Disconnected
import org.operatorfoundation.signalbridge.models.RecordingState
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.signalbridgedemo.ui.theme.SignalBridgeDemoTheme
import timber.log.Timber

/**
 * Main activity for the USB Audio Library demo application.
 *
 * This activity demonstrates the core functionality of the USB Audio Library,
 * including device discovery, connection management, and audio recording.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO: Move this
        // Initialize logging
        Timber.plant(Timber.DebugTree())

        setContent {
            SignalBridgeDemoTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        viewModel.cleanup()
    }
}

/**
 * Main screen composable that displays the USB audio demo interface.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel)
{
    // Request audio recording permission
    val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // Collect UI state
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Request permission if not granted
    LaunchedEffect(audioPermissionState.status.isGranted)
    {
        if (!audioPermissionState.status.isGranted)
        {
            audioPermissionState.launchPermissionRequest()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    )
    {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        )
        {
            // Header
            item {
                Text(
                    text = "USB Audio Library Demo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Permission status
            if (!audioPermissionState.status.isGranted)
            {
                item {
                    PermissionRequiredCard {
                        audioPermissionState.launchPermissionRequest()
                    }
                }
            }
            else
            {
                // Main content
                item {
                    DeviceDiscoverySection(
                        devices = uiState.availableDevices,
                        onConnectToDevice = { device -> viewModel.connectToDevice(device) }
                    )
                }

                item {
                    ConnectionStatusSection(
                        connectionStatus = uiState.connectionStatus,
                        connectedDevice = uiState.connectedDevice,
                        onConnect = { device -> viewModel.connectToDevice(device) },
                        onDisconnect = { viewModel.disconnect() }
                    )
                }

                if (uiState.connectedDevice != null) {
                    item {
                        AudioControlSection(
                            recordingState = uiState.recordingState,
                            audioLevel = uiState.audioLevel,
                            isPlaybackEnabled = uiState.isPlaybackEnabled,
                            onStartRecording = { viewModel.startRecording() },
                            onStopRecording = { viewModel.stopRecording() },
                            onTogglePlayback = { viewModel.togglePlayback() }
                        )
                    }

                    //  Add diagnostic section
                    item {
                        DiagnosticSection(
                            onRunDiagnostics = { viewModel.runAudioRecordDiagnostics() },
                            onTestSystemAudio = { viewModel.testSystemAudio() }
                        )
                    }
                }

                //  Display diagnostic results
                uiState.diagnosticResults?.let { results ->
                    item {
                        DiagnosticResultsCard(
                            results = results,
                            onDismiss = { viewModel.clearDiagnostics() }
                        )
                    }
                }

                // Error display
                uiState.errorMessage?.let { error ->
                    item {
                        ErrorCard(
                            message = error,
                            onDismiss = { viewModel.clearError() }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card displayed when audio permission is required.
 */
@Composable
fun PermissionRequiredCard(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Audio Permission Required",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "This app needs audio recording permission to capture audio from USB devices.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Grant Permission")
            }
        }
    }
}

/**
 * Section for device discovery and listing.
 */
@Composable
fun DeviceDiscoverySection(
    devices: List<UsbAudioDevice>,
    onConnectToDevice: (UsbAudioDevice) -> Unit
)
{
    Card(modifier = Modifier.fillMaxWidth())
    {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        )
        {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            )
            {
                Text(
                    text = "Available USB Audio Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (devices.isEmpty())
            {
                Text(
                    text = "No USB audio devices found. Connect a USB audio device and tap Refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else
            {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 200.dp)
                )
                {
                    items(devices) { device ->
                        DeviceItem(device = device, onClick = onConnectToDevice)
                    }
                }
            }
        }
    }
}

/**
 * Individual device item in the device list.
 */
@Composable
fun DeviceItem(device: UsbAudioDevice, onClick: (UsbAudioDevice) -> Unit)
{
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(device) },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Vendor ID: ${device.vendorId}, Product ID: ${device.productId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Section showing connection status and controls.
 */
@Composable
fun ConnectionStatusSection(
    connectionStatus: ConnectionStatus,
    connectedDevice: UsbAudioDevice?,
    onConnect: (UsbAudioDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connection Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = when (connectionStatus) {
                            is Disconnected -> "Disconnected"
                            is Connecting -> "Connecting..."
                            is Connected -> "Connected"
                            is ConnectionStatus.Error -> "Error"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (connectionStatus) {
                            is Connected -> Color.Green
                            is ConnectionStatus.Error -> Color.Red
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    connectedDevice?.let { device ->
                        Text(
                            text = device.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (connectedDevice != null) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

/**
 * Section for audio recording controls and level monitoring.
 */
@Composable
fun AudioControlSection(
    recordingState: RecordingState,
    audioLevel: AudioLevelInfo?,
    isPlaybackEnabled: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTogglePlayback: () -> Unit
)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Audio Recording",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Recording status
            Text(
                text = when (recordingState) {
                    is RecordingState.Stopped -> "Stopped"
                    is RecordingState.Starting -> "Starting..."
                    is RecordingState.Recording -> "Recording"
                    is RecordingState.Stopping -> "Stopping..."
                    is RecordingState.Error -> "Error: ${recordingState.message}"
                    else -> "Error"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (recordingState) {
                    is RecordingState.Recording -> Color.Green
                    is RecordingState.Error -> Color.Red
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )

            // Audio level display
            audioLevel?.let { level ->
                AudioLevelDisplay(level = level)
            }

            // Recording controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStartRecording,
                    enabled = recordingState is RecordingState.Stopped,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Recording")
                }

                Button(
                    onClick = onStopRecording,
                    enabled = recordingState is RecordingState.Recording,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Stop Recording")
                }
            }

            // Playback control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback through speakers:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )

                Switch(
                    checked = isPlaybackEnabled,
                    onCheckedChange = { onTogglePlayback() },
                    enabled = true
                )
            }

            if (isPlaybackEnabled)
            {
                Text(
                    text = "🔊 Audio from USB device will play through phone speakers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Debug info section
            if (isPlaybackEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Yellow.copy(alpha = 0.1f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "🔧 Playback Debug Info",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Toggle: ON | Recording: ${recordingState::class.simpleName}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Audio Level: ${audioLevel?.currentLevel?.times(100)?.toInt() ?: 0}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Display component for audio level visualization.
 */
@Composable
fun AudioLevelDisplay(level: AudioLevelInfo) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Audio Levels",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )

        // Current level bar
        LevelBar(
            label = "Current",
            level = level.currentLevel,
            color = if (level.isClipping()) Color.Red else MaterialTheme.colorScheme.primary
        )

        // Peak level bar
        LevelBar(
            label = "Peak",
            level = level.peakLevel,
            color = MaterialTheme.colorScheme.secondary
        )

        // Average level bar
        LevelBar(
            label = "Average",
            level = level.averageLevel,
            color = MaterialTheme.colorScheme.tertiary
        )
    }
}

/**
 * Individual level bar component.
 */
@Composable
fun LevelBar(
    label: String,
    level: Float,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(60.dp)
        )

        Box(
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Background bar
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {}

            // Level indicator
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(level.coerceIn(0f, 1f)),
                color = color,
                shape = MaterialTheme.shapes.small
            ) {}
        }

        Text(
            text = "${(level * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(40.dp)
        )
    }
}

/**
 *  Diagnostic section for testing AudioRecord compatibility
 */
@Composable
fun DiagnosticSection(
    onRunDiagnostics: () -> Unit,
    onTestSystemAudio: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = " AudioRecord Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Test if AudioRecord can access the connected USB audio device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onRunDiagnostics,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run AudioRecord Compatibility Test")
            }

            Button(
                onClick = onTestSystemAudio,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🔊 Test System Audio (440Hz Tone)")
            }
        }
    }
}

/**
 *  Display diagnostic results
 */
@Composable
fun DiagnosticResultsCard(
    results: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (results.contains("✅ YES")) {
                Color.Green.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AudioRecord Compatibility Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }

            Text(
                text = results,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )

            if (results.contains("✅ YES")) {
                Text(
                    text = "🎉 SUCCESS: AudioRecord can access USB audio!",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Green
                )
            } else {
                Text(
                    text = "⚠️ AudioRecord cannot access USB audio. Alternative approaches may be needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Red
                )
            }
        }
    }
}


/**
 * Error display card.
 */
@Composable
fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Dismiss")
            }
        }
    }
}