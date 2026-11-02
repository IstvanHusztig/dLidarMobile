package com.dlidar.mobile.infra.ble.gatt

import android.bluetooth.BluetoothManager
import android.content.Context
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.infra.ble.BlePermissions

internal class AndroidBluetoothAvailability(private val context: Context) : BluetoothAvailability {
    override fun problem(): DownReason? {
        if (!BlePermissions.canConnect(context)) return DownReason.PermissionMissing
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return DownReason.BluetoothOff
        return null
    }
}
