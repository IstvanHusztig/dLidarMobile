package com.dlidar.mobile.presentation.status

import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.result.DomainError

data class StatusUiState(
    val scannerName: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val updateAppRequired: Boolean = false,
    /** Values are live only while connected; otherwise they are the last known ones. */
    val isLive: Boolean = false,
    val canRetry: Boolean = false,
    val status: ScannerStatus? = null,
    val freeBytes: Long? = null,
    val info: DeviceInfo? = null,
    val warnings: List<Warning> = emptyList(),
    val summary: RecordingSummary? = null,
    val refreshError: DomainError? = null,
) {
    companion object {
        fun from(snapshot: ScannerSnapshot, refreshError: DomainError? = null) = StatusUiState(
            scannerName = snapshot.target?.name,
            connection = snapshot.connection,
            updateAppRequired = snapshot.compatibility is Compatibility.UpdateAppRequired,
            isLive = snapshot.connection == ConnectionState.Connected,
            canRetry = snapshot.connection is ConnectionState.Refused ||
                snapshot.connection is ConnectionState.Failed ||
                snapshot.connection == ConnectionState.Disconnected,
            status = snapshot.status,
            freeBytes = snapshot.freeBytes,
            info = snapshot.info,
            warnings = snapshot.warnings,
            summary = snapshot.lastSummary,
            refreshError = refreshError,
        )
    }
}

sealed interface StatusAction {
    data object Refresh : StatusAction
    data object Retry : StatusAction
    data object Disconnect : StatusAction
    data class DismissWarning(val warningId: Long) : StatusAction
}
