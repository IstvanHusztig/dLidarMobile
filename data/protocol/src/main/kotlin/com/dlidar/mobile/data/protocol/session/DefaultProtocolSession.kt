package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.ProtocolLimits
import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.codec.Inbound
import com.dlidar.mobile.data.protocol.codec.MessageCodec
import com.dlidar.mobile.data.protocol.codec.ProtocolEvent
import com.dlidar.mobile.data.protocol.codec.RemoteError
import com.dlidar.mobile.data.protocol.codec.ResponseBody
import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.protocol.framing.DecodedFrame
import com.dlidar.mobile.data.protocol.framing.FrameDecoder
import com.dlidar.mobile.data.protocol.framing.FrameSplitter
import com.dlidar.mobile.data.protocol.framing.SplitResult
import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The protocol on one connection.
 *
 * One reader coroutine decodes everything in arrival order: responses go to their pending call
 * (and its `onAnswer`), everything else goes to the [listener]. Writes are serialized so pieces
 * of two requests never interleave, and ids are allocated in write order.
 */
class DefaultProtocolSession(
    private val connection: ByteConnection,
    private val listener: SessionListener,
    parentScope: CoroutineScope,
    limits: ProtocolLimits = ProtocolLimits(),
    private val codec: MessageCodec = MessageCodec(),
    private val logger: Logger = Logger.None,
    private val traffic: TrafficListener = TrafficListener.None,
) : ProtocolSession {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val decoder = FrameDecoder(limits.maxMessageBytes)
    private val splitter = FrameSplitter(limits.maxRequestBytes)
    private val requestTimeout = limits.requestTimeout
    private val dispatcher = RequestDispatcher()
    private val sequencer = EventSequencer()
    private val writeMutex = Mutex()
    @Volatile private var reader: Job? = null
    @Volatile private var received = false

    override val hasReceivedMessages: Boolean get() = received

    override fun start() {
        reader = scope.launch {
            connection.incoming.collect { piece -> decoder.feed(piece).forEach(::onFrame) }
            logger.debug(TAG) { "link closed" }
            dispatcher.close(CallFailure.LinkLost)
        }
    }

    override suspend fun awaitDrained() {
        reader?.join()
    }

    override fun applyLimits(maxRequestBytes: Int?, maxMessageBytes: Int?) {
        maxRequestBytes?.takeIf { it > 0 }?.let { splitter.maxRequestBytes = it }
        maxMessageBytes?.takeIf { it > 0 }?.let { decoder.maxMessageBytes = it }
    }

    override fun close() {
        dispatcher.close(CallFailure.LinkLost)
        job.cancel()
    }

    override suspend fun <R> call(command: ProtocolCommand<R>, onSent: ((id: Int) -> Unit)?, onAnswer: ((R) -> Unit)?): CallResult<R> {
        val call = PendingCall(command, onAnswer, onSent)
        // The timeout covers only the wait for the answer: writing can take longer, for example
        // while Android pairs on the first write; the transport bounds that.
        try {
            val id = when (val sent = send(call)) {
                is Sent.Ok -> sent.id
                is Sent.Failed -> return CallResult.Failure(sent.failure)
            }
            return withTimeoutOrNull(requestTimeout) { call.result.await() } ?: run {
                dispatcher.remove(id)
                logger.warn(TAG, "${command.name} (id $id) timed out after $requestTimeout")
                CallResult.Failure(CallFailure.Timeout)
            }
        } catch (e: CancellationException) {
            // Cancelled while queued, after writing, or while waiting: forget the request.
            call.id?.let(dispatcher::remove)
            throw e
        }
    }

    private suspend fun send(call: PendingCall<*>): Sent = writeMutex.withLock {
        // A caller cancelled while it waited for the lock sends nothing.
        currentCoroutineContext().ensureActive()
        val id = dispatcher.register(call) ?: return@withLock Sent.Failed(CallFailure.NotConnected)
        val command = call.command
        val split = try {
            splitter.split(codec.encodeRequest(id, command.name, command.args()), connection.maxWritePiece)
        } catch (e: Exception) {
            dispatcher.remove(id)
            throw e
        }
        val pieces = when (split) {
            is SplitResult.Pieces -> split.pieces
            is SplitResult.TooLarge -> {
                dispatcher.remove(id)
                return@withLock Sent.Failed(CallFailure.TooLarge(split.bytes))
            }
        }
        val shown = codec.encodeRequest(id, command.name, redact(command.args(), command.sensitiveArgs))
        logger.debug(TAG) { "-> $shown" }
        traffic.onTraffic(Traffic.Sent(shown))
        call.onSent?.invoke(id)
        // Once the first piece is out, the rest must follow even if the caller is cancelled;
        // otherwise the scanner keeps a partial line and the next request is glued onto it.
        withContext(NonCancellable) { writePieces(id, pieces) }
    }

    private suspend fun writePieces(id: Int, pieces: List<ByteArray>): Sent {
        pieces.forEachIndexed { index, piece ->
            val written = connection.write(piece)
            if (written is WriteResult.Failed) {
                dispatcher.remove(id)
                if (index > 0) breakStream()
                return Sent.Failed(CallFailure.WriteFailed(written.reason))
            }
        }
        return Sent.Ok(id)
    }

    private fun breakStream() {
        logger.warn(TAG, "a request was cut off mid-way; the stream is out of step")
        dispatcher.close(CallFailure.LinkLost)
        listener.onSignal(SessionSignal.StreamBroken)
    }

    private fun onFrame(frame: DecodedFrame) {
        when (frame) {
            is DecodedFrame.Line -> {
                traffic.onTraffic(Traffic.Received(frame.text))
                onLine(frame.text)
            }
            is DecodedFrame.Oversized -> dropped("a message of ${frame.bytes} bytes, over the limit")
            is DecodedFrame.Malformed -> dropped("a message of ${frame.bytes} bytes that is not UTF-8")
        }
    }

    private fun dropped(what: String) {
        logger.warn(TAG, "dropped $what")
        traffic.onTraffic(Traffic.Dropped(what))
    }

    private fun onLine(line: String) {
        val message = codec.decode(line)
        if (message !is Inbound.Unparsed) received = true
        when (message) {
            is Inbound.Response -> onResponse(message)
            is Inbound.Event -> onEvent(message.event)
            is Inbound.UnsolicitedError -> onErrorWithoutId(message.error)
            is Inbound.Unparsed -> logger.debug(TAG) { "ignored line (${message.reason}): $line" }
        }
    }

    private fun onResponse(response: Inbound.Response) {
        val taken = dispatcher.take(response.id)
        if (taken == null) {
            logger.warn(TAG, "response for id ${response.id} has no pending request (late or unknown); ignored")
            return
        }
        if (!taken.inOrder) logger.warn(TAG, "response for id ${response.id} came before an older request's response")
        logger.debug(TAG) { "<- response ${response.id}" }
        taken.call.deliver(response.body)
    }

    /**
     * Spec: too_large, and bad_request for a request whose id could not be read, take that
     * request's place in the response order, so they answer the oldest pending request. Other
     * errors without id (too_many_clients) belong to no request.
     *
     * Accepted limit: when the request the error belongs to already timed out, the next one
     * gets it. The scanner answers in milliseconds and in order (open questions 8 and 9).
     */
    private fun onErrorWithoutId(error: RemoteError) {
        if (error.code == ErrorCode.TooLarge || error.code == ErrorCode.BadRequest) {
            val oldest = dispatcher.takeOldest()
            if (oldest != null) {
                logger.warn(TAG, "${error.code.wire} without id answers ${oldest.command.name} (id ${oldest.id})")
                oldest.deliver(ResponseBody.Err(error))
                return
            }
        }
        logger.warn(TAG, "unsolicited error ${error.code.wire}: ${error.message}")
        listener.onSignal(SessionSignal.UnsolicitedError(error))
    }

    private fun onEvent(event: ProtocolEvent) {
        logger.debug(TAG) { "<- event ${event.name} seq=${event.seq}" }
        listener.onSignal(SessionSignal.EventReceived(event))
        sequencer.check(event.seq, event.dropped)?.let { gap ->
            logger.debug(TAG) { "event gap: $gap" }
            listener.onSignal(SessionSignal.GapDetected(gap))
        }
    }

    private fun redact(args: JsonObject?, sensitive: Set<String>): JsonObject? {
        if (args == null || sensitive.isEmpty()) return args
        return JsonObject(args.mapValues { (key, value) -> if (key in sensitive) JsonPrimitive(REDACTED) else value })
    }

    private sealed interface Sent {
        data class Ok(val id: Int) : Sent
        data class Failed(val failure: CallFailure) : Sent
    }

    private companion object {
        const val TAG = "Protocol"
        const val REDACTED = "***"
    }
}

class DefaultProtocolSessionFactory(
    private val limits: ProtocolLimits = ProtocolLimits(),
    private val logger: Logger = Logger.None,
    private val traffic: TrafficListener = TrafficListener.None,
) : ProtocolSessionFactory {
    override fun create(connection: ByteConnection, listener: SessionListener, scope: CoroutineScope): ProtocolSession =
        DefaultProtocolSession(connection, listener, scope, limits, MessageCodec(), logger, traffic)
}
