package com.dlidar.mobile.domain.model

import java.time.Instant

/**
 * End-of-recording summary. [imuMode] and [decision] stay strings because the scanner does not
 * document their values.
 */
data class RecordingSummary(
    val imuMode: String,
    val decision: String,
    val imuIn: Long,
    val imuOut: Long,
    val lostSamples: Long,
    val receivedAt: Instant,
)
