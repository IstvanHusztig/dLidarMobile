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
    /** The scanner speaks another protocol version; nothing is sent until the app is updated. */
    data object IncompatibleProtocol : DomainError
    /** The scanner rejected the request. Unknown codes land here too, as generic errors. */
    data class Remote(val code: String, val message: String) : DomainError
}
