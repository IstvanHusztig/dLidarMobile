package com.dlidar.mobile.domain.model

/** How a request to join a network ended. */
sealed interface WifiJoinResult {
    /** [ip] is null when the scanner has no address yet. */
    data class Connected(val ssid: String, val ip: String?) : WifiJoinResult
    data object WrongPassword : WifiJoinResult
    /** The network was not found; the scanner may be out of its range. */
    data object NotFound : WifiJoinResult
    /** No answer in time. A network out of range can end this way too. */
    data object Timeout : WifiJoinResult
    /** Anything else; [message] is the scanner's readable text, shown as it is. */
    data class Failed(val message: String) : WifiJoinResult
    /** The result was lost with the link, and the scanner's status did not tell it. */
    data object Unknown : WifiJoinResult
}

/** What wifi.status reports. [ssid] and [ip] are null when the scanner is not connected. */
data class ScannerWifiStatus(
    val state: WifiState,
    val ssid: String?,
    val ip: String?,
    /** The newest join since dLidar started, or null. */
    val last: LastJoin?,
)

sealed interface WifiState {
    data object Connected : WifiState
    data object Connecting : WifiState
    data object Disconnected : WifiState
    /** No Wi-Fi device, or NetworkManager is not running. */
    data object Unavailable : WifiState
    data class Unknown(val raw: String) : WifiState
}

/** [result] is null while that join is still running. */
data class LastJoin(val ssid: String, val result: WifiJoinResult?)
