package org.operatorfoundation.signalbridge

/**
 * Represents a completed WSPR transmission record.
 * Tracks successful transmissions for history and debugging.
 */
data class TransmissionRecord(
    val id: Long,
    val timestamp: String,
    val callsign: String,
    val gridSquare: String,
    val power: Int, // Power in dBm
    val status: String,
    val serialResponse: String? = null, // Response from serial connection if any
    val durationMs: Long? = null // Transmission duration in milliseconds
)
{
    /** Summary line for logging */
    fun createSummaryLine(): String = "$callsign $gridSquare ${power}dBm at $timestamp ($status)"

    /** Formatted display string for UI */
    val displayString: String
        get() = "$callsign - $gridSquare - ${power}dBm"
}
