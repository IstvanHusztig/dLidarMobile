package com.dlidar.mobile.domain.model

/** State of the link to the selected scanner, including automatic reconnection. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    /** Connecting; [attempt] counts attempts since the last successful connection, from 1. */
    data class Connecting(val attempt: Int) : ConnectionState

    data object Connected : ConnectionState

    /** The link failed or dropped; attempt [nextAttempt] starts after [retryInMillis]. */
    data class WaitingToRetry(val nextAttempt: Int, val retryInMillis: Long) : ConnectionState

    /** Bluetooth is off. The app reconnects on its own once it is switched on. */
    data object WaitingForBluetooth : ConnectionState

    /** The scanner refused this phone. The app does not retry on its own. */
    data class Refused(val reason: Reason) : ConnectionState {
        enum class Reason { AnotherPhoneConnected }
    }

    /** Connecting cannot succeed until the user fixes something. The app does not retry on its own. */
    data class Failed(val reason: Reason) : ConnectionState {
        enum class Reason { BluetoothOff, PermissionMissing, NotADLidar, Other }
    }
}
