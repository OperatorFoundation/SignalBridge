package org.operatorfoundation.signalbridge

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
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
import org.operatorfoundation.signalbridge.ui.theme.WSPRColors
import org.operatorfoundation.transmission.SerialConnectionFactory
import com.hoho.android.usbserial.driver.UsbSerialDriver
import timber.log.Timber

/**
 * Main activity for the WSPR Station Demo application.
 *
 * This activity integrates three libraries:
 * - SignalBridge: USB audio device management and audio input
 * - AudioCoder: WSPR timing, processing, and decoding
 * - TransmissionAndroid: Serial communication with custom radio hardware
 *
 * The UI displays:
 * - USB audio input with level monitoring
 * - WSPR timing information and cycle status
 * - Decode results and failure analysis
 * - Serial device connection and WSPR transmission
 */
class MainActivity : ComponentActivity()
{
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize logging for debugging
        Timber.plant(Timber.DebugTree())

        setContent {
            SignalBridgeDemoTheme() {
                WSPRStationDemoApp(viewModel = viewModel)
            }
        }
    }
}

/**
 * Main composable for the WSPR Station Demo application.
 * Handles permissions and displays the UI based on system state.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WSPRStationDemoApp(viewModel: MainViewModel)
{
    // Request audio recording permission
    val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)

    // Collect UI state from the ViewModel
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Handle pending share intent for WSPR file sharing
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
        if (!audioPermissionState.status.isGranted) {
            audioPermissionState.launchPermissionRequest()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!audioPermissionState.status.isGranted) {
            // Show permission request UI
            PermissionRequiredScreen {
                audioPermissionState.launchPermissionRequest()
            }
        } else {
            // Show main WSPR station interface
            WSPRStationMainScreen(
                uiState = uiState,
                // USB Audio actions
                onConnectToUsbDevice = { device -> viewModel.connectToUSBAudioDevice(device) },
                onDisconnectUsbAudio = { viewModel.disconnectUSBAudio() },
                // Serial Radio actions
                onConnectToSerialDevice = { device -> viewModel.connectToSerialDevice(device) },
                onDisconnectSerial = { viewModel.disconnectSerial() },
                // WSPR operations
                onEncodeWSPR = { callsign, gridSquare, power -> viewModel.encodeWSPRSignal(callsign, gridSquare, power) },
                onShareLastFile = { viewModel.shareLastWSPRFile() },
                onTriggerManualDecode = { viewModel.triggerManualDecode() },
                // Diagnostics
                onGetDiagnostics = { viewModel.getDiagnosticInformation() }
            )
        }
    }
}

/**
 * Main screen for WSPR station operations.
 * Two-column layout with receive and transmit sections.
 */
