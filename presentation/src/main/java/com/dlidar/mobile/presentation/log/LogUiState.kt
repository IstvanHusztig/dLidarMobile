package com.dlidar.mobile.presentation.log

import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.LogCursor
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.result.DomainError

data class LogUiState(
    val threshold: LogThreshold = LogThreshold.Info,
    /** Lines newest first, with a header where a boot starts. */
    val rows: List<LogRow> = emptyList(),
    val access: Access = Access.NotConnected,
    val loading: Loading = Loading.None,
    /** True once a read reached the start of the log. */
    val startReached: Boolean = false,
    val canLoadOlder: Boolean = false,
    val failure: ReadFailure? = null,
) {
    val hasLines: Boolean get() = rows.any { it is LogRow.Line }

    /** Whether the log can be read now. */
    enum class Access {
        Live,
        /** Connected, but hello has not told yet whether the scanner sends its log. */
        Waiting,
        /** No common protocol, or the scanner does not answer; the status screen says why. */
        Blocked,
        NotSupported,
        NotConnected,
    }

    enum class Loading { None, Newest, Older }

    /** The last read failed; [read] tells which, so Retry repeats it. */
    data class ReadFailure(val read: Loading, val error: DomainError)
}

sealed interface LogRow {
    /** The first (newest) lines of [boot] follow. */
    data class BootStart(val boot: String) : LogRow
    data class Line(val entry: LogEntry) : LogRow

    companion object {
        /** [entries] newest first; a header goes before each run of lines from one boot. */
        fun of(entries: List<LogEntry>): List<LogRow> = buildList {
            var boot: String? = null
            for (entry in entries) {
                if (entry.boot != boot) {
                    add(BootStart(entry.boot))
                    boot = entry.boot
                }
                add(Line(entry))
            }
        }
    }
}

sealed interface LogAction {
    /** The Diagnostics tab is shown: the newest page is read once, when the scanner can answer. */
    data object Opened : LogAction
    data object Refresh : LogAction
    data object LoadOlder : LogAction
    data class SetThreshold(val threshold: LogThreshold) : LogAction
}

/** What the log needs from the snapshot; status and progress changes do not touch it. */
internal data class LogAccessInput(val access: LogUiState.Access, val scannerId: String?) {
    companion object {
        fun from(snapshot: ScannerSnapshot): LogAccessInput {
            val live = snapshot.connection == ConnectionState.Connected
            val access = when {
                !live -> LogUiState.Access.NotConnected
                snapshot.compatibility == Compatibility.Unknown -> LogUiState.Access.Waiting
                !snapshot.compatibility.allowsRequests -> LogUiState.Access.Blocked
                !snapshot.canReadLog -> LogUiState.Access.NotSupported
                else -> LogUiState.Access.Live
            }
            return LogAccessInput(access, snapshot.target?.id)
        }
    }
}

/** The lines the ViewModel holds, and where the next older page starts. */
internal data class HeldLog(
    val threshold: LogThreshold = LogThreshold.Info,
    val rows: List<LogRow> = emptyList(),
    val entries: List<LogEntry> = emptyList(),
    val older: LogCursor? = null,
    /** True once a read of the newest page succeeded. */
    val loaded: Boolean = false,
    val loading: LogUiState.Loading = LogUiState.Loading.None,
    val failure: LogUiState.ReadFailure? = null,
)
