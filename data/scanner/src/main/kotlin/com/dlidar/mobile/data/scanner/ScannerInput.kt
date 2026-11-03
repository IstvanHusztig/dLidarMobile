package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.Warning

/** Everything that changes the snapshot. Inputs carry their own time, so the reducer stays pure. */
internal sealed interface ScannerInput {
    data class TargetSelected(val candidate: ScannerCandidate) : ScannerInput
    data class ConnectionChanged(val state: ConnectionState) : ScannerInput
    data class CompatibilityResolved(val compatibility: Compatibility) : ScannerInput
    /** Whether this connection's scanner takes recording.start and recording.stop. */
    data class RecordingControlKnown(val available: Boolean) : ScannerInput
    /**
     * The answer to status.get. [warnings] is the scanner's whole list (already without the ones
     * the user dismissed), or null when the answer had none; [summary] is null when it had none.
     */
    data class StatusLoaded(
        val status: ScannerStatus,
        val freeBytes: Long?,
        val warnings: List<Warning>? = null,
        val summary: RecordingSummary? = null,
    ) : ScannerInput
    data class InfoLoaded(val info: DeviceInfo) : ScannerInput
    data class StateChanged(val status: ScannerStatus) : ScannerInput
    data class ProgressReported(val chunks: Long, val points: Long) : ScannerInput
    data class WarningRaised(val warning: Warning) : ScannerInput
    data class SummaryReceived(val summary: RecordingSummary) : ScannerInput
    data class WarningDismissed(val warningId: Long) : ScannerInput
}
