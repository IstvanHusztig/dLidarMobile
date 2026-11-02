package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.session.CallFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * App policy for repeating a request that must succeed on every connection (status.get and
 * info.get): only failures that can pass are retried, with a growing delay.
 */
internal object CallRetry {
    private val MAX_DELAY = 8.seconds

    fun worthRetrying(failure: CallFailure): Boolean = when (failure) {
        CallFailure.Timeout -> true
        is CallFailure.Remote -> failure.error.code == ErrorCode.Busy
        // The session is over, or the answer will not change.
        CallFailure.LinkLost,
        CallFailure.NotConnected,
        is CallFailure.WriteFailed,
        is CallFailure.TooLarge,
        is CallFailure.BadResult -> false
    }

    /** 1 s, 2 s, 4 s, then 8 s. */
    fun delayAfter(attempt: Int): Duration = (1.seconds * (1 shl (attempt - 1).coerceIn(0, 3))).coerceAtMost(MAX_DELAY)
}
