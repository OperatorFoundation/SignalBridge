package org.operatorfoundation.signalbridge

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import org.operatorfoundation.audiocoder.*
import org.operatorfoundation.audiocoder.models.WSPRCycleInformation
import org.operatorfoundation.audiocoder.models.WSPRDecodeResult
import org.operatorfoundation.audiocoder.models.WSPRStationState
import org.operatorfoundation.signalbridge.models.AudioLevelInfo
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import org.operatorfoundation.signalbridge.ui.theme.SignalBridgeDemoTheme
import timber.log.Timber

/**
 * Main activity for the WSPR Station Demo application.
 *
 * This activity demonstrates the complete WSPR station functionality using:
 * - SignalBridge: USB audio device management
 * - AudioCoder: WSPR timing, processing, and decoding
 *
 * The UI is designed to show users:
 * - Current system status at a glance
 * - WSPR timing and cycle information
 * - Real-time decode results
 * - Device connection status
 * - Diagnostic information for troubleshooting
 */
class MainActivity : ComponentActivity()
{
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logging for debugging
        Timber.plant(Timber.DebugTree())

        setContent {
            SignalBridgeDemoTheme {
                WSPRStationDemoApp(viewModel = viewModel)
            }
        }
    }
}

/**
 * Main composable for the WSPR Station Demo application.
 * Handles permissions and displays the appropriate UI based on system state.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WSPRStationDemoApp(viewModel: MainViewModel)
{
    // Request audio recording permission
    val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // Collect UI state
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Handle pending share intent
    uiState.pendingShareIntent?.let { shareIntent ->
        LaunchedEffect(shareIntent) {

            // Start the share activity (errors handled in ViewModel)
            context.startActivity(shareIntent)

            // Clear the pending intent after starting the activity
            viewModel.clearPendingShareIntent()
        }
    }

    // Request permission if not granted
    LaunchedEffect(audioPermissionState.status.isGranted) {
        if (!audioPermissionState.status.isGranted)
        {
            audioPermissionState.launchPermissionRequest()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!audioPermissionState.status.isGranted)
        {
            // Show permission request UI
            PermissionRequiredScreen {
                audioPermissionState.launchPermissionRequest()
            }
        }
        else
        {
            // Show main WSPR station interface
            WSPRStationMainScreen(
                uiState = uiState,
                onConnectToDevice = { device -> viewModel.connectToDevice(device) },
                onDisconnect = { viewModel.disconnect() },
                onEncodeWSPR = { callsign, gridSquare, power -> viewModel.encodeWSPRSignal(callsign, gridSquare, power) },
                onShareLastFile = { viewModel.shareLastWSPRFile() },
                onGetDiagnostics = { viewModel.getDiagnosticInformation() }
            )
        }
    }
}

/**
 * Main screen for WSPR station operations.
 * Displays system status, device management, and decode results.
 */
@Composable
fun WSPRStationMainScreen(
    uiState: MainUiState,
    onConnectToDevice: (UsbAudioDevice) -> Unit,
    onDisconnect: () -> Unit,
    onEncodeWSPR: (String, String, Int) -> Unit,
    onShareLastFile: () -> Unit,
    onGetDiagnostics: () -> String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with app title and status
        WSPRStationHeader(uiState = uiState)

        // Main content area
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // System status card (always visible)
            item {
                SystemStatusCard(uiState = uiState)
            }

            // Device management section
            item {
                DeviceManagementSection(
                    uiState = uiState,
                    onConnectToDevice = onConnectToDevice,
                    onDisconnect = onDisconnect
                )
            }

            // Audio level monitoring (when connected)
            if (uiState.connectedDevice != null)
            {
                item {
                    AudioLevelIndicator(
                        audioLevel = uiState.audioLevel,
                        isReceivingAudio = uiState.isReceivingAudio
                    )
                }
            }

            // WSPR timing and controls (only when connected)
            if (uiState.isReadyForOperation)
            {
                item {
                    WSPRTimingCard(uiState = uiState)
                }
            }

            // WSPR encoding and file management (always available)
            item {
                WSPREncodingCard(
                    onEncodeWSPR = onEncodeWSPR,
                    onShareLastFile = onShareLastFile,
                    hasFileToShare = uiState.lastGeneratedFile != null
                )
            }

            // Decode results (only when we have results)
            item {
                DecodeResultsCard(results = uiState.decodeResults)
            }

            // Diagnostics section
            item {
                DiagnosticsCard(onGetDiagnostics = onGetDiagnostics)
            }
        }
    }
}

