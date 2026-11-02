package com.dlidar.mobile.data.scanner.command

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.scanner.dto.HelloInfo
import com.dlidar.mobile.data.scanner.dto.InfoDto
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import com.dlidar.mobile.data.scanner.dto.StatusDto
import kotlinx.serialization.KSerializer
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
 * hello is read leniently: only `protocol` and `limits` matter, and a hello whose other fields
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
        )
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}

internal object StatusGetCommand : NoArgsCommand<StatusDto>("status.get", StatusDto.serializer())

internal object InfoGetCommand : NoArgsCommand<InfoDto>("info.get", InfoDto.serializer())
