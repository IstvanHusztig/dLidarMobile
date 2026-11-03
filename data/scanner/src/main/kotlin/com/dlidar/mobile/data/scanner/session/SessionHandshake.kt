package com.dlidar.mobile.data.scanner.session

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.protocol.session.ProtocolSession
import com.dlidar.mobile.data.scanner.CallRetry
import com.dlidar.mobile.data.scanner.CompatibilityChecker
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerInput
import com.dlidar.mobile.data.scanner.StatusRefresher
import com.dlidar.mobile.data.scanner.command.HelloCommand
import com.dlidar.mobile.data.scanner.command.InfoGetCommand
import com.dlidar.mobile.data.scanner.command.RECORDING_COMMANDS
import com.dlidar.mobile.data.scanner.command.WIFI_COMMANDS
import com.dlidar.mobile.domain.model.Compatibility
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What happens on every link up: hello first, because its answer decides compatibility and
 * which features are offered (recording control, Wi-Fi setup); then
 * status.get (through the refresher) and info.get together, each repeated until it succeeds.
 * After a protocol mismatch nothing more is sent. While the scanner sends nothing that is a
 * protocol message (its echo test service, for example), only hello is repeated, every
 * [notAnsweringRetry] (app choice).
 */
internal class SessionHandshake(
    private val session: ProtocolSession,
    private val refresher: StatusRefresher,
    private val record: (ScannerInput) -> Unit,
    /** Called once hello allows further requests; until then nobody else may send. */
    private val onReady: () -> Unit,
    private val logger: Logger,
    private val notAnsweringRetry: Duration = NOT_ANSWERING_RETRY,
) {
    suspend fun run() {
        var hello = session.call(HelloCommand)
        while (hello.isTimeout() && !session.hasReceivedMessages) {
            record(ScannerInput.CompatibilityResolved(Compatibility.NotAnswering))
            logger.warn(TAG, "no protocol message on this link; asking hello again in $notAnsweringRetry")
            waitUntilRetryOrAnswer()
            hello = session.call(HelloCommand)
        }
        val compatibility = CompatibilityChecker.check(hello)
        record(ScannerInput.CompatibilityResolved(compatibility))
        if (!compatibility.allowsRequests) {
            logger.warn(TAG, "no common protocol version ($compatibility); not sending further requests")
            return
        }
        if (hello is CallResult.Success) session.applyLimits(hello.value.maxRequestBytes, hello.value.maxMessageBytes)
        // An older scanner answers these commands with unknown_command, so they are offered
        // only when hello lists them.
        val commands = (hello as? CallResult.Success)?.value?.commands.orEmpty()
        record(ScannerInput.FeaturesKnown(commands.containsAll(RECORDING_COMMANDS), commands.containsAll(WIFI_COMMANDS)))
        onReady()
        refresher.start()
        refresher.request()
        fetchInfo()
    }

    private suspend fun fetchInfo() {
        var attempt = 0
        while (true) {
            val result = session.call(InfoGetCommand) { record(ScannerInput.InfoLoaded(ProtocolMappers.info(it))) }
            if (result !is CallResult.Failure) return
            if (!CallRetry.worthRetrying(result.failure)) {
                logger.warn(TAG, "info.get failed: ${result.failure}")
                return
            }
            delay(CallRetry.delayAfter(++attempt))
        }
    }

    /** Waits [notAnsweringRetry], or less once the scanner sent a protocol message (a slow first answer). */
    private suspend fun waitUntilRetryOrAnswer() {
        var waited = Duration.ZERO
        while (waited < notAnsweringRetry && !session.hasReceivedMessages) {
            delay(ANSWER_POLL)
            waited += ANSWER_POLL
        }
    }

    private fun CallResult<*>.isTimeout() = this is CallResult.Failure && failure == CallFailure.Timeout

    companion object {
        private const val TAG = "Scanner"
        val NOT_ANSWERING_RETRY: Duration = 30.seconds
        private val ANSWER_POLL = 1.seconds
    }
}
