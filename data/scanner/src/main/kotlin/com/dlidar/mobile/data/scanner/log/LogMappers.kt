package com.dlidar.mobile.data.scanner.log

import com.dlidar.mobile.data.scanner.dto.LogEntryDto
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogLevel
import com.dlidar.mobile.domain.model.LogSource
import com.dlidar.mobile.domain.model.LogThreshold
import java.time.Instant

/** log.get wire values to domain types. Unknown levels and sources become the generic ones. */
internal object LogMappers {

    fun wire(threshold: LogThreshold): String = when (threshold) {
        LogThreshold.Info -> "info"
        LogThreshold.Warning -> "warning"
        LogThreshold.Error -> "error"
    }

    fun entry(dto: LogEntryDto) = LogEntry(
        time = Instant.ofEpochMilli(dto.t),
        boot = dto.boot,
        level = level(dto.level),
        source = source(dto.source),
        text = dto.text,
    )

    private fun level(raw: String): LogLevel = when (raw) {
        "info" -> LogLevel.Info
        "warning" -> LogLevel.Warning
        "error" -> LogLevel.Error
        else -> LogLevel.Unknown(raw)
    }

    private fun source(raw: String): LogSource = when (raw) {
        "dlidar" -> LogSource.Dlidar
        "system" -> LogSource.System
        else -> LogSource.Unknown(raw)
    }
}
