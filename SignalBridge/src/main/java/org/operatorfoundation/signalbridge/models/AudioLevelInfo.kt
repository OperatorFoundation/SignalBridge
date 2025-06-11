package org.operatorfoundation.signalbridge.models

/**
 * Audio level information for monitoring and display.
 *
 * @property currentLevel Current audio level as a Float (0.0 to 1.0)
 * @property peakLevel Peak audio level since last reset as a Float (0.0 to 0.0)
 * @property averageLevel Average audio level over recent samples  as a Float (0.0 to 0.0)
 * @property timestamp Timestamp when this level was measured as a Long
 */
data class AudioLevelInfo(
    val currentLevel: Float,
    val peakLevel: Float,
    val averageLevel: Float,
    val timestamp: Long
)
{
    init
    {
        require(currentLevel in 0f..1f) { "Current level must be between 0.0 and 1.0." }
        require(peakLevel in 0f..1f) { "Peak level must be between 0.0 and 1.0." }
        require(averageLevel in 0f..1f) { "Average level must be between 0.0 and 1.0." }
    }

    /**
     * Returns true if audio level indicates silence (below threshold).
     */
    fun isSilent(threshold: Float = 0.01f): Boolean = currentLevel < threshold

    /**
     * Returns true if the audio level indicates clipping (above threshold).
     */
    fun isClipping(threshold: Float = 0.95f): Boolean = currentLevel > threshold
}
