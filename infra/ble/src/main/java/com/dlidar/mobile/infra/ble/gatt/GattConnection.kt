package com.dlidar.mobile.infra.ble.gatt

import com.dlidar.mobile.data.protocol.transport.DownReason
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One GATT connection as suspend operations, one at a time. This seam keeps the connection
 * sequence in [com.dlidar.mobile.infra.ble.AndroidBleTransport] testable without Android.
 */
internal interface GattConnection {
    /** TX notifications in arrival order. Closed when the connection ends. */
    val notifications: ReceiveChannel<ByteArray>

    /** Completes with the GATT status when the connection ends, for any reason. */
    val disconnected: Deferred<Int>

    suspend fun discoverServices(): Boolean

    /** True when service discovery found the scanner's service with RX and TX. */
    fun hasScannerService(): Boolean

    /** Returns the negotiated MTU, or null when the request failed. */
    suspend fun requestMtu(mtu: Int): Int?

    /** Turns on TX notifications, including the CCCD write. */
    suspend fun enableTxNotifications(): GattWrite

    suspend fun writeRx(piece: ByteArray, withResponse: Boolean): GattWrite

    suspend fun close()
}

/**
 * Result of a GATT write. When the scanner needs an encrypted link and the phone is not bonded,
 * Android pairs on its own and repeats the write; [NotPaired] means that pairing failed.
 */
internal enum class GattWrite { Ok, Failed, NotPaired }

internal fun interface GattConnector {
    suspend fun connect(address: String): GattConnectOutcome
}

internal sealed interface GattConnectOutcome {
    data class Connected(val connection: GattConnection) : GattConnectOutcome
    data class Failed(val reason: DownReason) : GattConnectOutcome
}

/** Reports what stops connecting before it starts: Bluetooth off, permissions missing. */
internal fun interface BluetoothAvailability {
    fun problem(): DownReason?
}
