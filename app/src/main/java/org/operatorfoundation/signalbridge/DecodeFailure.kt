package org.operatorfoundation.signalbridge


/**
 * Represents a WSPR decode failure for debugging purposes.
 * Contains diagnostic information to help understand why decodes fail.
 */
data class DecodeFailure(
    val id: Long,
    val timestamp: String,
    val reason: String,
    val cyclePosition: Int, // Position in 120-second WSPR cycle when failure occured
    val audioLevel: String, // Audio level percentage at time of failure
    val additionalInfo: String? = null // Optional extra debugging info
)
{
    fun createSummaryLine(): String = "$timestamp: $reason (cycle: ${cyclePosition}s, audio: $audioLevel)"
}