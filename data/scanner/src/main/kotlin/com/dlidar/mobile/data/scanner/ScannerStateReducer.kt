package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.domain.model.ScannerSnapshot

/**
 * Applies one input to the snapshot. Pure. The choices marked "app choice" are not protocol rules.
 */
internal object ScannerStateReducer {
    /** App choice: the newest warnings are kept. */
    const val MAX_WARNINGS = 20

    fun reduce(snapshot: ScannerSnapshot, input: ScannerInput): ScannerSnapshot = when (input) {
        // A different scanner starts from nothing, so its screen never shows the old one's data.
        is ScannerInput.TargetSelected ->
            if (snapshot.target?.id == input.candidate.id) snapshot.copy(target = input.candidate)
            else ScannerSnapshot(target = input.candidate)
        is ScannerInput.ConnectionChanged -> snapshot.copy(connection = input.state)
        is ScannerInput.CompatibilityResolved -> snapshot.copy(compatibility = input.compatibility)
        is ScannerInput.StatusLoaded -> snapshot.copy(status = input.status, freeBytes = input.freeBytes)
        is ScannerInput.InfoLoaded -> snapshot.copy(info = input.info)
        // App choice: the state event has no free_bytes, so the last known value stays.
        is ScannerInput.StateChanged -> snapshot.copy(status = input.status)
        // Progress before the first status is dropped; the status.get sent on connect covers it.
        is ScannerInput.ProgressReported -> snapshot.status
            ?.let { snapshot.copy(status = it.copy(chunks = input.chunks, points = input.points)) }
            ?: snapshot
        is ScannerInput.WarningRaised -> snapshot.copy(warnings = (snapshot.warnings + input.warning).takeLast(MAX_WARNINGS))
        is ScannerInput.SummaryReceived -> snapshot.copy(lastSummary = input.summary)
        is ScannerInput.WarningDismissed -> snapshot.copy(warnings = snapshot.warnings.filterNot { it.id == input.warningId })
    }
}
