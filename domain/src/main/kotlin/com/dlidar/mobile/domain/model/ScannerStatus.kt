package com.dlidar.mobile.domain.model

/** Recording status of the scanner, as reported by status.get and the state event. */
data class ScannerStatus(
    val state: RecordingState,
    val chunks: Long,
    val points: Long,
    val lastOk: Boolean,
    val location: String,
)
