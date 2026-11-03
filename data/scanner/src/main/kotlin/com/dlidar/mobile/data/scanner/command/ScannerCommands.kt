package com.dlidar.mobile.data.scanner.command

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.scanner.dto.HelloInfo
import com.dlidar.mobile.data.scanner.dto.InfoDto
import com.dlidar.mobile.data.scanner.dto.RecordingCommandDto
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import com.dlidar.mobile.data.scanner.dto.StatusDto
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A v1 command without arguments whose result is read with [serializer]. */
internal abstract class NoArgsCommand<R>(
    override val name: String,
    private val serializer: KSerializer<R>,
) : ProtocolCommand<R> {
    final override fun args(): JsonObject? = null
    final override fun decode(result: JsonObject): R = ScannerJson.decodeFromJsonElement(serializer, result)
}

/**
 * hello is read leniently: only `protocol`, `limits` and `commands` matter, and a hello whose other fields
 * changed shape must still reveal a protocol mismatch. It never fails to decode.
 */
internal object HelloCommand : ProtocolCommand<HelloInfo> {
    override val name = "hello"
    override fun args(): JsonObject? = null
    override fun decode(result: JsonObject): HelloInfo {
        val limits = result["limits"] as? JsonObject
        return HelloInfo(
            protocol = result.int("protocol"),
            maxRequestBytes = limits?.int("max_request_bytes"),
            maxMessageBytes = limits?.int("max_message_bytes"),
            commands = (result["commands"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.toSet(),
        )
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}

internal object StatusGetCommand : NoArgsCommand<StatusDto>("status.get", StatusDto.serializer())

internal object InfoGetCommand : NoArgsCommand<InfoDto>("info.get", InfoDto.serializer())

internal object RecordingStartCommand : NoArgsCommand<RecordingCommandDto>("recording.start", RecordingCommandDto.serializer())

internal object RecordingStopCommand : NoArgsCommand<RecordingCommandDto>("recording.stop", RecordingCommandDto.serializer())

/** Commands a scanner must list in hello before the app offers recording control. */
internal val RECORDING_COMMANDS = setOf(RecordingStartCommand.name, RecordingStopCommand.name)
