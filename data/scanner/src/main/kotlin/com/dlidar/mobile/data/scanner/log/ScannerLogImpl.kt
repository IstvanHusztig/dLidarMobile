package com.dlidar.mobile.data.scanner.log

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.scanner.LiveSessionSource
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.data.scanner.command.LogGetCommand
import com.dlidar.mobile.data.scanner.dto.LogEntryDto
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import com.dlidar.mobile.domain.model.LogCursor
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogPage
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.port.ScannerLog
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import kotlinx.serialization.json.JsonObject

/**
 * [ScannerLog] over the repository's live session, with `log.get`.
 *
 * A page always fits the scanner's message limit and one request reads only a bounded part of
 * the journal, so an answer can hold fewer lines than asked, even none, while `more` is not
 * null. A read therefore asks again with the new cursor until it has lines or the start of the
 * log, at most [MAX_REQUESTS] times; after that it returns the empty page and its cursor.
 */
class ScannerLogImpl internal constructor(
    private val source: LiveSessionSource,
    private val logger: Logger,
) : ScannerLog {

    constructor(repository: ScannerRepositoryImpl, logger: Logger = Logger.None) : this(repository.liveSessionSource, logger)

    override suspend fun read(older: LogCursor?, threshold: LogThreshold): Outcome<LogPage> {
        requestProblem()?.let { return Outcome.Failed(it) }
        val live = source.liveSession.value ?: return Outcome.Failed(DomainError.NotConnected)
        var cursor = older?.raw
        repeat(MAX_REQUESTS) {
            val command = LogGetCommand(cursor, PAGE_SIZE, LogMappers.wire(threshold))
            when (val answer = live.session.call(command)) {
                is CallResult.Failure -> return Outcome.Failed(ProtocolMappers.error(answer.failure))
                is CallResult.Success -> {
                    val entries = answer.value.entries.mapNotNull(::readEntry)
                    if (entries.size < answer.value.entries.size) {
                        logger.warn(TAG, "skipped ${answer.value.entries.size - entries.size} unreadable log entries")
                    }
                    cursor = answer.value.more
                    if (entries.isNotEmpty() || cursor == null) return Outcome.Ok(LogPage(entries, cursor?.let(::LogCursor)))
                }
            }
        }
        return Outcome.Ok(LogPage(emptyList(), cursor?.let(::LogCursor)))
    }

    private fun requestProblem(): DomainError? {
        val snapshot = source.snapshot.value
        return when {
            !snapshot.compatibility.allowsRequests -> DomainError.IncompatibleProtocol
            source.liveSession.value == null -> DomainError.NotConnected
            !snapshot.canReadLog -> DomainError.NotSupported
            else -> null
        }
    }

    private fun readEntry(raw: JsonObject): LogEntry? = try {
        LogMappers.entry(ScannerJson.decodeFromJsonElement(LogEntryDto.serializer(), raw))
    } catch (e: IllegalArgumentException) {
        // SerializationException is one too.
        null
    }

    private companion object {
        const val TAG = "Log"
        /** App choice: lines asked per request; the scanner sends fewer when they do not fit 16 KiB. */
        const val PAGE_SIZE = 100
        /** App choice: requests per read while the answers hold no lines. */
        const val MAX_REQUESTS = 5
    }
}
