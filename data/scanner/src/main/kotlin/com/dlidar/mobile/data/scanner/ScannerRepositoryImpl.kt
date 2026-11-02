package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.protocol.session.ProtocolSession
import com.dlidar.mobile.data.protocol.session.ProtocolSessionFactory
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.scanner.command.StatusGetCommand
import com.dlidar.mobile.data.scanner.dto.StatusDto
import com.dlidar.mobile.data.scanner.event.ScannerEventDecoders
import com.dlidar.mobile.data.scanner.session.SessionHandshake
import com.dlidar.mobile.data.scanner.session.SessionSignalHandler
import com.dlidar.mobile.data.scanner.session.SessionStop
import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.port.Clock
import com.dlidar.mobile.domain.port.ScannerRepository
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a connection to the selected scanner and turns what it reports into a [ScannerSnapshot].
 *
 * Each connect() starts a [Run]: connect, run a session ([SessionHandshake] and
 * [SessionSignalHandler] do the protocol work), and reconnect per [ReconnectPolicy] when the link
 * drops. The lifecycle runs in [scope], which the composition root owns.
 */
class ScannerRepositoryImpl(
    private val transport: ByteTransport,
    private val sessionFactory: ProtocolSessionFactory,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val logger: Logger = Logger.None,
) : ScannerRepository {

    private val state = MutableStateFlow(ScannerSnapshot())
    override val snapshot: StateFlow<ScannerSnapshot> = state.asStateFlow()

    private val decoders = ScannerEventDecoders.v1()
    private val warningIds = AtomicLong()
    private val lock = Any()
    /** The run allowed to change the snapshot; null after disconnect(). Guarded by [lock]. */
    private var current: Run? = null
    /**
     * Run jobs that have not finished, current or not. Every new run cancels them at once and
     * waits for all of them, so no old run's cleanup can close the new run's link, even when a
     * run was cancelled before it started. Guarded by [lock].
     */
    private val unfinished = mutableSetOf<Job>()
    /**
     * Warnings the user dismissed, by kind and text, so a status.get that still lists them does
     * not bring them back. A key is forgotten once the scanner's list no longer has it, or when
     * a warning event raises it again. Guarded by [lock].
     */
    private val dismissed = mutableSetOf<Pair<WarningKind, String>>()
    /** Set only after hello allowed further requests. */
    @Volatile private var live: LiveSession? = null

    override fun connect(candidate: ScannerCandidate) {
        synchronized(lock) {
            val previous = unfinished.toList()
            previous.forEach { it.cancel() }
            val run = Run(candidate.id)
            current = run
            if (state.value.target?.id != candidate.id) dismissed.clear()
            reduce(ScannerInput.TargetSelected(candidate))
            reduce(ScannerInput.ConnectionChanged(ConnectionState.Connecting(attempt = 1)))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous.joinAll()
                run.maintain()
            }
            unfinished += job
            job.invokeOnCompletion { synchronized(lock) { unfinished -= job } }
            job.start()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            current = null
            unfinished.toList().forEach { it.cancel() }
            reduce(ScannerInput.ConnectionChanged(ConnectionState.Disconnected))
        }
    }

    override suspend fun refreshStatus(): Outcome<Unit> {
        if (!state.value.compatibility.allowsRequests) {
            return Outcome.Failed(DomainError.IncompatibleProtocol)
        }
        // Before hello has decided compatibility there is no live session, so nothing is sent.
        val active = live ?: return Outcome.Failed(DomainError.NotConnected)
        return when (val result = active.session.call(StatusGetCommand, active.run::applyStatus)) {
            is CallResult.Success -> Outcome.Ok(Unit)
            is CallResult.Failure -> Outcome.Failed(ProtocolMappers.error(result.failure))
        }
    }

    override fun dismissWarning(warningId: Long) {
        synchronized(lock) {
            state.value.warnings.firstOrNull { it.id == warningId }?.let { dismissed += it.key }
            reduce(ScannerInput.WarningDismissed(warningId))
        }
    }

    /** Callers hold [lock]. */
    private fun reduce(input: ScannerInput) {
        state.update { ScannerStateReducer.reduce(it, input) }
    }

    private class LiveSession(val run: Run, val session: ProtocolSession)

    private sealed interface SessionEnd {
        data object Refused : SessionEnd
        /** [healthy]: the scanner answered on this link, so backoff starts over. */
        data class LinkDown(val reason: DownReason, val healthy: Boolean) : SessionEnd
    }

    /**
     * One connect() call: connect, run sessions, reconnect, until disconnect() or another
     * connect(). A run that is no longer current cannot change the snapshot.
     */
    private inner class Run(private val target: String) {

        fun record(input: ScannerInput) {
            synchronized(lock) {
                if (current !== this) return
                if (input is ScannerInput.WarningRaised) dismissed -= input.warning.key
                reduce(input)
            }
        }

        fun applyStatus(dto: StatusDto) {
            val now = clock.now()
            val warnings = dto.warnings?.map { Warning(warningIds.incrementAndGet(), ProtocolMappers.warningKind(it.kind), it.text, now) }
            val summary = dto.summary?.let { ProtocolMappers.summary(it, now) }
            synchronized(lock) {
                if (current !== this) return
                if (warnings != null) dismissed.retainAll(warnings.map { it.key }.toSet())
                reduce(ScannerInput.StatusLoaded(ProtocolMappers.status(dto), dto.freeBytes, warnings?.filterNot { it.key in dismissed }, summary))
            }
        }

        suspend fun maintain() {
            var failures = 0
            var waitingForBluetooth = false
            try {
                while (true) {
                    val connecting = ConnectionState.Connecting(attempt = failures + 1)
                    if (!waitingForBluetooth) record(ScannerInput.ConnectionChanged(connecting))
                    val reason = when (val result = connectOnce(connecting, waitingForBluetooth)) {
                        is ConnectResult.Failed -> result.reason
                        is ConnectResult.Connected -> {
                            waitingForBluetooth = false
                            when (val end = runSession(result.connection)) {
                                SessionEnd.Refused -> {
                                    record(ScannerInput.ConnectionChanged(ConnectionState.Refused(ConnectionState.Refused.Reason.AnotherPhoneConnected)))
                                    return
                                }
                                is SessionEnd.LinkDown -> {
                                    if (end.healthy) failures = 0
                                    // A session can end with its link still up (a broken stream):
                                    // release the scanner during the backoff.
                                    val link = transport.link.value
                                    if (link is LinkState.Up && link.connection === result.connection) transport.disconnect()
                                    end.reason
                                }
                            }
                        }
                    }
                    logger.debug(TAG) { "link down: $reason" }
                    if (reason == DownReason.BluetoothOff) {
                        waitingForBluetooth = true
                        record(ScannerInput.ConnectionChanged(ConnectionState.WaitingForBluetooth))
                        delay(reconnectPolicy.bluetoothPollInterval)
                        continue
                    }
                    waitingForBluetooth = false
                    if (!reconnectPolicy.shouldRetry(reason)) {
                        record(ScannerInput.ConnectionChanged(ConnectionState.Failed(failureReason(reason))))
                        return
                    }
                    failures++
                    val wait = reconnectPolicy.delayAfter(failures)
                    record(ScannerInput.ConnectionChanged(ConnectionState.WaitingToRetry(failures + 1, wait.inWholeMilliseconds)))
                    delay(wait)
                }
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }

        /**
         * One connect attempt. While the screen says Bluetooth is off, it switches to [connecting]
         * as soon as the transport really starts connecting.
         */
        private suspend fun connectOnce(connecting: ConnectionState, waitingForBluetooth: Boolean): ConnectResult = coroutineScope {
            val watcher = if (waitingForBluetooth) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    transport.link.first { it is LinkState.Connecting }
                    record(ScannerInput.ConnectionChanged(connecting))
                }
            } else {
                null
            }
            transport.connect(target).also { watcher?.cancel() }
        }

        private suspend fun runSession(connection: ByteConnection): SessionEnd = coroutineScope {
            val stop = CompletableDeferred<SessionStop>()
            lateinit var session: ProtocolSession
            val refresher = StatusRefresher(this) { fetchStatus(session) }
            val handler = SessionSignalHandler(
                decoders = decoders,
                clock = clock,
                newWarningId = warningIds::incrementAndGet,
                record = ::record,
                onGap = refresher::request,
                onStop = { stop.complete(it) },
                logger = logger,
            )
            session = sessionFactory.create(connection, handler, this)
            try {
                record(ScannerInput.CompatibilityResolved(Compatibility.Unknown))
                record(ScannerInput.ConnectionChanged(ConnectionState.Connected))
                session.start()
                val goLive: () -> Unit = { synchronized(lock) { if (current === this@Run) live = LiveSession(this@Run, session) } }
                launch { SessionHandshake(session, refresher, ::record, goLive, logger).run() }

                val linkDown = async {
                    transport.link.first { !(it is LinkState.Up && it.connection === connection) }
                }
                val first = select<SessionEnd> {
                    stop.onAwait { it.toEnd(session) }
                    linkDown.onAwait { SessionEnd.LinkDown((it as? LinkState.Down)?.reason ?: DownReason.ConnectionLost(null), false) }
                }
                val end = if (first is SessionEnd.LinkDown) {
                    // A refusal sent just before the scanner dropped the link is still in the reader.
                    withTimeoutOrNull(DRAIN_TIMEOUT) { session.awaitDrained() }
                    if (stop.isCompleted) stop.await().toEnd(session) else first.copy(healthy = session.hasReceivedMessages)
                } else {
                    first
                }
                coroutineContext.cancelChildren()
                end
            } finally {
                synchronized(lock) { if (live?.session === session) live = null }
                session.close()
            }
        }

        /** Returns false when the status is worth fetching again. */
        private suspend fun fetchStatus(session: ProtocolSession): Boolean {
            val result = session.call(StatusGetCommand, ::applyStatus)
            if (result !is CallResult.Failure) return true
            logger.warn(TAG, "status.get failed: ${result.failure}")
            return !CallRetry.worthRetrying(result.failure)
        }

        private fun SessionStop.toEnd(session: ProtocolSession): SessionEnd = when (this) {
            SessionStop.Refused -> SessionEnd.Refused
            SessionStop.Broken -> SessionEnd.LinkDown(DownReason.ConnectionLost(null), session.hasReceivedMessages)
        }

        private fun failureReason(reason: DownReason): ConnectionState.Failed.Reason = when (reason) {
            DownReason.PermissionMissing -> ConnectionState.Failed.Reason.PermissionMissing
            DownReason.ServiceNotFound -> ConnectionState.Failed.Reason.NotADLidar
            DownReason.NotPaired -> ConnectionState.Failed.Reason.NotPaired
            else -> ConnectionState.Failed.Reason.Other
        }
    }

    private companion object {
        const val TAG = "Scanner"
        val DRAIN_TIMEOUT = 1.seconds

        val Warning.key get() = kind to text
    }
}
