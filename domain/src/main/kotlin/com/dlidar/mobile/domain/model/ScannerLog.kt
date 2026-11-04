package com.dlidar.mobile.domain.model

import java.time.Instant

/** One line of the scanner service's log, as log.get reports it. */
data class LogEntry(
    /** The Pi's clock, which can be wrong offline (no RTC battery); the order is the list's, not this. */
    val time: Instant,
    /** Short boot id; it changes when the scanner reboots. */
    val boot: String,
    val level: LogLevel,
    val source: LogSource,
    /** The scanner's text, shown as it is; at most 1024 bytes. */
    val text: String,
)

sealed interface LogLevel {
    data object Info : LogLevel
    data object Warning : LogLevel
    data object Error : LogLevel
    data class Unknown(val raw: String) : LogLevel
}

sealed interface LogSource {
    /** dLidar's own lines. */
    data object Dlidar : LogSource
    /** systemd's lines about the service: started, exited, crashed, restarted. */
    data object System : LogSource
    data class Unknown(val raw: String) : LogSource
}

/** The lowest level a read returns. */
enum class LogThreshold { Info, Warning, Error }

/** Where the next older page starts. Opaque: only the scanner reads it. */
@JvmInline
value class LogCursor(val raw: String)

/**
 * Lines newest first. [older] reads the next older page; null when the start of the log is
 * reached. A page can be empty while [older] is not null.
 */
data class LogPage(val entries: List<LogEntry>, val older: LogCursor?)
