package org.operatorfoundation.signalbridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.operatorfoundation.audiocoder.common.AudioResampler

/**
 * Converts a [UsbAudioConnection] into a [Flow<ShortArray>].
 *
 * ## Responsibilities
 * - Calls [UsbAudioConnection.startRecording] to begin audio capture.
 * - Resamples to [targetSampleRate] when the hardware delivers audio at a
 *   different rate. The resampler is created lazily on the first chunk
 *   where the rates differ.
 * - Emits each processed [ShortArray] downstream for immediate consumption.
 *
 * ## Usage
 * ```kotlin
 * val audioStream = usbAudioConnection.asAudioFlow(
 *     targetSampleRate = MFSKConstants.MFSK_RECOMMENDED_SAMPLE_RATE
 * )
 * val station = MFSKStation(audioStream, configuration)
 * station.start()
 * ```
 *
 * @param targetSampleRate The sample rate (Hz) that the consumer requires.
 *                         Audio delivered at a different rate is resampled.
 *                         Use [org.operatorfoundation.audiocoder.mfsk.MFSKConstants.MFSK_RECOMMENDED_SAMPLE_RATE]
 *                         for MFSK-16.
 */
fun UsbAudioConnection.asAudioFlow(targetSampleRate: Int): Flow<ShortArray> = flow {
    var resampler: AudioResampler? = null

    startRecording().collect { audioData ->
        val incomingRate: Int = audioData.sampleRate
        val incomingSamples: ShortArray = audioData.samples
        val needsResampling: Boolean = incomingRate != targetSampleRate

        if (resampler == null && needsResampling) {
            resampler = AudioResampler(incomingRate, targetSampleRate)
        }

        val samples: ShortArray = if (resampler != null) {
            resampler!!.resample(incomingSamples)
        } else {
            incomingSamples
        }

        emit(samples)
    }
}