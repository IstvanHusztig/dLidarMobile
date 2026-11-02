package com.dlidar.mobile.data.protocol.codec

import kotlinx.serialization.json.JsonObject

/** One decoded message from the scanner. */
sealed interface Inbound {
    data class Response(val id: Int, val body: ResponseBody) : Inbound

    /**
     * An error with `"id":null`: too_many_clients, or too_large / bad_request for a request whose
     * id could not be read (the session gives those to the oldest pending request).
     */
    data class UnsolicitedError(val error: RemoteError) : Inbound

    data class Event(val event: ProtocolEvent) : Inbound

    /** A line that is not a protocol message, for example from the echo test service. */
    data class Unparsed(val line: String, val reason: String) : Inbound
}

sealed interface ResponseBody {
    data class Ok(val result: JsonObject) : ResponseBody
    data class Err(val error: RemoteError) : ResponseBody
}

/** An error reported by the scanner. [details] is the whole error object, for extra fields. */
data class RemoteError(
    val code: ErrorCode,
    val message: String,
    val details: JsonObject,
)

/**
 * An event as it came in. [seq] and [dropped] are null when absent. [raw] is the whole message,
 * so fields used later (such as `op`) stay reachable without changing this type.
 */
data class ProtocolEvent(
    val name: String,
    val seq: Long?,
    val dropped: Long?,
    val data: JsonObject,
    val raw: JsonObject,
)
