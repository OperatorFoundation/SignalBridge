package org.operatorfoundation.signalbridge.internal

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * Manages audio output to phone speakers/headphones
 *
 * Responsibilities:
 * - AudioTrack initialization and configuration
 * - Audio playback through system audio output
 * - Playback state management
 * - Audio buffer management for output
 *
 * Takes audio input from USB device and plays it through phone speakers/headphones in real-time.
 */
internal class AudioOutputManager
{
    companion object
    {
        // Audio output configuration - matched input configuration for consistency
        // TODO: Consider moving this to a constants file for single source of truth fgor I/O
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }

    // AudioTrack instance for playback
    private var audioTrack: AudioTrack? = null

    // Playback state tracking
    private val isPlaying = AtomicBoolean(false)

    // Buffer configuration
    private var bufferSize: Int = 0

    // Calculate minimum buffer size for AudioTrack
    private val minBufferSize: Int by lazy {
        AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    /**
     * Initializes AudioTrack for playback.
     * Must be called before any playback operations.
     *
     * @return true if initialization was successful, false if not
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try
        {
            Timber.d("Initializing AudioTrack for USB audio playback")

            // Validate minimum buffer size
            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR)
            {
                Timber.e("Invalid AudioTrack configuration - cannot determine buffer size")
                return@withContext false
            }

            // Calculate optimal buffer size for smooth playback
            bufferSize = calculateOptimalBufferSize(minBufferSize)
            Timber.d("Using AudioTrack buffer size: $bufferSize (min: $minBufferSize)")

            // Create audio attributes for media playback
            // TODO: Verify that these settings are really what we want
//            val audioAttributes = AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_MEDIA)
//                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
//                .setFlags(AudioAttributes.FLAG_LOW_LATENCY) // Request low latency for real-time monitoring
//                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION) // Try this instead of USAGE_MEDIA
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY)
                .build()

            // Create audio format specification
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            // Create AudioTrack instance
            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            // Verify AudioTrack was initialized succesfully
            val track = audioTrack
            if (track?.state == AudioTrack.STATE_INITIALIZED)
            {
                Timber.i("AudioTrack initialized successfully for USB audio playback")
                Timber.d("AudioTrack configuration: ${getAudioTrackInfo()}")
                true
            }
            else
            {
                Timber.e("AudioTrack initialization failed, state: ${track?.state}")
                track?.release()
                audioTrack = null
                false
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Exception initializing AudioTrack")
            audioTrack?.release()
            audioTrack = null
            false
        }
    }

    /**
     * Starts audio playback.
     * Audio samples can then be written using playAudioSamples()
     *
     * @return true if playback started successfully, false if not
     */
    fun startPlayback(): Boolean
    {
        val track = audioTrack

        if (track == null)
        {
            Timber.e("Cannot start playback - AudioTrack not initialized")
            return false
        }

        return try
        {
            if (!isPlaying.get())
            {
                track.play()
                isPlaying.set(true)
                Timber.i("AudioTrack playback started")
                true
            }
            else
            {
                Timber.d("AudioTrack playback already active")
                true
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Failed to start AudioTrack playback")
            isPlaying.set(false)
            false
        }
    }

    /**
     * Stops audio playback and flushes any buffered audio.
     * Playback can be restarted with startPlayback()
     */
    fun stopPlayback()
    {
        val track = audioTrack
        if (track == null)
        {
            Timber.d("Cannot stop playback - AudioTrack not initialized")
            return
        }

        try
        {
            if (isPlaying.get())
            {
                track.pause()
                track.flush() // Clear any buffered audio data
                isPlaying.set(false)
                Timber.i("AudioTrack playback stopped and flushed")
            }
            else
            {
                Timber.d("AudioTrack playback was not active")
            }
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error stopping AudioTrack playback")
            isPlaying.set(false)
        }
    }

    /**
     * Plays audio samples.
     * This should be called with audio data received from the USB device.
     *
     * @param samples Raw 16-bit audio samples to play.
     * @return Number of samples written, or negative value on error
     */
    suspend fun playAudioSamples(samples: ShortArray): Int = withContext(Dispatchers.IO) {
        val track = audioTrack
        if (track == null)
        {
            Timber.v("Cannot play samples - AudioTrack not initialized")
            return@withContext -1
        }

        if (!isPlaying.get())
        {
            Timber.v("Cannot play samples - playback not started")
            return@withContext -1
        }

        if (samples.isEmpty())
        {
            Timber.v("Empty samples array received")
            return@withContext 0
        }

        try
        {
            // Write audio samples to AudioTrack buffer
            val samplesWritten = track.write(samples, 0, samples.size)

            if (samplesWritten < 0)
            {
                // Handle AudioTrack write errors
                val errorMessage = getAudioTrackErrorName(samplesWritten)
                Timber.w("AudioTrack write error: $errorMessage")

                // For critical errors, stop playback
                if (samplesWritten == AudioTrack.ERROR_DEAD_OBJECT)
                {
                    Timber.e("AudioTrack dead object - stopping playback")
                    isPlaying.set(false)
                }
            }
            else if (samplesWritten < samples.size)
            {
                // Partial write - this can happen under high load
                Timber.v("Partial AudioTrack write: $samplesWritten/${samples.size} samples")
            }

            samplesWritten
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Exception writing samples to AudioTrack")
            -1
        }
    }

    /**
     * Gets the current playback head position.
     * Useful for synchronization and latency calculation.
     *
     * @return Current playback position in frames, 0 if audio track is null
     */
    fun getPlaybackPosition(): Int
    {
        return audioTrack?.playbackHeadPosition ?: 0
    }

    /**
     * Checks if audio playback is currently active.
     *
     * @return true if playback is active, false if not
     */
    fun isPlaybackActive(): Boolean
    {
        return isPlaying.get() && audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    /**
     * Gets comprehensive information about the AudioTrack state and configuration.
     *
     * @return AudioTrack info or null if there is no audio track
     */
    fun getAudioTrackInfo(): AudioTrackInfo?
    {
        val track = audioTrack ?: return null

        return AudioTrackInfo(
            state = track.state,
            playState = track.playState,
            sampleRate = SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSize = bufferSize,
            minBufferSize = minBufferSize,
            playbackHeadPosition = track.playbackHeadPosition
        )
    }

    /**
     * Debug method to test if AudioTrack can play anything at all
     */
    suspend fun testDirectPlayback(): Boolean = withContext(Dispatchers.IO)
    {
        try {
            // Generate a simple test tone
            val testSamples = ShortArray(4800) // 0.1 seconds at 48kHz
            for (i in testSamples.indices)
            {
                val time = i.toDouble() / 48000
                val amplitude = (sin(2 * PI * 440.0 * time) * Short.MAX_VALUE * 0.3).toInt()
                testSamples[i] = amplitude.toShort()
            }

            val track = audioTrack ?: return@withContext false

            if (startPlayback())
            {
                val written = track.write(testSamples, 0, testSamples.size)
                Timber.d("Test tone written: $written samples")
                kotlinx.coroutines.delay(200)
                stopPlayback()
                return@withContext written > 0
            }

            false
        }
        catch (e: Exception)
        {
            Timber.e(e, "Test playback failed")
            false
        }
    }

    /**
     * Releases all AudioTrack resources.
     * Should be called when audio output is no longer needed
     */
    fun release()
    {
        try
        {
            // Stop playback first
            stopPlayback()

            // Release AudioTrack resources
            audioTrack?.release()
            audioTrack = null

            // Reset state
            isPlaying.set(false)
            bufferSize = 0

            Timber.d("AudioTrack resources released")
        }
        catch (exception: Exception)
        {
            Timber.e(exception, "Error releasing AudioTrack resources")
        }
    }

    /**
     * Calculates optimal buffer size for smooth playback.
     *
     * @param minBufferSize The minimum buffer size
     * @return The optimal size for the buffer.
     *
     * Note: If min provided is 0 or less the default size is selected.
     */
    private fun calculateOptimalBufferSize(minBufferSize: Int): Int
    {
        return when {
            minBufferSize <= 0 -> DEFAULT_BUFFER_SIZE
            else -> minBufferSize * BUFFER_SIZE_MULTIPLIER
        }
    }

    /**
     * Converts AudioTrack error codes to human-readable names
     */
    private fun getAudioTrackErrorName(errorCode: Int): String
    {
        return when (errorCode) {
            AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioTrack.ERROR -> "ERROR"
            else -> "Unknown error code: $errorCode"
        }
    }

}

/**
 * Information about AudioTrack state and configuration.
 */
data class AudioTrackInfo(
    val state: Int,
    val playState: Int,
    val sampleRate: Int,
    val channelConfig: Int,
    val audioFormat: Int,
    val bufferSize: Int,
    val minBufferSize: Int,
    val playbackHeadPosition: Int
)
{
    val isInitialized: Boolean
        get() = state == AudioTrack.STATE_INITIALIZED

    val isPlaying: Boolean
        get() = playState == AudioTrack.PLAYSTATE_PLAYING

    val isPaused: Boolean
        get() = playState == AudioTrack.PLAYSTATE_PAUSED

    val isStopped: Boolean
        get() = playState == AudioTrack.PLAYSTATE_STOPPED

    val playStateString: String
        get() = when (playState) {
            AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
            AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
            AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
            else -> "UNKNOWN($playState)"
        }

    val stateString: String
        get() = when (state) {
            AudioTrack.STATE_INITIALIZED -> "INITIALIZED"
            AudioTrack.STATE_UNINITIALIZED -> "UNINITIALIZED"
            else -> "UNKNOWN($state)"
        }

    override fun toString(): String
    {
        return "AudioTrackInfo(" +
                "state=$stateString, " +
                "playState=$playStateString, " +
                "sampleRate=$sampleRate, " +
                "bufferSize=$bufferSize, " +
                "minBufferSize=$minBufferSize, " +
                "playbackPos=$playbackHeadPosition)"
    }
}