@Composable
fun WSPRStationMainScreen(
    uiState: MainUiState,
    onConnectToUsbDevice: (UsbAudioDevice) -> Unit,
    onDisconnectUsbAudio: () -> Unit,
    onConnectToSerialDevice: (UsbSerialDriver) -> Unit,
    onDisconnectSerial: () -> Unit,
    onEncodeWSPR: (String, String, Int) -> Unit,
    onShareLastFile: () -> Unit,
    onTriggerManualDecode: () -> Unit,
    onGetDiagnostics: () -> String
) {
    // Color palette from theme
    val colors = WSPRColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with gradient background
        WSPRStationHeader(uiState = uiState, colors = colors)

        // System status dashboard - 4 key metrics
        SystemStatusDashboard(uiState = uiState, colors = colors)

        // Main content in scrollable column for phone compatibility
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // USB Audio Receive Section
            item {
                UsbAudioReceiveSection(
                    uiState = uiState,
                    colors = colors,
                    onConnectToUsbDevice = onConnectToUsbDevice,
                    onDisconnectUsbAudio = onDisconnectUsbAudio
                )
            }

            // Audio level monitoring (when USB connected)
            if (uiState.connectedUsbDevice != null) {
                item {
                    AudioLevelMonitoringCard(
                        audioLevel = uiState.audioLevel,
                        isReceivingAudio = uiState.isReceivingAudio,
                        colors = colors
                    )
                }
            }

            // WSPR timing and status (when WSPR station active)
            if (uiState.isWSPRStationActive) {
                item {
                    WSPRTimingAndStatusCard(
                        cycleInformation = uiState.cycleInformation,
                        stationState = uiState.stationState,
                        colors = colors,
                        onTriggerManualDecode = onTriggerManualDecode
                    )
                }
            }

            // WSPR decode results and failures
            if (uiState.isWSPRStationActive) {
                item {
                    WSPRActivityCard(
                        decodeResults = uiState.decodeResults,
                        decodeFailures = uiState.decodeFailures,
                        colors = colors
                    )
                }
            }

            // Serial Radio Transmit Section
            item {
                SerialRadioTransmitSection(
                    uiState = uiState,
                    colors = colors,
                    onConnectToSerialDevice = onConnectToSerialDevice,
                    onDisconnectSerial = onDisconnectSerial,
                    onEncodeWSPR = onEncodeWSPR,
                    onShareLastFile = onShareLastFile
                )
            }

            // Transmission history (when available)
            if (uiState.transmissionHistory.isNotEmpty()) {
                item {
                    TransmissionHistoryCard(
                        transmissionHistory = uiState.transmissionHistory,
                        colors = colors
                    )
                }
            }

            // Diagnostics section (collapsible)
            item {
                DiagnosticsCard(
                    onGetDiagnostics = onGetDiagnostics,
                    colors = colors
                )
            }
        }
    }
}

/**
 * Header with app title and system status summary.
 */
@Composable
fun WSPRStationHeader(uiState: MainUiState, colors: WSPRColors)
{
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                uiState.hasErrors -> colors.error
                uiState.isReadyForOperation -> colors.primary
                else -> colors.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Call, // FIXME: Radio icon?
                    contentDescription = "WSPR Station",
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "WSPR Station Demo",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "WSPR receive and transmit system",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.9f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.systemStatusSummary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

/**
 * System status dashboard showing key metrics.
 */
@Composable
fun SystemStatusDashboard(uiState: MainUiState, colors: WSPRColors)
{
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // USB Audio Status
        StatusCard(
            title = "USB Audio",
            value = if (uiState.connectedUsbDevice != null) "Connected" else "Disconnected",
            icon = Icons.Default.Phone,
            isPositive = uiState.connectedUsbDevice != null,
            colors = colors,
            modifier = Modifier.weight(1f)
        )

        // Serial Radio Status
        StatusCard(
            title = "Serial Radio",
            value = when (uiState.serialConnectionState) {
                is SerialConnectionFactory.ConnectionState.Connected -> "Connected"
                is SerialConnectionFactory.ConnectionState.Connecting -> "Connecting"
                is SerialConnectionFactory.ConnectionState.RequestingPermission -> "Requesting Permission"
                is SerialConnectionFactory.ConnectionState.Error -> "Error"
                else -> "Disconnected"
            },
            icon = Icons.Default.FavoriteBorder,
            isPositive = uiState.serialConnectionState is SerialConnectionFactory.ConnectionState.Connected,
            colors = colors,
            modifier = Modifier.weight(1f)
        )

        // Activity Counter
        StatusCard(
            title = "RX/TX",
            value = "${uiState.decodeResults.size}/${uiState.transmissionHistory.size}",
            icon = Icons.Default.Notifications, // FIXME: Icon
            isPositive = uiState.decodeResults.isNotEmpty() || uiState.transmissionHistory.isNotEmpty(),
            colors = colors,
            modifier = Modifier.weight(1f)
        )

        // System Status
        StatusCard(
            title = "Status",
            value = when {
                uiState.hasErrors -> "Error"
                uiState.isReadyForReceive && uiState.isReadyForTransmit -> "Complete"
                uiState.isReadyForReceive -> "RX Ready"
                uiState.isReadyForTransmit -> "TX Ready"
                uiState.connectedUsbDevice != null || uiState.connectedSerialDevice != null -> "Partial"
                else -> "Ready"
            },
            icon = when {
                uiState.hasErrors -> Icons.Default.Warning
                uiState.isReadyForOperation -> Icons.Default.CheckCircle
                else -> Icons.Default.Info
            },
            isPositive = uiState.isReadyForOperation && !uiState.hasErrors,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Individual status card component.
 */
@Composable
fun StatusCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPositive: Boolean,
    colors: WSPRColors,
    modifier: Modifier = Modifier
)
{
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colors.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(20.dp),
                tint = if (isPositive) colors.success else colors.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )

            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (isPositive) colors.success else colors.onSurface
            )
        }
    }
}

