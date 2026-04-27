package org.operatorfoundation.signalbridge

import org.operatorfoundation.audiocoder.wspr.WSPRAudioSource
import org.operatorfoundation.audiocoder.wspr.WSPRConstants.WSPR_REQUIRED_SAMPLE_RATE
import org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration

/**
 * SignalBridge implementation of [WSPRAudioSource] for USB audio devices.
 *
 * Extends [SignalBridgeAudioSource] which provides all buffering, resampling,
 * and lifecycle behaviour.
 *
 * @param usbAudioConnection  Active USB audio connection from SignalBridge.
 * @param bufferConfiguration Optional buffer configuration. Defaults to sizes
 *                            appropriate for WSPR's 2-minute decode windows.
 */
class SignalBridgeWSPRAudioSource(
    usbAudioConnection: UsbAudioConnection,
    bufferConfiguration: AudioBufferConfiguration =
        AudioBufferConfiguration.createDefault(WSPR_REQUIRED_SAMPLE_RATE)
) : SignalBridgeAudioSource(usbAudioConnection, WSPR_REQUIRED_SAMPLE_RATE, bufferConfiguration),
    WSPRAudioSource
{
    override suspend fun initialize(): Result<Unit>                    = super.initialize()
    override suspend fun readAudioChunk(durationMs: Long): ShortArray  = super.readAudioChunk(durationMs)
    override suspend fun flushBuffer()                                 = super<SignalBridgeAudioSource>.flushBuffer()
    override suspend fun cleanup()                                     = super.cleanup()
    override suspend fun getSourceStatus(): AudioSourceStatus          = super.getSourceStatus()
}