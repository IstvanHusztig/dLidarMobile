package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import kotlinx.coroutines.CoroutineScope

/** Sends commands and returns their results. */
interface CommandSender {
    /**
     * Sends [command] and suspends until its response, a timeout, or the end of the link.
     *
     * [onAnswer] runs on the session's reader, before any message that arrived after the
     * response is handled. Use it to apply results in stream order. It must not suspend.
     *
     * [onSent] gets the request id just before the request is written, so a result that comes
     * later in an event (`"op": <id>`) can be matched. It must not suspend.
     */
    suspend fun <R> call(
        command: ProtocolCommand<R>,
        onSent: ((id: Int) -> Unit)? = null,
        onAnswer: ((R) -> Unit)? = null,
    ): CallResult<R>
}

/** The protocol on one connection, from link up to link down. A new link gets a new session. */
interface ProtocolSession : CommandSender {
    /** Starts reading. Signals go to the listener given at creation. */
    fun start()

    /** Applies limits the scanner reported in hello. Null keeps the current value. */
    fun applyLimits(maxRequestBytes: Int?, maxMessageBytes: Int?)

    /** True once any protocol message (response, event or error) arrived on this session. */
    val hasReceivedMessages: Boolean

    /** Suspends until the reader handled everything the link delivered before it ended. */
    suspend fun awaitDrained()

    /** Ends the session. Pending calls fail with [CallFailure.LinkLost]. */
    fun close()
}

fun interface ProtocolSessionFactory {
    fun create(connection: ByteConnection, listener: SessionListener, scope: CoroutineScope): ProtocolSession
}
