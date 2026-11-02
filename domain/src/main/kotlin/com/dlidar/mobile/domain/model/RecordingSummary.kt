package com.dlidar.mobile.domain.model

import java.time.Instant

/**
 * End-of-recording summary. [imuMode] is "sensor" (normal) or "host_aligned" (fallback) and
 * [decision] is readable text; both stay strings and are shown as they are.
 */
data class RecordingSummary(
    val imuMode: String,
    val decision: String,
    val imuIn: Long,
    val imuOut: Long,
    val lostSamples: Long,
    val receivedAt: Instant,
)
