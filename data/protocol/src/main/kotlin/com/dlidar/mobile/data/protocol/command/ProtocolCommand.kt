package com.dlidar.mobile.data.protocol.command

import kotlinx.serialization.json.JsonObject

/**
 * One command of the protocol. A new command is a new implementation; the session, the codec and
 * the dispatcher do not change (open/closed).
 *
 * [args] must contain only arguments the scanner documents: an unknown argument is rejected with
 * bad_request.
 */
interface ProtocolCommand<out R> {
    val name: String

    /** Argument names whose values are replaced in logs, for example `psk`. */
    val sensitiveArgs: Set<String> get() = emptySet()

    fun args(): JsonObject?

    /** Reads the result. Throws [IllegalArgumentException] when the result does not fit. */
    fun decode(result: JsonObject): R
}
