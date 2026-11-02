package com.dlidar.mobile.data.protocol.session

/**
 * Sees every complete message of a session, for debug tools. It is called from the session's
 * reader and writer, so an implementation must be thread-safe and must not block.
 */
fun interface TrafficListener {
    fun onTraffic(traffic: Traffic)

    companion object {
        val None = TrafficListener { }
    }
}

sealed interface Traffic {
    /** A request line as written, without its newline; sensitive args are already redacted. */
    data class Sent(val line: String) : Traffic

    /** A received line, without its newline. */
    data class Received(val line: String) : Traffic

    /** A received message that was dropped before decoding (too long, not UTF-8). */
    data class Dropped(val reason: String) : Traffic
}
