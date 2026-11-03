package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.Warning

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
        is ScannerInput.RecordingControlKnown -> snapshot.copy(canControlRecording = input.available)
        is ScannerInput.StatusLoaded -> snapshot.copy(
            status = input.status,
            freeBytes = input.freeBytes,
            warnings = input.warnings?.let { mergeWarnings(snapshot.warnings, it) } ?: snapshot.warnings,
            lastSummary = input.summary?.let { keepTimeIfSame(snapshot.lastSummary, it) } ?: snapshot.lastSummary,
        )
        is ScannerInput.InfoLoaded -> snapshot.copy(info = input.info)
        // App choice: the state event has no free_bytes, so the last known value stays.
        is ScannerInput.StateChanged -> snapshot.copy(status = input.status)
        // Progress before the first status is dropped; the status.get sent on connect covers it.
        is ScannerInput.ProgressReported -> snapshot.status
            ?.let { snapshot.copy(status = it.copy(chunks = input.chunks, points = input.points)) }
            ?: snapshot
        // App choice: a warning repeated with the same kind and text is shown once, as the newest.
        is ScannerInput.WarningRaised -> snapshot.copy(
            warnings = (snapshot.warnings.filterNot { it.sameAs(input.warning) } + input.warning).takeLast(MAX_WARNINGS),
        )
        is ScannerInput.SummaryReceived -> snapshot.copy(lastSummary = input.summary)
        is ScannerInput.WarningDismissed -> snapshot.copy(warnings = snapshot.warnings.filterNot { it.id == input.warningId })
    }

    /**
     * The scanner's list replaces the shown one, each kind and text once, in the order of its
     * newest occurrence. A warning already shown keeps its id and time, so the list does not
     * flicker on every refresh.
     */
    private fun mergeWarnings(shown: List<Warning>, loaded: List<Warning>): List<Warning> =
        loaded.asReversed()
            .distinctBy { it.kind to it.text }
            .asReversed()
            .map { warning -> shown.firstOrNull { it.sameAs(warning) } ?: warning }
            .takeLast(MAX_WARNINGS)

    private fun Warning.sameAs(other: Warning) = kind == other.kind && text == other.text

    private fun keepTimeIfSame(shown: RecordingSummary?, loaded: RecordingSummary): RecordingSummary =
        if (shown != null && shown.copy(receivedAt = loaded.receivedAt) == loaded) shown else loaded
}
