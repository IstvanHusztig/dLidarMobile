package com.dlidar.mobile.data.scanner.wifi

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.data.scanner.command.WifiConnectCommand
import com.dlidar.mobile.data.scanner.command.WifiStatusCommand
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
 * it runs (app choice: asked again every [statusPoll], until [recoveryWindow] after the
 * scanner's timeout).
 */
class ScannerWifiImpl internal constructor(
    private val repository: ScannerRepositoryImpl,
    private val logger: Logger,
    private val resultMargin: Duration,
    private val recoveryWindow: Duration,
    private val statusPoll: Duration,
) : ScannerWifi {

    constructor(repository: ScannerRepositoryImpl, logger: Logger = Logger.None) :
        this(repository, logger, RESULT_MARGIN, RECOVERY_WINDOW, STATUS_POLL)

    override suspend fun join(ssid: String, security: WifiSecurity, password: WifiPassword?): Outcome<WifiJoinResult> {
        requestProblem()?.let {
            password?.clear()
            return Outcome.Failed(it)
        }
        val live = repository.liveSession.value ?: return Outcome.Failed(DomainError.NotConnected)
        val psk = password?.toCharArray()
        var pending: CompletableDeferred<JsonObject?>? = null
        val answer = try {
            live.session.call(WifiConnectCommand(ssid, security, psk), onSent = { id -> pending = live.operations.register(id) })
        } finally {
            psk?.fill('\u0000')
            password?.clear()
        }
        val sent = pending
        return when (answer) {
            is CallResult.Failure -> {
                val failure = answer.failure
                // Sent, but the answer was lost: the join may be running, so ask its status.
                if (sent != null && (failure == CallFailure.LinkLost || failure == CallFailure.Timeout)) {
                    Outcome.Ok(recover(ssid, DEFAULT_TIMEOUT))
                } else {
                    Outcome.Failed(ProtocolMappers.error(failure))
                }
            }
            is CallResult.Success -> {
                val timeout = (answer.value.timeoutS ?: DEFAULT_TIMEOUT.inWholeSeconds.toInt()).seconds
                val event = withTimeoutOrNull(timeout + resultMargin) { sent?.await() }
                val result = event?.let(::readResult)
                Outcome.Ok(result ?: recover(ssid, timeout))
            }
        }
    }

    override suspend fun status(): Outcome<ScannerWifiStatus> {
        requestProblem()?.let { return Outcome.Failed(it) }
        val live = repository.liveSession.value ?: return Outcome.Failed(DomainError.NotConnected)
        return when (val result = live.session.call(WifiStatusCommand)) {
            is CallResult.Success -> Outcome.Ok(WifiMappers.status(result.value))
            is CallResult.Failure -> Outcome.Failed(ProtocolMappers.error(result.failure))
        }
    }

    private fun requestProblem(): DomainError? {
        val snapshot = repository.snapshot.value
        return when {
            !snapshot.compatibility.allowsRequests -> DomainError.IncompatibleProtocol
            repository.liveSession.value == null -> DomainError.NotConnected
            !snapshot.canSetUpWifi -> DomainError.NotSupported
            else -> null
        }
    }

    private fun readResult(event: JsonObject): WifiJoinResult? = try {
        WifiMappers.joinResult(event)
    } catch (e: IllegalArgumentException) {
        logger.warn(TAG, "unreadable result event: ${e.message}")
        null
    }

    /**
     * Reads the outcome from wifi.status, waiting for a reconnect when needed. `last` must name
     * [ssid]; otherwise (dLidar restarted, or someone else joined since) the outcome is unknown.
     */
    private suspend fun recover(ssid: String, scannerTimeout: Duration): WifiJoinResult {
        logger.debug(TAG) { "join result not received; reading wifi.status" }
        return withTimeoutOrNull(scannerTimeout + recoveryWindow) {
            var outcome: WifiJoinResult? = null
            while (outcome == null) {
                val live = repository.liveSession.first { it != null }!!
                val answer = live.session.call(WifiStatusCommand)
                if (answer is CallResult.Success) {
                    val last = WifiMappers.status(answer.value).last
                    outcome = if (last == null || last.ssid != ssid) WifiJoinResult.Unknown else last.result
                }
                if (outcome == null) delay(statusPoll)
            }
            outcome
        } ?: WifiJoinResult.Unknown
    }

    private companion object {
        const val TAG = "Wifi"
        /** Spec: the scanner's join timeout, used when the answer did not say. */
        val DEFAULT_TIMEOUT = 60.seconds
        /** App choice: extra wait for the result event beyond the scanner's timeout. */
        val RESULT_MARGIN = 10.seconds
        /** App choice: how long after the scanner's timeout the status is still asked. */
        val RECOVERY_WINDOW = 30.seconds
        val STATUS_POLL = 2.seconds
    }
}
