package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.codec.ProtocolEvent
import com.dlidar.mobile.data.protocol.codec.RemoteError

/** Something the scanner sent that is not the answer to a call. */
sealed interface SessionSignal {
    data class EventReceived(val event: ProtocolEvent) : SessionSignal

    /** Events were missed; the state should be fetched again with status.get. */
    data class GapDetected(val gap: SequenceGap) : SessionSignal

    data class UnsolicitedError(val error: RemoteError) : SessionSignal

    /**
     * A request was cut off after its first piece, so the scanner holds a partial line and the
     * byte stream can no longer be trusted. The link must be reopened. Sent by the writing call,
     * not by the reader.
     */
    data object StreamBroken : SessionSignal
}

/**
 * Receives signals in arrival order, interleaved correctly with the `onAnswer` callbacks of
 * calls. Apart from [SessionSignal.StreamBroken] they come from the session's reader. It must
 * return quickly and must not suspend.
 */
fun interface SessionListener {
    fun onSignal(signal: SessionSignal)
}

/** [actual] is null when the event had no seq; [dropped] is the scanner's count, if present. */
data class SequenceGap(val expected: Long, val actual: Long?, val dropped: Long?)
