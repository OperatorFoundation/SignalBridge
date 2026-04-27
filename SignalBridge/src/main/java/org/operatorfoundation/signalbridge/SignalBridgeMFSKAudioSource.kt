package org.operatorfoundation.signalbridge

import org.operatorfoundation.audiocoder.mfsk.MFSKAudioSource
import org.operatorfoundation.audiocoder.mfsk.MFSKConstants.MFSK_RECOMMENDED_SAMPLE_RATE
import org.operatorfoundation.audiocoder.common.models.AudioSourceStatus
import org.operatorfoundation.signalbridge.models.AudioBufferConfiguration

/**
 * SignalBridge implementation of [MFSKAudioSource] for USB audio devices.
 *
 * Extends [SignalBridgeAudioSource] which provides all buffering, resampling,
 * and lifecycle behaviour.
 *
 * @param usbAudioConnection  Active USB audio connection from SignalBridge.
 * @param bufferConfiguration Optional buffer configuration. Defaults to sizes
 *                            appropriate for MFSK streaming decode sessions.
 */
class SignalBridgeMFSKAudioSource(
    usbAudioConnection: UsbAudioConnection,
    bufferConfiguration: AudioBufferConfiguration =
        AudioBufferConfiguration.createDefault(MFSK_RECOMMENDED_SAMPLE_RATE)
) : SignalBridgeAudioSource(usbAudioConnection, MFSK_RECOMMENDED_SAMPLE_RATE, bufferConfiguration),
    MFSKAudioSource
{
    override suspend fun initialize(): Result<Unit>  = super.initialize()
    override suspend fun readAudioChunk(durationMs: Long): ShortArray = super.readAudioChunk(durationMs)
    override suspend fun flushBuffer()               = super<SignalBridgeAudioSource>.flushBuffer()
    override suspend fun cleanup()                   = super.cleanup()
    override suspend fun getSourceStatus(): AudioSourceStatus = super.getSourceStatus()
}