/**
 * Header section with app title and current system status.
 */
@Composable
fun WSPRStationHeader(uiState: MainUiState)
{
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.hasErrors)
            {
                MaterialTheme.colorScheme.errorContainer
            }
            else if (uiState.isReadyForOperation)
            {
                MaterialTheme.colorScheme.primaryContainer
            }
            else
            {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📡 WSPR Station Demo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.systemStatusSummary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (uiState.hasErrors) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * System status card showing current operational state.
 */
@Composable
fun SystemStatusCard(uiState: MainUiState)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Icon(
                    imageVector = when {
                        uiState.hasErrors -> Icons.Default.Close
                        uiState.isReadyForOperation -> Icons.Default.CheckCircle
                        else -> Icons.Default.Info
                    },
                    contentDescription = "Status",
                    tint = when {
                        uiState.hasErrors -> MaterialTheme.colorScheme.error
                        uiState.isReadyForOperation -> Color.Green
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Connection status
            StatusRow(
                label = "USB Connection",
                value = if (uiState.connectedDevice != null)
                {
                    "Connected to ${uiState.connectedDevice.displayName}"
                }
                else
                {
                    "Not connected"
                },
                isPositive = uiState.connectedDevice != null
            )

            // WSPR station status
            StatusRow(
                label = "WSPR Station",
                value = if (uiState.isWSPRStationActive)
                {
                    uiState.stationState?.let { state ->
                        when (state) {
                            is WSPRStationState.Running -> "Running"
                            is WSPRStationState.WaitingForNextWindow -> "Waiting for decode window"
                            is WSPRStationState.CollectingAudio -> "Collecting audio"
                            is WSPRStationState.ProcessingAudio -> "Processing signals"
                            is WSPRStationState.DecodeCompleted -> "Decode completed"
                            is WSPRStationState.Error -> "Error: ${state.errorDescription}"
                            else -> state::class.simpleName ?: "Unknown"
                        }
                    } ?: "Active"
                }
                else
                {
                    "Not active"
                },
                isPositive = uiState.isWSPRStationActive && uiState.stationState !is WSPRStationState.Error
            )

            // Recent activity
            if (uiState.decodeResults.isNotEmpty())
            {
                StatusRow(
                    label = "Recent Decodes",
                    value = "${uiState.decodeResults.size} signals decoded",
                    isPositive = true
                )
            }
        }
    }
}

/**
 * Device management section for connecting to USB audio devices.
 */
@Composable
fun DeviceManagementSection(
    uiState: MainUiState,
    onConnectToDevice: (UsbAudioDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "USB Audio Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.connectedDevice != null) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Disconnect",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
            }

            if (uiState.connectedDevice != null) {
                // Show connected device info
                ConnectedDeviceInfo(device = uiState.connectedDevice)
            } else {
                // Show available devices
                AvailableDevicesList(
                    devices = uiState.availableDevices,
                    onConnectToDevice = onConnectToDevice
                )
            }
        }
    }
}

/**
 * WSPR timing information and manual controls.
 */
@Composable
fun WSPRTimingCard(
    uiState: MainUiState
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WSPR Timing & Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            uiState.cycleInformation?.let { cycleInfo ->
                WSPRCycleDisplay(cycleInfo = cycleInfo)
            }
        }
    }
}

/**
 * Decode results display with detailed information.
 */
@Composable
fun DecodeResultsCard(results: List<WSPRDecodeResult>)
{
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "WSPR Decodes (${results.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (results.isEmpty())
            {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    )
                    {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "No Signals",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "No WSPR signals decoded yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Station is listening for WSPR transmissions...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            else
            {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results.take(10)) { result ->
                        WSPRDecodeResultItem(result = result)
                    }
                }
            }
        }
    }
}

/**
 * Individual WSPR decode result item.
 */
