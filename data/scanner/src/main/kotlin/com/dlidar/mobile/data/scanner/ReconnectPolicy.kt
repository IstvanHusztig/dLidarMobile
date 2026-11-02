package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.transport.DownReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** App policy, not part of the protocol: when to try again after the link failed or dropped. */
class ReconnectPolicy(
    private val initialDelay: Duration = 1.seconds,
    private val maxDelay: Duration = 30.seconds,
    /** While Bluetooth is off, how often to check whether it is back. The check is local. */
    val bluetoothPollInterval: Duration = 3.seconds,
) {
    /** Delay after [failures] failures in a row (1 or more): 1 s, 2 s, 4 s, ... up to the maximum. */
    fun delayAfter(failures: Int): Duration {
        require(failures >= 1) { "failures start at 1" }
        val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return (initialDelay * (1 shl doublings)).coerceAtMost(maxDelay)
    }

    /** Whether trying again can help without the user doing something first. */
    fun shouldRetry(reason: DownReason): Boolean = when (reason) {
        DownReason.Closed,
        DownReason.ServiceNotFound,
        DownReason.NotPaired,
        DownReason.PermissionMissing -> false
        DownReason.BluetoothOff,
        is DownReason.ConnectFailed,
        is DownReason.ConnectionLost,
        is DownReason.SetupFailed -> true
    }

    private companion object {
        const val MAX_DOUBLINGS = 16
    }
}
