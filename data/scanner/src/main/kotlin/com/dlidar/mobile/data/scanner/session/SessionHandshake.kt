package com.dlidar.mobile.data.scanner.session

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.protocol.session.ProtocolSession
import com.dlidar.mobile.data.scanner.CallRetry
import com.dlidar.mobile.data.scanner.CompatibilityChecker
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerInput
import com.dlidar.mobile.data.scanner.StatusRefresher
import com.dlidar.mobile.data.scanner.command.HelloCommand
import com.dlidar.mobile.data.scanner.command.InfoGetCommand
import com.dlidar.mobile.domain.model.Compatibility
import kotlinx.coroutines.delay

/**
 * What happens on every link up: hello first, because its answer decides compatibility; then
 * status.get (through the refresher) and info.get together, each repeated until it succeeds.
 * After a protocol mismatch nothing more is sent.
 */
internal class SessionHandshake(
    private val session: ProtocolSession,
    private val refresher: StatusRefresher,
    private val record: (ScannerInput) -> Unit,
    /** Called once hello allows further requests; until then nobody else may send. */
    private val onReady: () -> Unit,
    private val logger: Logger,
) {
    suspend fun run() {
        val hello = session.call(HelloCommand)
        val compatibility = CompatibilityChecker.check(hello)
        record(ScannerInput.CompatibilityResolved(compatibility))
        if (compatibility is Compatibility.UpdateAppRequired) {
            logger.warn(TAG, "scanner speaks protocol ${compatibility.serverProtocol}; not sending further requests")
            return
        }
        if (hello is CallResult.Success) session.applyLimits(hello.value.maxRequestBytes, hello.value.maxMessageBytes)
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

    private companion object {
        const val TAG = "Scanner"
    }
}
