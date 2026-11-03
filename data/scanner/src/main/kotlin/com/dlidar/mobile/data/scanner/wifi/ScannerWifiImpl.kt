package com.dlidar.mobile.data.scanner.wifi

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.scanner.LiveSessionSource
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.data.scanner.command.WifiConnectCommand
import com.dlidar.mobile.data.scanner.command.WifiStatusCommand
import com.dlidar.mobile.data.scanner.session.PendingOperations
import com.dlidar.mobile.domain.model.LastJoin
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.port.ScannerWifi
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ScannerWifi] over the repository's live session.
 *
 * A join is sent with `wifi.connect`; its outcome is the `result` event with the request's id
 * as `op`, which comes within the `timeout_s` of the answer. When that event cannot arrive
 * (the link dropped, which joining may cause on the Pi's shared radio) or does not arrive in
 * time, the outcome is read from `wifi.status`: its `last` is the newest join, "pending" while
 * it runs. One deadline covers the whole join (app choice): the scanner's timeout plus
 * [resultMargin] for the event plus [recoveryWindow] for reading the status, asked every
 * [statusPoll] while pending.
 */
class ScannerWifiImpl internal constructor(
    private val source: LiveSessionSource,
    private val logger: Logger,
    private val resultMargin: Duration,
    private val recoveryWindow: Duration,
    private val statusPoll: Duration,
) : ScannerWifi {

    constructor(repository: ScannerRepositoryImpl, logger: Logger = Logger.None) :
        this(repository.liveSessionSource, logger, RESULT_MARGIN, RECOVERY_WINDOW, STATUS_POLL)

    override suspend fun join(ssid: String, security: WifiSecurity, password: WifiPassword?): Outcome<WifiJoinResult> {
        try {
            requestProblem()?.let { return Outcome.Failed(it) }
            val live = source.liveSession.value ?: return Outcome.Failed(DomainError.NotConnected)
            // The newest join before this one, so a lost request cannot pass an old outcome off as its own.
            val before = (live.session.call(WifiStatusCommand) as? CallResult.Success)?.value?.let(WifiMappers::status)?.last
            val psk = password?.toCharArray()
            var pending: CompletableDeferred<JsonObject?>? = null
            var opId: Int? = null
            val answer = try {
                live.session.call(WifiConnectCommand(ssid, security, psk), onSent = { id ->
                    opId = id
                    pending = live.operations.register(id)
                })
            } finally {
                psk?.fill('\u0000')
                password?.clear()
            }
            try {
                return when (answer) {
                    is CallResult.Failure -> {
                        val failure = answer.failure
                        // Sent, but the answer was lost: the join may be running, so ask its status.
                        if (pending != null && (failure == CallFailure.LinkLost || failure == CallFailure.Timeout)) {
                            Outcome.Ok(awaitOutcome(ssid, DEFAULT_TIMEOUT, event = null, before = before))
                        } else {
                            Outcome.Failed(ProtocolMappers.error(failure))
                        }
                    }
                    is CallResult.Success -> {
                        if (!answer.value.accepted) return Outcome.Failed(DomainError.InvalidResponse)
                        val timeout = (answer.value.timeoutS ?: DEFAULT_TIMEOUT.inWholeSeconds.toInt()).seconds
                        // Accepted: the join surely started, so the newest last for this SSID is its own.
                        Outcome.Ok(awaitOutcome(ssid, timeout, event = pending, before = null))
                    }
                }
            } finally {
                opId?.let(live.operations::forget)
            }
        } finally {
            password?.clear()
        }
    }

    override suspend fun status(): Outcome<ScannerWifiStatus> {
        requestProblem()?.let { return Outcome.Failed(it) }
        val live = source.liveSession.value ?: return Outcome.Failed(DomainError.NotConnected)
        return when (val result = live.session.call(WifiStatusCommand)) {
            is CallResult.Success -> Outcome.Ok(WifiMappers.status(result.value))
            is CallResult.Failure -> Outcome.Failed(ProtocolMappers.error(result.failure))
        }
    }

    private fun requestProblem(): DomainError? {
        val snapshot = source.snapshot.value
        return when {
            !snapshot.compatibility.allowsRequests -> DomainError.IncompatibleProtocol
            source.liveSession.value == null -> DomainError.NotConnected
            !snapshot.canSetUpWifi -> DomainError.NotSupported
            else -> null
        }
    }

    /**
     * Waits for the result [event], then falls back to wifi.status, all within one deadline.
     * [before] is the `last` seen before sending, or null when the join is known to have started.
     */
    private suspend fun awaitOutcome(
        ssid: String,
        scannerTimeout: Duration,
        event: CompletableDeferred<JsonObject?>?,
        before: LastJoin?,
    ): WifiJoinResult = withTimeoutOrNull(scannerTimeout + resultMargin + recoveryWindow) {
        val received = event?.let { withTimeoutOrNull(scannerTimeout + resultMargin) { it.await() } }
        received?.let(::readResult) ?: recover(ssid, before)
    } ?: WifiJoinResult.Unknown

    private fun readResult(event: JsonObject): WifiJoinResult? = try {
        WifiMappers.joinResult(event)
    } catch (e: IllegalArgumentException) {
        logger.warn(TAG, "unreadable result event: ${e.message}")
        null
    }

    /**
     * Reads the outcome from wifi.status, waiting for a reconnect when needed. `last` must name
     * [ssid] and, when [before] is given, have changed since or been seen pending; otherwise
     * (dLidar restarted, someone else joined, or the request never arrived) it is unknown.
     */
    private suspend fun recover(ssid: String, before: LastJoin?): WifiJoinResult {
        logger.debug(TAG) { "join result not received; reading wifi.status" }
        var sawPending = false
        while (true) {
            val live = source.liveSession.first { it != null }!!
            val answer = live.session.call(WifiStatusCommand)
            if (answer is CallResult.Success) {
                val last = WifiMappers.status(answer.value).last
                if (last == null || last.ssid != ssid) return WifiJoinResult.Unknown
                val result = last.result
                when {
                    result == null -> sawPending = true
                    before != null && !sawPending && last == before -> return WifiJoinResult.Unknown
                    else -> return result
                }
            }
            delay(statusPoll)
        }
    }

    private companion object {
        const val TAG = "Wifi"
        /** Spec: the scanner's join timeout, used when the answer did not say. */
        val DEFAULT_TIMEOUT = 60.seconds
        /** App choice: extra wait for the result event beyond the scanner's timeout. */
        val RESULT_MARGIN = 10.seconds
        /** App choice: how long after that the status is still asked. */
        val RECOVERY_WINDOW = 30.seconds
        val STATUS_POLL = 2.seconds
    }
}
