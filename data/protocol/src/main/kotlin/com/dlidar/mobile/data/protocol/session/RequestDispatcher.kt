package com.dlidar.mobile.data.protocol.session

/**
 * Request ids and the table of pending requests. Ids run through 0..[Int.MAX_VALUE], wrap, and
 * skip ids that are still pending. Thread-safe.
 */
internal class RequestDispatcher(firstId: Int = 1) {
    private val lock = Any()
    private val pending = LinkedHashMap<Int, PendingCall<*>>()
    private var nextId = firstId
    private var closed = false

    init {
        require(firstId >= 0) { "ids are not negative" }
    }

    /** Registers [call] and returns its id, or null when the dispatcher is closed. */
    fun register(call: PendingCall<*>): Int? {
        synchronized(lock) {
            if (closed) return null
            var id = nextId
            while (id in pending) id = following(id)
            nextId = following(id)
            pending[id] = call
            call.id = id
            return id
        }
    }

    fun remove(id: Int) {
        synchronized(lock) { pending.remove(id) }
    }

    /** Takes the call for a response. [Taken.inOrder] is false when an older call is still pending. */
    fun take(id: Int): Taken? {
        synchronized(lock) {
            val oldest = pending.keys.firstOrNull()
            val call = pending.remove(id) ?: return null
            return Taken(call, inOrder = oldest == id)
        }
    }

    /** Fails every pending call and refuses new ones. */
    fun close(failure: CallFailure) {
        val calls = synchronized(lock) {
            if (closed) return
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        calls.forEach { it.fail(failure) }
    }

    private fun following(id: Int) = if (id == Int.MAX_VALUE) 0 else id + 1

    class Taken(val call: PendingCall<*>, val inOrder: Boolean)
}
