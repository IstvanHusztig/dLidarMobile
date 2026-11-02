package com.dlidar.mobile.data.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The single place for protocol limits. `hello` may report other values; a session then uses
 * those instead of the defaults.
 */
data class ProtocolLimits(
    /** Largest request in bytes, counting the trailing newline. */
    val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    /** Largest incoming message in bytes, not counting the trailing newline. */
    val maxMessageBytes: Int = MAX_MESSAGE_BYTES,
    val requestTimeout: Duration = REQUEST_TIMEOUT,
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_REQUEST_BYTES = 4096
        const val MAX_MESSAGE_BYTES = 16384
        val REQUEST_TIMEOUT: Duration = 5.seconds
    }
}
