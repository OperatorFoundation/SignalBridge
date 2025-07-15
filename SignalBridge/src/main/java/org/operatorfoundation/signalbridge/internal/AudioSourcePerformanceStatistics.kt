package org.operatorfoundation.signalbridge.internal

/**
 * Tracks performance statistics for monitoring audio source health.
 */
internal class AudioSourcePerformanceStatistics
{
    // Counters
    private var totalSamplesReceived = 0L
    private var totalReadRequests = 0L
    private var successfulReads = 0L
    private var partialReads = 0L
    private var readErrors = 0L
    private var bufferOverflows = 0L
    private var totalSamplesOverflowed = 0L

    // Timing for periodic reporting
    private var lastStatisticsLogTime = System.currentTimeMillis()
    private val statisticsLogIntervalMs = 30_000L // Log every 30 seconds

    /**
     * Records that audio samples were received.
     */
    fun recordSamplesReceived(sampleCount: Int)
    {
        totalSamplesReceived += sampleCount
    }

    /**
     * Records a request to read audio samples.
     */
    fun recordReadRequest(requestedSamples: Int, availableSamples: Int)
    {
        totalReadRequests++
    }

    /**
     * Records a successful read operation that returned the requested amount.
     */
    fun recordSuccessfulRead(sampleCount: Int)
    {
        successfulReads++
    }

    /**
     * Records a partial read that returned fewer samples than requested.
     */
    fun recordPartialRead(sampleCount: Int)
    {
        partialReads++
    }

    /**
     * Records a read operation that failed with an error.
     */
    fun recordReadError()
    {
        readErrors++
    }

    /**
     * Records a buffer overflow event where old samples were discarded.
     */
    fun recordBufferOverflow(samplesDiscarded: Int)
    {
        bufferOverflows++
        totalSamplesOverflowed += samplesDiscarded
    }

    /**
     * Checks if it's time to log performance statistics.
     */
    fun shouldLogStatistics(): Boolean
    {
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastStatisticsLogTime) >= statisticsLogIntervalMs
    }

    /**
     * Gets a summary of current performance statistics.
     */
    fun getStatisticsSummary(): String
    {
        lastStatisticsLogTime = System.currentTimeMillis()

        val readSuccessRate = if (totalReadRequests > 0) {
            (successfulReads.toDouble() / totalReadRequests.toDouble() * 100.0).toInt()
        } else {
            0
        }

        return "Reads: ${totalReadRequests} (${readSuccessRate}% success), " +
                "Samples: ${totalSamplesReceived}, " +
                "Overflows: ${bufferOverflows}, " +
                "Errors: ${readErrors}"
    }

    /**
     * Resets all statistics counters.
     */
    fun reset()
    {
        totalSamplesReceived = 0L
        totalReadRequests = 0L
        successfulReads = 0L
        partialReads = 0L
        readErrors = 0L
        bufferOverflows = 0L
        totalSamplesOverflowed = 0L
        lastStatisticsLogTime = System.currentTimeMillis()
    }
}