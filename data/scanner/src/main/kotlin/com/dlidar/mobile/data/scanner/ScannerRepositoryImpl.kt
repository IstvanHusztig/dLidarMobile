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
import kotlinx.coroutines.cancelAndJoin
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
    /** The last run launched, current or not, so the next one waits for its cleanup. Guarded by [lock]. */
    private var lastJob: Job? = null
    @Volatile private var live: LiveSession? = null

    override fun connect(candidate: ScannerCandidate) {
        synchronized(lock) {
            val previous = lastJob
            val run = Run(candidate.id)
            current = run
            reduce(ScannerInput.TargetSelected(candidate))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.cancelAndJoin()
                run.maintain()
            }
            lastJob = job
            job.start()
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            current = null
            lastJob?.cancel()
            reduce(ScannerInput.ConnectionChanged(ConnectionState.Disconnected))
        }
    }

    override suspend fun refreshStatus(): Outcome<Unit> {
        if (state.value.compatibility is Compatibility.UpdateAppRequired) {
            return Outcome.Failed(DomainError.IncompatibleProtocol)
        }
        val active = live ?: return Outcome.Failed(DomainError.NotConnected)
        return when (val result = active.session.call(StatusGetCommand, active.run::applyStatus)) {
            is CallResult.Success -> Outcome.Ok(Unit)
            is CallResult.Failure -> Outcome.Failed(ProtocolMappers.error(result.failure))
        }
    }

    override fun dismissWarning(warningId: Long) {
        synchronized(lock) { reduce(ScannerInput.WarningDismissed(warningId)) }
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
            synchronized(lock) { if (current === this) reduce(input) }
        }

        fun applyStatus(dto: StatusDto) {
            record(ScannerInput.StatusLoaded(ProtocolMappers.status(dto), dto.freeBytes))
        }

        suspend fun maintain() {
            var failures = 0
            var waitingForBluetooth = false
            try {
                while (true) {
                    if (!waitingForBluetooth) {
                        record(ScannerInput.ConnectionChanged(ConnectionState.Connecting(attempt = failures + 1)))
                    }
                    val reason = when (val result = transport.connect(target)) {
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
                synchronized(lock) { if (current === this@Run) live = LiveSession(this@Run, session) }
                launch { SessionHandshake(session, refresher, ::record, logger).run() }

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
            DownReason.BluetoothOff -> ConnectionState.Failed.Reason.BluetoothOff
            DownReason.PermissionMissing -> ConnectionState.Failed.Reason.PermissionMissing
            DownReason.ServiceNotFound -> ConnectionState.Failed.Reason.NotADLidar
            else -> ConnectionState.Failed.Reason.Other
        }
    }

    private companion object {
        const val TAG = "Scanner"
        val DRAIN_TIMEOUT = 1.seconds
    }
}
