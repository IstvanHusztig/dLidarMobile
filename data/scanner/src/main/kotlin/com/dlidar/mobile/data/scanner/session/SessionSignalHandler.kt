package com.dlidar.mobile.data.scanner.session

import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.SessionListener
import com.dlidar.mobile.data.protocol.session.SessionSignal
import com.dlidar.mobile.data.scanner.ScannerInput
import com.dlidar.mobile.data.scanner.event.EventContext
import com.dlidar.mobile.data.scanner.event.EventDecoding
import com.dlidar.mobile.data.scanner.event.ScannerEventDecoders
import com.dlidar.mobile.domain.port.Clock

/** Why a session has to end although its link is still up. */
internal enum class SessionStop {
    /** too_many_clients: another phone is connected. */
    Refused,
    /** The byte stream is out of step; the link must be reopened. */
    Broken,
}

/** Routes what one session reports: events become inputs, gaps a refresh, and some errors end it. */
internal class SessionSignalHandler(
    private val decoders: ScannerEventDecoders,
    private val clock: Clock,
    private val newWarningId: () -> Long,
    private val record: (ScannerInput) -> Unit,
    private val onGap: () -> Unit,
    private val onStop: (SessionStop) -> Unit,
    private val logger: Logger,
) : SessionListener {

    override fun onSignal(signal: SessionSignal) {
        when (signal) {
            is SessionSignal.EventReceived -> onEvent(signal)
            is SessionSignal.GapDetected -> onGap()
            is SessionSignal.UnsolicitedError ->
                if (signal.error.code == ErrorCode.TooManyClients) onStop(SessionStop.Refused)
            SessionSignal.StreamBroken -> onStop(SessionStop.Broken)
        }
    }

    private fun onEvent(signal: SessionSignal.EventReceived) {
        when (val decoded = decoders.decode(signal.event, EventContext(clock.now(), newWarningId))) {
            is EventDecoding.Decoded -> record(decoded.input)
            EventDecoding.Unknown -> logger.debug(TAG) { "ignored unknown event ${signal.event.name}" }
            is EventDecoding.Invalid -> logger.warn(TAG, "ignored ${signal.event.name} event: ${decoded.reason}")
        }
    }

    private companion object {
        const val TAG = "Scanner"
    }
}
