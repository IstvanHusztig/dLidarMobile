package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.codec.RemoteError

sealed interface CallResult<out R> {
    data class Success<out R>(val value: R) : CallResult<R>
    data class Failure(val failure: CallFailure) : CallResult<Nothing>
}

sealed interface CallFailure {
    /** The scanner answered `ok:false`. */
    data class Remote(val error: RemoteError) : CallFailure
    /** No answer within the request timeout. */
    data object Timeout : CallFailure
    /** The link dropped while the request was pending. */
    data object LinkLost : CallFailure
    /** The session had already ended. */
    data object NotConnected : CallFailure
    /** The request is larger than the scanner accepts; nothing was sent. */
    data class TooLarge(val bytes: Int) : CallFailure
    data class WriteFailed(val reason: String) : CallFailure
    /** The scanner answered `ok:true`, but the result did not fit the command. */
    data class BadResult(val reason: String) : CallFailure
}
