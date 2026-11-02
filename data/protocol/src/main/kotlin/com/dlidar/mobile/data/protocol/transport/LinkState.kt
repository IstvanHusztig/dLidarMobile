package com.dlidar.mobile.data.protocol.transport

sealed interface LinkState {
    /** Never connected. */
    data object Idle : LinkState
    data object Connecting : LinkState
    data class Up(val connection: ByteConnection) : LinkState
    data class Down(val reason: DownReason) : LinkState
}

sealed interface DownReason {
    /** Closed by this app. */
    data object Closed : DownReason
    data class ConnectFailed(val status: Int?) : DownReason
    data class ConnectionLost(val status: Int?) : DownReason
    /** The device does not offer the scanner's GATT service. */
    data object ServiceNotFound : DownReason
    /** Connected, but a setup step (MTU, notifications) failed. */
    data class SetupFailed(val step: String) : DownReason
    data object BluetoothOff : DownReason
    data object PermissionMissing : DownReason
}
