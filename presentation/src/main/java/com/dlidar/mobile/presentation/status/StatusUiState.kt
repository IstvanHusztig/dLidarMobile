package com.dlidar.mobile.presentation.status

import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingControl
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.result.DomainError

data class StatusUiState(
    val scannerName: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val updateAppRequired: Boolean = false,
    val updateScannerRequired: Boolean = false,
    /** Connected, but the scanner answers no request (for example, its test service runs). */
    val notAnswering: Boolean = false,
    /** Values are live only while connected; otherwise they are the last known ones. */
    val isLive: Boolean = false,
    val canRetry: Boolean = false,
    val status: ScannerStatus? = null,
    val freeBytes: Long? = null,
    val info: DeviceInfo? = null,
    val warnings: List<Warning> = emptyList(),
    val summary: RecordingSummary? = null,
    val refreshError: DomainError? = null,
    /** Start and Stop are shown: live, and the scanner takes the commands. */
    val showRecordingControls: Boolean = false,
    val canStart: Boolean = false,
    val canStop: Boolean = false,
    /** The last recording is still being saved; Start waits for idle. */
    val saving: Boolean = false,
    val commandFailure: CommandFailure? = null,
    /** The Wi-Fi setup entry is shown: live, and the scanner takes the Wi-Fi commands. */
    val showWifiSetup: Boolean = false,
) {
    /** A start or stop the scanner refused, or that got no answer. */
    data class CommandFailure(val command: RecordingCommand, val error: DomainError)

    enum class RecordingCommand { Start, Stop }

    companion object {
        fun from(
            snapshot: ScannerSnapshot,
            refreshError: DomainError? = null,
            sending: Boolean = false,
            commandFailure: CommandFailure? = null,
        ): StatusUiState {
            val live = snapshot.connection == ConnectionState.Connected
            val controls = live && snapshot.compatibility.allowsRequests && snapshot.canControlRecording
            val recordingState = snapshot.status?.state
            return StatusUiState(
                scannerName = snapshot.target?.name,
                connection = snapshot.connection,
                updateAppRequired = snapshot.compatibility is Compatibility.UpdateAppRequired,
                updateScannerRequired = snapshot.compatibility is Compatibility.UpdateScannerRequired,
                notAnswering = snapshot.compatibility == Compatibility.NotAnswering && live,
                isLive = live,
                canRetry = snapshot.connection is ConnectionState.Refused ||
                    snapshot.connection is ConnectionState.Failed ||
                    snapshot.connection == ConnectionState.Disconnected,
                status = snapshot.status,
                freeBytes = snapshot.freeBytes,
                info = snapshot.info,
                warnings = snapshot.warnings,
                summary = snapshot.lastSummary,
                refreshError = refreshError,
                showRecordingControls = controls,
                canStart = controls && !sending && recordingState != null && RecordingControl.canStart(recordingState),
                canStop = controls && !sending && recordingState != null && RecordingControl.canStop(recordingState),
                saving = controls && recordingState == RecordingState.Finalizing,
                commandFailure = commandFailure.takeIf { controls },
                showWifiSetup = live && snapshot.compatibility.allowsRequests && snapshot.canSetUpWifi,
            )
        }
    }
}

sealed interface StatusAction {
    data object Refresh : StatusAction
    data object Retry : StatusAction
    data object Disconnect : StatusAction
    data class DismissWarning(val warningId: Long) : StatusAction
    data object StartRecording : StatusAction
    data object StopRecording : StatusAction
}
