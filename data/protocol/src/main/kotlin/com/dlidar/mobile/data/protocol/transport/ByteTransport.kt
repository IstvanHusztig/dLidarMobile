package com.dlidar.mobile.data.protocol.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A byte link to one scanner (BLE in production, a fake in tests).
 *
 * [connect] makes one attempt and suspends until the link is usable or the attempt failed.
 * "Usable" means the MTU is negotiated **and** notifications are on, so nothing can be written
 * too early. Retrying is the caller's decision.
 */
interface ByteTransport {
    val link: StateFlow<LinkState>

    suspend fun connect(target: String): ConnectResult

    /** Closes the current link, if any. The link then reports [DownReason.Closed]. */
    suspend fun disconnect()
}

/** One established connection. It is valid while [ByteTransport.link] is `Up` with it. */
interface ByteConnection {
    /** Largest piece [write] accepts: the negotiated MTU minus 3. */
    val maxWritePiece: Int

    /**
     * Notification payloads in arrival order. It has a single collector and completes when the
     * connection ends.
     */
    val incoming: Flow<ByteArray>

    /** Writes one piece of at most [maxWritePiece] bytes. */
    suspend fun write(piece: ByteArray): WriteResult
}

sealed interface ConnectResult {
    data class Connected(val connection: ByteConnection) : ConnectResult
    data class Failed(val reason: DownReason) : ConnectResult
}

sealed interface WriteResult {
    data object Ok : WriteResult
    data class Failed(val reason: String) : WriteResult
}
