package com.dlidar.mobile.data.protocol.codec

import com.dlidar.mobile.data.protocol.ProtocolLimits
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Converts between protocol lines and messages. Unknown fields are ignored; a line that is not a
 * protocol message becomes [Inbound.Unparsed] instead of an exception.
 */
class MessageCodec {

    fun encodeRequest(id: Int, command: String, args: JsonObject?): String {
        val request = buildJsonObject {
            put("v", ProtocolLimits.PROTOCOL_VERSION)
            put("id", id)
            put("cmd", command)
            if (args != null) put("args", args)
        }
        return Json.encodeToString(JsonObject.serializer(), request)
    }

    fun decode(line: String): Inbound {
        val element = try {
            Json.parseToJsonElement(line)
        } catch (e: SerializationException) {
            return Inbound.Unparsed(line, "not JSON")
        }
        val message = element as? JsonObject ?: return Inbound.Unparsed(line, "not a JSON object")
        return when {
            "event" in message -> decodeEvent(message, line)
            "id" in message -> decodeResponse(message, line)
            else -> Inbound.Unparsed(line, "neither a response nor an event")
        }
    }

    private fun decodeResponse(message: JsonObject, line: String): Inbound {
        val error = (message["error"] as? JsonObject)?.let(::decodeError)
        val idElement = message["id"]
        if (idElement is JsonNull) {
            return error?.let { Inbound.UnsolicitedError(it) }
                ?: Inbound.Unparsed(line, "id is null but there is no error")
        }
        val id = (idElement as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it in 0..Int.MAX_VALUE }
            ?.toInt()
            ?: return Inbound.Unparsed(line, "invalid id")
        val ok = (message["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: return Inbound.Unparsed(line, "missing ok")
        val body = if (ok) {
            ResponseBody.Ok(message["result"] as? JsonObject ?: EMPTY)
        } else {
            ResponseBody.Err(error ?: RemoteError(ErrorCode.Other(""), "", EMPTY))
        }
        return Inbound.Response(id, body)
    }

    private fun decodeError(error: JsonObject): RemoteError = RemoteError(
        code = ErrorCode.fromWire(error.string("code") ?: ""),
        message = error.string("message") ?: "",
        details = error,
    )

    private fun decodeEvent(message: JsonObject, line: String): Inbound {
        val name = message.string("event") ?: return Inbound.Unparsed(line, "event name is not a string")
        return Inbound.Event(
            ProtocolEvent(
                name = name,
                seq = message.long("seq"),
                dropped = message.long("dropped"),
                data = message["data"] as? JsonObject ?: EMPTY,
                raw = message,
            ),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}