@Composable
fun WSPRDecodeResultItem(result: WSPRDecodeResult)
{
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${result.callsign} (${result.gridSquare})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "${result.powerLevelDbm}dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = result.signalQualityDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = result.displayFrequency,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * WSPR cycle timing display with progress indicator.
 */
@Composable
fun WSPRCycleDisplay(cycleInfo: WSPRCycleInformation)
{
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Progress bar for cycle position
        LinearProgressIndicator(
            progress = cycleInfo.cycleProgressPercentage,
            modifier = Modifier.fillMaxWidth()
        )

        // Cycle information
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Cycle: ${cycleInfo.cyclePositionSeconds}s / 120s",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = if (cycleInfo.isDecodeWindowOpen) {
                    "🔍 Decode Window Open"
                } else if (cycleInfo.isTransmissionActive) {
                    "📡 Transmission Active"
                } else {
                    "🔇 Silent Period"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (cycleInfo.isDecodeWindowOpen) {
                    Color.Green
                } else if (cycleInfo.isTransmissionActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        // Next window information
        Text(
            text = cycleInfo.nextDecodeWindowInfo.humanReadableDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Connected device information display.
 */
@Composable
fun ConnectedDeviceInfo(device: UsbAudioDevice)
{
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Connected Device",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = "Device ID: ${device.deviceId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * List of available USB audio devices.
 */
@Composable
fun AvailableDevicesList(
    devices: List<UsbAudioDevice>,
    onConnectToDevice: (UsbAudioDevice) -> Unit
) {
    if (devices.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "No Devices",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "No USB audio devices found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Connect a USB audio device to begin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            devices.forEach { device ->
                DeviceListItem(
                    device = device,
                    onConnect = { onConnectToDevice(device) }
                )
            }
        }
    }
}

/**
 * Individual device item in the available devices list.
 */
@Composable
fun DeviceListItem(
    device: UsbAudioDevice,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = "USB Device",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "ID: ${device.deviceId} | Vendor: ${device.vendorId} | Product: ${device.productId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onConnect,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Connect")
            }
        }
    }
}

/**
 * WSPR encoding card for generating and sharing WSPR signals.
 */
@Composable
fun WSPREncodingCard(
    onEncodeWSPR: (String, String, Int) -> Unit,
    onShareLastFile: () -> Unit,
    hasFileToShare: Boolean
) {
    var callsign by remember { mutableStateOf("Q0QQQ") }
    var gridSquare by remember { mutableStateOf("FN20") }
    var power by remember { mutableStateOf("30") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "WSPR Signal Generator",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Generate WSPR audio signals for testing and transmission",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Input fields
            OutlinedTextField(
                value = callsign,
                onValueChange = { callsign = it.uppercase() },
                label = { Text("Callsign") },
                placeholder = { Text("W1AW") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = gridSquare,
                onValueChange = { gridSquare = it.uppercase() },
                label = { Text("Grid Square") },
                placeholder = { Text("FN31") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = power,
                onValueChange = { power = it },
                label = { Text("Power (dBm)") },
                placeholder = { Text("30") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val powerInt = power.toIntOrNull() ?: 30
                        onEncodeWSPR(callsign, gridSquare, powerInt)
                    },
                    enabled = callsign.isNotBlank() && gridSquare.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Generate",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate WAV")
                }

                Button(
                    onClick = onShareLastFile,
                    enabled = hasFileToShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share File")
                }
            }

            // Info about generated files
            if (hasFileToShare) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Generated File",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "WSPR file generated and ready to share",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AudioLevelIndicator(audioLevel: AudioLevelInfo?, isReceivingAudio: Boolean)
{
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReceivingAudio)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    )
    {
        Column(modifier = Modifier.padding(12.dp))
        {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            )
            {
                Text("Audio Input")
                Text(if (isReceivingAudio) "🔊 ACTIVE" else "🔇 SILENT")
            }

            audioLevel?.let { level ->
                LinearProgressIndicator(
                    progress = level.currentLevel,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Level: ${(level.currentLevel * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun DiagnosticsCard(onGetDiagnostics: () -> String)
{
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        diagnosticsText = onGetDiagnostics()
                        showDiagnostics = !showDiagnostics
                    }
                ) {
                    Icon(
                        imageVector = if (showDiagnostics) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (showDiagnostics) "Hide" else "Show",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (showDiagnostics) "Hide" else "Show Diagnostics")
                }
            }

            if (showDiagnostics) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = diagnosticsText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Status row component for displaying labeled status information.
 */
@Composable
fun StatusRow(
    label: String,
    value: String,
    isPositive: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPositive) Color.Green else MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Permission request screen displayed when audio permission is needed.
 */
@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit)
{
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Microphone Permission",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Audio permission required...",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "This app needs audio recording permission to capture and process WSPR signals from USB audio devices.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Permission")
        }
    }
}