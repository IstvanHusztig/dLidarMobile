package com.dlidar.mobile.presentation.log

import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogLevel
import com.dlidar.mobile.domain.model.LogSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The log as plain text to share. Not translated: it is read by whoever looks into a problem. */
object LogExport {

    /** Share texts are capped; an Intent extra must stay well below the 1 MB Binder limit. */
    const val MAX_CHARS = 100_000

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * [entries] newest first, written oldest first, one line each. When they do not fit
     * [maxChars], the oldest are left out and the first line says so.
     */
    fun text(entries: List<LogEntry>, zone: ZoneId, maxChars: Int = MAX_CHARS): String {
        val lines = ArrayDeque<String>()
        var size = 0
        for (entry in entries) {
            val line = line(entry, zone)
            if (size + line.length + 1 > maxChars) break
            lines.addFirst(line)
            size += line.length + 1
        }
        val left = entries.size - lines.size
        if (left > 0) lines.addFirst("[$left older lines left out]")
        return lines.joinToString("\n")
    }

    fun line(entry: LogEntry, zone: ZoneId): String =
        "${stamp.format(entry.time.atZone(zone))} ${entry.boot} ${level(entry.level)} ${source(entry.source)}: ${entry.text}"

    private fun level(level: LogLevel) = when (level) {
        LogLevel.Info -> "INFO"
        LogLevel.Warning -> "WARNING"
        LogLevel.Error -> "ERROR"
        is LogLevel.Unknown -> level.raw.uppercase()
    }

    private fun source(source: LogSource) = when (source) {
        LogSource.Dlidar -> "dlidar"
        LogSource.System -> "system"
        is LogSource.Unknown -> source.raw
    }
}
