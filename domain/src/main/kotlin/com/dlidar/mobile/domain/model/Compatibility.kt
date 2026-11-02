package com.dlidar.mobile.domain.model

/** Whether this app and the scanner speak a common protocol version. */
sealed interface Compatibility {
    /** False when nothing but hello may be sent. */
    val allowsRequests: Boolean get() = true

    /** Not known yet, or the scanner did not answer hello. */
    data object Unknown : Compatibility
    data object Compatible : Compatibility

    /**
     * The link is up, but the scanner has not answered any request, for example because its
     * echo test service runs instead of the protocol server. App choice: hello is asked again
     * now and then, and nothing else is sent.
     */
    data object NotAnswering : Compatibility {
        override val allowsRequests get() = false
    }

    /** The scanner only speaks newer protocol versions; the user should update the app. */
    data class UpdateAppRequired(val serverProtocol: Int?) : Compatibility {
        override val allowsRequests get() = false
    }

    /** The scanner only speaks older protocol versions; the user should update the scanner. */
    data class UpdateScannerRequired(val serverProtocol: Int?) : Compatibility {
        override val allowsRequests get() = false
    }
}
