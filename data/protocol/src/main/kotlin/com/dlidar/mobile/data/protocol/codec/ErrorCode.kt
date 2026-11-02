package com.dlidar.mobile.data.protocol.codec

/** Error code of a failed response. Codes this app does not know map to [Other]. */
sealed interface ErrorCode {
    val wire: String

    data object BadRequest : ErrorCode { override val wire = "bad_request" }
    data object UnknownCommand : ErrorCode { override val wire = "unknown_command" }
    data object UnsupportedVersion : ErrorCode { override val wire = "unsupported_version" }
    data object TooLarge : ErrorCode { override val wire = "too_large" }
    data object TooManyClients : ErrorCode { override val wire = "too_many_clients" }
    data object Busy : ErrorCode { override val wire = "busy" }
    data class Other(override val wire: String) : ErrorCode

    companion object {
        fun fromWire(raw: String): ErrorCode = when (raw) {
            BadRequest.wire -> BadRequest
            UnknownCommand.wire -> UnknownCommand
            UnsupportedVersion.wire -> UnsupportedVersion
            TooLarge.wire -> TooLarge
            TooManyClients.wire -> TooManyClients
            Busy.wire -> Busy
            else -> Other(raw)
        }
    }
}
