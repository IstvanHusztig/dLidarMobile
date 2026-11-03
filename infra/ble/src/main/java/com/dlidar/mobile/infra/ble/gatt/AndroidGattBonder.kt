package com.dlidar.mobile.infra.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.dlidar.mobile.data.protocol.log.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Bonds with `createBond()` and waits for `ACTION_BOND_STATE_CHANGED`. Bonding before the first
 * encrypted operation means one pairing dialog: otherwise the scanner's Security Request on the
 * TX subscription and the refused first RX write each open one.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattBonder(
    private val context: Context,
    private val logger: Logger,
) : GattBonder {

    override suspend fun ensureBonded(address: String): BondOutcome {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BondOutcome.Unavailable
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return BondOutcome.Unavailable
        }
        return try {
            bond(device)
        } catch (e: SecurityException) {
            logger.warn(TAG, "bonding without permission", e)
            BondOutcome.Unavailable
        }
    }

    private suspend fun bond(device: BluetoothDevice): BondOutcome {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return BondOutcome.Bonded
        // Bond state changes as (previous, new).
        val states = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (changed?.address != device.address) return
                states.trySend(
                    intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR) to
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
                )
            }
        }
        // Exported, so it does not depend on which process the Bluetooth stack sends from; only
        // the system can send this protected broadcast.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            // Checked again after registering, so a change in between is not missed.
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return BondOutcome.Bonded
                BluetoothDevice.BOND_NONE -> if (!device.createBond()) {
                    logger.warn(TAG, "createBond refused; Android pairs on the first encrypted operation instead")
                    return BondOutcome.Unavailable
                }
                // BOND_BONDING: a pairing is already under way; wait for its end.
            }
            logger.debug(TAG) { "bonding with ${device.address}" }
            // The scanner drops a phone that has not subscribed to TX within 30 s, so waiting
            // longer only ends as a lost link.
            val outcome = withTimeoutOrNull(BOND_TIMEOUT) {
                for ((previous, state) in states) {
                    when {
                        state == BluetoothDevice.BOND_BONDED -> return@withTimeoutOrNull BondOutcome.Bonded
                        // Only the end of a pairing attempt counts, not a stale change to NONE.
                        state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING ->
                            return@withTimeoutOrNull BondOutcome.Failed
                    }
                }
                BondOutcome.Failed
            } ?: BondOutcome.Failed
            if (outcome == BondOutcome.Bonded) logger.debug(TAG) { "bonded" } else logger.warn(TAG, "bonding failed")
            return outcome
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private companion object {
        const val TAG = "Bond"
        val BOND_TIMEOUT = 30.seconds
    }
}
