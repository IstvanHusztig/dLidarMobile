package com.dlidar.mobile.infra.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.transport.DownReason
import kotlinx.coroutines.CancellationException

/** Opens a GATT connection to a device by its address. Cancelling the call closes it. */
@SuppressLint("MissingPermission")
internal class AndroidGattConnector(
    private val context: Context,
    private val logger: Logger,
) : GattConnector {

    override suspend fun connect(address: String): GattConnectOutcome {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return GattConnectOutcome.Failed(DownReason.BluetoothOff)
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return GattConnectOutcome.Failed(DownReason.ConnectFailed(null))
        }
        val connection = AndroidGattConnection(logger)
        val gatt = try {
            // Deprecated in API 37; this overload is the one that exists back to minSdk 26.
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, connection, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            return GattConnectOutcome.Failed(DownReason.PermissionMissing)
        } ?: return GattConnectOutcome.Failed(DownReason.ConnectFailed(null))
        connection.attach(gatt)

        val ok = try {
            connection.awaitConnected()
        } catch (e: CancellationException) {
            connection.close()
            throw e
        }
        if (ok) return GattConnectOutcome.Connected(connection)
        connection.close()
        return GattConnectOutcome.Failed(DownReason.ConnectFailed(connection.lastStatus))
    }
}