/**
 * Color scheme for WSPR station interface - now integrated into theme system.
 */
@Composable
fun WSPRColors() = WSPRColors

// Remaining composable functions for the complete UI implementation

/**
 * USB Audio receive section with device connection management.
 */
@Composable
fun UsbAudioReceiveSection(
    uiState: MainUiState,
    colors: WSPRColors,
    onConnectToUsbDevice: (UsbAudioDevice) -> Unit,
    onDisconnectUsbAudio: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
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
                    text = "📻 WSPR Receive (USB Audio)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.connectedUsbDevice != null) {
                    Button(
                        onClick = onDisconnectUsbAudio,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                    ) {
                        Text("Disconnect", color = Color.White)
                    }
                }
            }

            if (uiState.connectedUsbDevice == null) {
                if (uiState.availableUsbDevices.isEmpty()) {
                    Text(
                        text = "No USB audio devices found. Connect a USB audio device to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Available USB audio devices:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    uiState.availableUsbDevices.forEach { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = device.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Device ID: ${device.deviceId}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = { onConnectToUsbDevice(device) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                                ) {
                                    Text("Connect", color = Color.White)
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colors.success.copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = colors.success
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "Connected: ${uiState.connectedUsbDevice.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.success
                        )
                    }
                }
            }
        }
    }
}

/**
 * Audio level monitoring display.
 */
@Composable
fun AudioLevelMonitoringCard(
    audioLevel: AudioLevelInfo?,
    isReceivingAudio: Boolean,
    colors: WSPRColors
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReceivingAudio) {
                colors.success.copy(alpha = 0.1f)
            } else {
                colors.surface
            }
        )
    ) {
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
                    text = "Audio Input Levels",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star, // FIXME: Icon
                        contentDescription = "Audio",
                        tint = if (isReceivingAudio) colors.success else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isReceivingAudio) "RECEIVING" else "SILENT",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isReceivingAudio) colors.success else colors.onSurfaceVariant
                    )
                }
            }

            audioLevel?.let { level ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Current level
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Current Level",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            text = "${(level.currentLevel * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }

                    LinearProgressIndicator(
                        progress = level.currentLevel,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (level.currentLevel > 0.8f) colors.warning else colors.success
                    )

                    // Peak hold
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Peak Hold",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            text = "${(level.peakLevel * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }

                    LinearProgressIndicator(
                        progress = level.peakLevel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = colors.accent
                    )
                }
            }
        }
    }
}

/**
 * WSPR timing and status information with collapsible details.
 */
