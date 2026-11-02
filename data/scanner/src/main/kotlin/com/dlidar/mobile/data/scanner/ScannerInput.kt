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
    data class StatusLoaded(val status: ScannerStatus, val freeBytes: Long?) : ScannerInput
    data class InfoLoaded(val info: DeviceInfo) : ScannerInput
    data class StateChanged(val status: ScannerStatus) : ScannerInput
    data class ProgressReported(val chunks: Long, val points: Long) : ScannerInput
    data class WarningRaised(val warning: Warning) : ScannerInput
    data class SummaryReceived(val summary: RecordingSummary) : ScannerInput
    data class WarningDismissed(val warningId: Long) : ScannerInput
}
