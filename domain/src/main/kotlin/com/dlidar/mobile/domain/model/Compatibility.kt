package com.dlidar.mobile.domain.model

/** Whether this app speaks the protocol version of the scanner. */
sealed interface Compatibility {
    /** Not known yet, or the scanner did not answer hello. */
    data object Unknown : Compatibility
    data object Compatible : Compatibility
    /** The scanner speaks a different protocol; the user should update the app. */
    data class UpdateAppRequired(val serverProtocol: Int?) : Compatibility
}
