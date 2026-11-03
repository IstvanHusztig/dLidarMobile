package com.dlidar.mobile.domain.result

/** Why a request to the scanner failed. */
sealed interface DomainError {
    data object NotConnected : DomainError
    data object LinkLost : DomainError
    data object Timeout : DomainError
    data object RequestTooLarge : DomainError
    data object Busy : DomainError
    /** The scanner answered, but the answer could not be read. */
    data object InvalidResponse : DomainError
    /** No common protocol version, or the scanner does not answer; nothing is sent. */
    data object IncompatibleProtocol : DomainError
    /** The scanner does not take this command (hello did not list it); nothing is sent. */
    data object NotSupported : DomainError
    /** The scanner rejected the request. Unknown codes land here too, as generic errors. */
    data class Remote(val code: String, val message: String) : DomainError
}
