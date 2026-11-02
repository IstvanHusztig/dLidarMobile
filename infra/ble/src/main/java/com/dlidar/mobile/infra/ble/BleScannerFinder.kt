package com.dlidar.mobile.infra.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.port.ScannerFinder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for scanners that advertise the service UUID (the name is not matched). Each result updates
 * the list, strongest signal first. Android limits apps to about five scan starts per 30 s.
 */
class BleScannerFinder(context: Context) : ScannerFinder {

    private val context = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun find(): Flow<Discovery> = callbackFlow {
        val problem = problem()
        if (problem != null) {
            send(Discovery.Failed(problem))
            close()
            return@callbackFlow
        }
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            send(Discovery.Failed(Discovery.Problem.BluetoothOff))
            close()
            return@callbackFlow
        }

        val found = LinkedHashMap<String, ScannerCandidate>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                add(result)
                trySend(Discovery.Found(found.values.sortedByDescending { it.rssi }))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::add)
                trySend(Discovery.Found(found.values.sortedByDescending { it.rssi }))
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(Discovery.Failed(Discovery.Problem.ScanFailed))
                close()
            }

            private fun add(result: ScanResult) {
                val address = result.device.address
                found[address] = ScannerCandidate(address, result.scanRecord?.deviceName, result.rssi)
            }
        }
        // The scanner always puts its service UUID in the advertisement itself; the name may
        // carry a device id or move to the scan response, so it is not matched.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        send(Discovery.Found(emptyList()))
        try {
            scanner.startScan(filters, settings, callback)
        } catch (e: SecurityException) {
            send(Discovery.Failed(Discovery.Problem.PermissionMissing))
            close()
        }
        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (e: Exception) {
                // Bluetooth was switched off or the permission revoked; the scan is gone anyway.
            }
        }
    }

    private fun problem(): Discovery.Problem? {
        if (!BlePermissions.canScan(context)) return Discovery.Problem.PermissionMissing
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return Discovery.Problem.BluetoothOff
        if (BlePermissions.locationOffBlocksScan(context)) return Discovery.Problem.LocationOff
        return null
    }
}
