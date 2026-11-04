package com.dlidar.mobile.data.scanner.command

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.scanner.dto.LogPageDto
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `log.get {before, limit, level}`: one page of the service's log, newest first. [before] is
 * the cursor from an earlier answer's `more`; without it the page starts at the newest line.
 * [level] is the lowest level returned: `info`, `warning` or `error`.
 */
internal class LogGetCommand(
    private val before: String?,
    private val limit: Int,
    private val level: String,
) : ProtocolCommand<LogPageDto> {
    init {
        // Spec: limit 1 to 200; anything else is bad_request.
        require(limit in 1..200) { "limit must be 1 to 200" }
    }

    override val name = NAME

    override fun args(): JsonObject = buildJsonObject {
        before?.let { put("before", it) }
        put("limit", limit)
        put("level", level)
    }

    override fun decode(result: JsonObject): LogPageDto = ScannerJson.decodeFromJsonElement(LogPageDto.serializer(), result)

    companion object {
        const val NAME = "log.get"
    }
}
