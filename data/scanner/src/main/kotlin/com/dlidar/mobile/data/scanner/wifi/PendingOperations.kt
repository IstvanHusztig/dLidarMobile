package com.dlidar.mobile.data.scanner.wifi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Requests whose outcome comes later in a `result` event carrying `"op": <request id>`. One per
 * session: ids are per connection, and the scanner sends a result only to the asking
 * connection, so when the session ends every pending operation ends without a result.
 */
internal class PendingOperations {
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject?>>()
    @Volatile private var closed = false

    /** Call before the request is written. The deferred gets the whole event, or null when the session ended. */
    fun register(id: Int): CompletableDeferred<JsonObject?> {
        val deferred = CompletableDeferred<JsonObject?>()
        waiting[id] = deferred
        if (closed) deferred.complete(null)
        return deferred
    }

    /** Returns false when no request waits for [op] (logged by the caller, otherwise ignored). */
    fun complete(op: Int, event: JsonObject): Boolean = waiting.remove(op)?.complete(event) ?: false

    fun forget(id: Int) {
        waiting.remove(id)
    }

    fun close() {
        closed = true
        waiting.values.forEach { it.complete(null) }
        waiting.clear()
    }
}
