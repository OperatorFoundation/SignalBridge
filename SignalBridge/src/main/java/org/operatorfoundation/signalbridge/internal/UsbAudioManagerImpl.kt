package org.operatorfoundation.signalbridge.internal

import android.Manifest
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.operatorfoundation.signalbridge.UsbAudioConnection
import org.operatorfoundation.signalbridge.UsbAudioManager
import org.operatorfoundation.signalbridge.exceptions.DeviceNotFoundException
import org.operatorfoundation.signalbridge.exceptions.PermissionDeniedException
import org.operatorfoundation.signalbridge.models.ConnectionStatus
import org.operatorfoundation.signalbridge.models.UsbAudioDevice
import timber.log.Timber

/**
 * Internal implementation of UsbAudioManager.
 *
 * This class handles USB device discovery, permission management, and connection establishment.
 * It coordinates between the USB system services and the audio recording components.
 *
 * @property context Application context for accessing system services
 */
internal class UsbAudioManagerImpl(private val context: Context) : UsbAudioManager
{
    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private val deviceDiscovery: UsbDeviceDiscovery by lazy {
        UsbDeviceDiscovery(context, usbManager)
    }

    private val permissionManager: UsbPermissionManager by lazy {
        UsbPermissionManager(context, usbManager)
    }

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)

    // Keep track of current connection to prevent multiple simultaneous connections
    private var currentConnection: RealUsbAudioConnection? = null

    init
    {
        Timber.d("UsbAudioManager initialized")
    }

    override fun discoverDevices(): Flow<List<UsbAudioDevice>>
    {
        Timber.d("Starting device discovery...")

        return  deviceDiscovery.discoverAudioDevices()
            .distinctUntilChanged()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun connectToDevice(device: UsbAudioDevice): Result<UsbAudioConnection>
    {
        return try
        {
            Timber.d("Attempting to connect to device: ${device.displayName}")

            // Check for an existing connection
            currentConnection?.let { existingConnection ->
                if (existingConnection.device.deviceId == device.deviceId)
                {
                    Timber.d("Already connected to device: ${device.displayName}")
                    return Result.success(existingConnection)
                }
                else
                {
                    // Disconnect from current device first
                    Timber.d("Disconnecting from current device before connecting to new one")
                    existingConnection.disconnect()
                    currentConnection = null
                }
            }

            _connectionStatus.value = ConnectionStatus.Connecting

            // Get the USB device from the system
            val usbDevice = deviceDiscovery.getUsbDeviceById(device.deviceId) ?: return Result.failure(
                DeviceNotFoundException(device.deviceId))

            // Request permission if needed
            if (!permissionManager.hasPermission(usbDevice))
            {
                Timber.d("Requesting permission for device: ${device.displayName}")

                val permissionGranted = permissionManager.requestPermission(usbDevice)

                if (!permissionGranted)
                {
                    Timber.w("Permission denied for device: ${device.displayName}")
                    _connectionStatus.value = ConnectionStatus.Error("Permission denied")
                    return Result.failure(
                        PermissionDeniedException(device.displayName)
                    )
                }
            }

            // Create the connection
            val connection = RealUsbAudioConnection(
                context = context,
                device = device,
                usbDevice = usbDevice,
                usbManager = usbManager
            )

            // CRITICAL: Test AudioRecord initialization
            val initResult = connection.initializeAudioRecord()
            if (!initResult.isSuccess) {
                val failedResult = initResult as ConnectionInitResult.Failed
                Timber.e("AudioRecord initialization failed for device: ${device.displayName}")
                _connectionStatus.value = ConnectionStatus.Error(
                    "AudioRecord initialization failed: ${failedResult.errorMessage}"
                )
                return Result.failure(failedResult.exception)
            }

            currentConnection = connection
            _connectionStatus.value = ConnectionStatus.Connected

            val successResult = initResult as ConnectionInitResult.Success
            Timber.i("Phase 2: Successfully connected to device with AudioRecord: ${device.displayName}")
            Timber.i("AudioRecord source: ${successResult.audioSource}")
            Timber.i("AudioRecord info: ${successResult.audioRecordInfo}")

            Result.success(connection)
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to connect to device: ${device.displayName}")

            _connectionStatus.value = ConnectionStatus.Error(
                message = "Connection failed: ${exception.message}",
                cause = exception
            )

            Result.failure(exception)
        }
    }

    override fun getConnectionStatus(): Flow<ConnectionStatus>
    {
        return _connectionStatus.asStateFlow()
    }

    override suspend fun cleanup()
    {
        Timber.d("Cleaning up UsbAudioManager")

        try
        {
            // Disconnect current connection if any
            currentConnection?.disconnect()
            currentConnection = null

            // Clean up discovery and permission manager
            deviceDiscovery.cleanup()
            permissionManager.cleanup()

            _connectionStatus.value = ConnectionStatus.Disconnected

            Timber.d("UsbAudioManager cleanup completed")
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error during UsbAudioManager cleanup")
        }
    }

    /**
     * Internal method to handle device disconnection notifications.
     * This is called when a USB device is physically disconnected.
     */
    internal fun onDeviceDisconnected(deviceId: Int)
    {
        currentConnection?.let { connection ->
            if (connection.device.deviceId == deviceId)
            {
                Timber.w("Current connected device was disconnected: $deviceId")
                try
                {
                    connection.handleDeviceDisconnection()
                    currentConnection = null
                    _connectionStatus.value = ConnectionStatus.Disconnected
                }
                catch (exception: Exception)
                {
                    Timber.e(exception, "Error handling device disconnection")
                    _connectionStatus.value = ConnectionStatus.Error(
                        message = "Device disconnected unexpectedly",
                        cause = exception
                    )
                }
            }
        }
    }

    /**
     * Get diagnostic information about AudioRecord compatibility
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun getAudioRecordDiagnostics(device: UsbAudioDevice): AudioRecordDiagnostics
    {
        Timber.d("Running AudioRecord diagnostics for device: ${device.displayName}")

        val usbDevice = deviceDiscovery.getUsbDeviceById(device.deviceId)
            ?:return AudioRecordDiagnostics.deviceNotFound(device.deviceId)

        if (!permissionManager.hasPermission(usbDevice))
        {
            return AudioRecordDiagnostics.permissionDenied(device.displayName)
        }

        // Test AudioRecord initialization
        val audioRecordManager = AudioRecordManager(context)
        val result = audioRecordManager.initialize()

        return AudioRecordDiagnostics(
            deviceId = device.deviceId,
            deviceName = device.displayName,
            hasPermission = true,
            audioRecordResult = result,
            audioRecordInfo = audioRecordManager.getAudioRecordInfo(),
            timestamp = System.currentTimeMillis()
        )
    }
}


/**
 * Diagnostic information for AudioRecord compatibility testing
 */
data class AudioRecordDiagnostics(
    val deviceId: Int,
    val deviceName: String,
    val hasPermission: Boolean,
    val audioRecordResult: AudioRecordInitResult?,
    val audioRecordInfo: AudioRecordInfo?,
    val errorMessage: String? = null,
    val timestamp: Long
)
{
    val isCompatible: Boolean
        get() = hasPermission && audioRecordResult?.isSuccess == true

    val summary: String
        get() = when
        {
            !hasPermission -> "No permission for device"
            audioRecordResult?.isSuccess == true -> "Compatible - AudioRecord initialized successfully"
            else -> "Incompatible - ${audioRecordResult?.errorMessage ?: "Unknown error"}"
        }

    companion object
    {
        fun deviceNotFound(deviceId: Int) = AudioRecordDiagnostics(
            deviceId = deviceId,
            deviceName = "Unknown Device",
            hasPermission = false,
            audioRecordResult = null,
            audioRecordInfo = null,
            errorMessage = "Device not found",
            timestamp = System.currentTimeMillis()
        )

        fun permissionDenied(deviceName: String) = AudioRecordDiagnostics(
            deviceId = -1,
            deviceName = deviceName,
            hasPermission = false,
            audioRecordResult = null,
            audioRecordInfo = null,
            errorMessage = "Permission denied",
            timestamp = System.currentTimeMillis()
        )
    }
}