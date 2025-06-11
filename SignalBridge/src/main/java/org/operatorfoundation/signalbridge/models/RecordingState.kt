package org.operatorfoundation.signalbridge.models

/**
 * Current state of audio recording.
 */
sealed class RecordingState
{
    object Stopped : RecordingState()
    object Starting : RecordingState()
    object Recording : RecordingState()
    object Stopping : RecordingState()
    data class Error(val message: String, val cause: Throwable? = null) : RecordingState()

    override fun toString(): String = when (this)
    {
        is Stopped -> "Stopped"
        is Starting -> "Starting"
        is Recording -> "Recording"
        is Stopping -> "Stopping"
        is Error -> "Error: $message"
    }
}