@Composable
fun WSPRTimingAndStatusCard(
    cycleInformation: WSPRCycleInformation?,
    stationState: WSPRStationState?,
    colors: WSPRColors,
    onTriggerManualDecode: () -> Unit
) {
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
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
                    text = "WSPR Timing & Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onTriggerManualDecode,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.secondary)
                    ) {
                        Text("Manual Decode", color = Color.White)
                    }

                    Button(
                        onClick = { showDetails = !showDetails },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (showDetails) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (showDetails) "Hide Details" else "Show Details",
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
            }

            cycleInformation?.let { cycle ->
                // Always visible timing summary
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Cycle Progress",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "${cycle.cyclePositionSeconds}s / 120s",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface
                        )
                    }

                    LinearProgressIndicator(
                        progress = cycle.cycleProgressPercentage,
                        modifier = Modifier.fillMaxWidth(),
                        color = when {
                            cycle.isDecodeWindowOpen -> colors.accent
                            cycle.isTransmissionActive -> colors.primary
                            else -> colors.secondary
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = cycle.currentStateDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                cycle.isDecodeWindowOpen -> colors.accent
                                cycle.isTransmissionActive -> colors.primary
                                else -> colors.secondary
                            }
                        )

                        stationState?.let { state ->
                            Text(
                                text = state::class.simpleName?.replace("([A-Z])".toRegex(), " $1")?.trim() ?: "Unknown",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }

                // Expandable details
                if (showDetails) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "Decode Window",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        text = if (cycle.isDecodeWindowOpen) "OPEN" else "CLOSED",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (cycle.isDecodeWindowOpen) colors.accent else colors.onSurface
                                    )
                                }

                                Column {
                                    Text(
                                        text = "Next Window",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${cycle.nextDecodeWindowInfo.secondsUntilWindow}s",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurface
                                    )
                                }
                            }

                            Text(
                                text = cycle.nextDecodeWindowInfo.humanReadableDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * WSPR activity display with decode results and failures.
 */
@Composable
fun WSPRActivityCard(
    decodeResults: List<WSPRDecodeResult>,
    decodeFailures: List<DecodeFailure>,
    colors: WSPRColors
) {
    var showFailures by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
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
                    text = "WSPR Activity (${decodeResults.size} decodes, ${decodeFailures.size} failures)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (decodeFailures.isNotEmpty()) {
                    Button(
                        onClick = { showFailures = !showFailures },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (showFailures) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (showFailures) "Hide Failures" else "Show Failures",
                            tint = colors.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showFailures) "Hide Failures" else "Show Failures",
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            if (decodeResults.isEmpty() && decodeFailures.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Listening",
                        modifier = Modifier.size(32.dp),
                        tint = colors.onSurfaceVariant
                    )
                    Text(
                        text = "Listening for WSPR signals...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            } else {
                // Successful decodes
                if (decodeResults.isNotEmpty()) {
                    Text(
                        text = "Recent Decodes:",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.success
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(decodeResults.take(6)) { result ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${result.callsign} ${result.gridSquare} ${result.powerLevelDbm}dBm",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = result.displayFrequency,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "SNR: ${result.signalQualityDescription}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Decode failures (expandable)
                if (showFailures && decodeFailures.isNotEmpty()) {
                    Text(
                        text = "Recent Failures:",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.warning
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(decodeFailures.take(8)) { failure ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = colors.warning.copy(alpha = 0.1f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = failure.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = failure.timestamp,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Cycle: ${failure.cyclePosition}s • Audio: ${failure.audioLevel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Serial radio transmit section with device connection and WSPR generation.
 */
@Composable
fun SerialRadioTransmitSection(
    uiState: MainUiState,
    colors: WSPRColors,
    onConnectToSerialDevice: (UsbSerialDriver) -> Unit,
    onDisconnectSerial: () -> Unit,
    onEncodeWSPR: (String, String, Int) -> Unit,
    onShareLastFile: () -> Unit
) {
    var callsign by remember { mutableStateOf("K1ABC") }
    var gridSquare by remember { mutableStateOf("FN31pr") }
    var power by remember { mutableStateOf("30") }

    val validWSPRPowers = listOf(0, 3, 7, 10, 13, 17, 20, 23, 27, 30, 33, 37, 40, 43, 47, 50, 53, 57, 60)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
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
                    text = "📡 WSPR Transmit (Serial Radio)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.connectedSerialDevice != null) {
                    Button(
                        onClick = onDisconnectSerial,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.error)
                    ) {
                        Text("Disconnect", color = Color.White)
                    }
                }
            }

            // Serial device connection
            if (uiState.connectedSerialDevice == null) {
                if (uiState.availableSerialDevices.isEmpty()) {
                    Text(
                        text = "No serial devices found. Connect your custom WSPR radio via USB.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Available serial devices:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    uiState.availableSerialDevices.forEach { driver ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = driver.device.deviceName ?: "Unknown Device",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "VID: ${String.format("0x%04X", driver.device.vendorId)} PID: ${String.format("0x%04X", driver.device.productId)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = { onConnectToSerialDevice(driver) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                                ) {
                                    Text("Connect", color = Color.White)
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = colors.success.copy(alpha = 0.2f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            tint = colors.success
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "Connected: ${uiState.connectedSerialDevice.device.deviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.success
                        )
                    }
                }

                // WSPR generation controls (always available)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "WSPR Signal Generation:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = if (uiState.connectedSerialDevice != null) {
                        "Generate WSPR signal and transmit via connected radio"
                    } else {
                        "Generate WSPR signal and save to file for sharing"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )

                // Input fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = callsign,
                        onValueChange = { callsign = it.uppercase() },
                        label = { Text("Callsign") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = gridSquare,
                        onValueChange = { gridSquare = it.uppercase() },
                        label = { Text("Grid Square") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // Power selection and buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = power,
                        onValueChange = { /* Handle dropdown only */ },
                        label = { Text("Power (dBm)") },
                        modifier = Modifier.weight(1f),
                        readOnly = true,
                        trailingIcon = {
                            DropdownMenu(
                                expanded = false,
                                onDismissRequest = { }
                            ) {
                                validWSPRPowers.forEach { powerValue ->
                                    DropdownMenuItem(
                                        text = { Text("$powerValue") },
                                        onClick = { power = powerValue.toString() }
                                    )
                                }
                            }
                        }
                    )

                    Button(
                        onClick = {
                            val powerInt = power.toIntOrNull() ?: 30
                            onEncodeWSPR(callsign, gridSquare, powerInt)
                        },
                        enabled = !uiState.isTransmitting && callsign.isNotBlank() && gridSquare.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isTransmitting) {
                            Text("Processing...", color = Color.White)
                        } else if (uiState.connectedSerialDevice != null) {
                            Text("Generate & Transmit", color = Color.White)
                        } else {
                            Text("Generate WAV File", color = Color.White)
                        }
                    }
                }

                // Share button (when file available)
                if (uiState.hasFileToShare) {
                    Button(
                        onClick = onShareLastFile,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share Generated File", color = Color.White)
                    }
                }

                // Status message
                if (uiState.statusMessage != null || uiState.errorMessage != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (uiState.errorMessage != null) {
                                colors.error.copy(alpha = 0.2f)
                            } else {
                                colors.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (uiState.errorMessage != null) Icons.Default.Warning else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (uiState.errorMessage != null) colors.error else colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.errorMessage ?: uiState.statusMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Transmission history display.
 */
@Composable
fun TransmissionHistoryCard(
    transmissionHistory: List<TransmissionRecord>,
    colors: WSPRColors
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Transmission History (${transmissionHistory.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(transmissionHistory.take(10)) { tx ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = tx.displayString,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = tx.timestamp,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = when (tx.status) {
                                        "success" -> colors.success
                                        "failed" -> colors.error
                                        else -> colors.warning
                                    }
                                )
                            ) {
                                Text(
                                    text = tx.status.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Diagnostics section with collapsible details.
 */
@Composable
fun DiagnosticsCard(
    onGetDiagnostics: () -> String,
    colors: WSPRColors
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
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
                    text = "System Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = {
                        if (!showDiagnostics) {
                            diagnosticsText = onGetDiagnostics()
                        }
                        showDiagnostics = !showDiagnostics
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant)
                ) {
                    Icon(
                        imageVector = if (showDiagnostics) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (showDiagnostics) "Hide" else "Show",
                        tint = colors.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showDiagnostics) "Hide" else "Show Diagnostics",
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (showDiagnostics) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
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
 * Permission request screen.
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
            contentDescription = "Permission Required",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Audio permission required",
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