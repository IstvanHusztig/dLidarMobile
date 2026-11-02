package com.dlidar.mobile.domain.model

/**
 * Everything the app knows about the selected scanner. Values stay after the link drops so the
 * UI can show the last known state; [connection] tells whether they are live.
 */
data class ScannerSnapshot(
    val target: ScannerCandidate? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val compatibility: Compatibility = Compatibility.Unknown,
    val status: ScannerStatus? = null,
    /** Free storage in bytes; null when the scanner does not know it or has not said yet. */
    val freeBytes: Long? = null,
    val info: DeviceInfo? = null,
    val warnings: List<Warning> = emptyList(),
    val lastSummary: RecordingSummary? = null,
)
