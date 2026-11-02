package com.dlidar.mobile.infra.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.infra.ble.BleUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [GattConnection] over [BluetoothGatt]. Android allows one outstanding GATT operation, so every
 * operation takes [opMutex] and waits for its own callback. Callbacks arrive on a binder thread.
 *
 * Permissions are checked by [AndroidBluetoothAvailability] before connecting; a revoked
 * permission shows up as a [SecurityException], which ends the connection.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattConnection(private val logger: Logger) : BluetoothGattCallback(), GattConnection {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val notifications: ReceiveChannel<ByteArray> = channel

    private val disconnectedStatus = CompletableDeferred<Int>()
    override val disconnected: Deferred<Int> = disconnectedStatus

    private val connected = CompletableDeferred<Boolean>()
    private val opMutex = Mutex()
    private val released = AtomicBoolean(false)
    @Volatile private var pending: PendingOp? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var tx: BluetoothGattCharacteristic? = null

    /** The status of the last connection change, for error reports. */
    @Volatile var lastStatus: Int = BluetoothGatt.GATT_SUCCESS
        private set

    fun attach(gatt: BluetoothGatt) {
        this.gatt = gatt
    }

    /** Suspends until the link is connected (true) or failed (false). */
    suspend fun awaitConnected(): Boolean = connected.await()

    override suspend fun discoverServices(): Boolean {
        var discoveredOn: BluetoothGatt? = null
        val result = runOp(OpKind.DISCOVER) {
            discoveredOn = it
            it.discoverServices()
        } ?: return false
        // A link that dropped right after discovery is a setup failure (retried), not a missing service.
        if (result.status != BluetoothGatt.GATT_SUCCESS || disconnectedStatus.isCompleted) return false
        val service = discoveredOn?.getService(BleUuids.SERVICE)
        rx = service?.getCharacteristic(BleUuids.RX)
        tx = service?.getCharacteristic(BleUuids.TX)
        return true
    }

    override fun hasScannerService(): Boolean = rx != null && tx != null

    override suspend fun requestMtu(mtu: Int): Int? {
        val result = runOp(OpKind.MTU) { it.requestMtu(mtu) } ?: return null
        return result.value.takeIf { result.status == BluetoothGatt.GATT_SUCCESS }
    }

    @Suppress("DEPRECATION")
    override suspend fun enableTxNotifications(): GattWrite {
        val tx = tx ?: return GattWrite.Failed
        val cccd = tx.getDescriptor(BleUuids.CCCD) ?: return GattWrite.Failed
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val result = runOp(OpKind.DESCRIPTOR_WRITE, WRITE_TIMEOUT) { gatt ->
            if (!gatt.setCharacteristicNotification(tx, true)) return@runOp false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.value = value
                gatt.writeDescriptor(cccd)
            }
        }
        return writeOutcome("CCCD write", result)
    }

    @Suppress("DEPRECATION")
    override suspend fun writeRx(piece: ByteArray, withResponse: Boolean): GattWrite {
        val rx = rx ?: return GattWrite.Failed
        val type = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val result = runOp(OpKind.CHARACTERISTIC_WRITE, WRITE_TIMEOUT) { gatt ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(rx, piece, type) == BluetoothStatusCodes.SUCCESS
            } else {
                rx.writeType = type
                rx.value = piece
                gatt.writeCharacteristic(rx)
            }
        }
        return writeOutcome("RX write", result)
    }

    private fun writeOutcome(what: String, result: OpResult?): GattWrite = when {
        result == null -> GattWrite.Failed
        result.status == BluetoothGatt.GATT_SUCCESS -> GattWrite.Ok
        result.status in PAIRING_FAILED -> GattWrite.NotPaired.also { logger.warn(TAG, "$what refused, not paired (status ${result.status})") }
        else -> GattWrite.Failed.also { logger.warn(TAG, "$what failed, status ${result.status}") }
    }

    /**
     * Disconnects first and waits briefly for the link to go down, then releases the client.
     * Closing at once can leave the link up on the scanner until its supervision timeout, and a
     * one-phone scanner would then refuse the next connection.
     */
    override suspend fun close() = withContext(NonCancellable) {
        val current = gatt
        if (current != null && !disconnectedStatus.isCompleted) {
            try {
                current.disconnect()
            } catch (e: SecurityException) {
                logger.warn(TAG, "disconnecting GATT without permission", e)
            }
            withTimeoutOrNull(DISCONNECT_TIMEOUT) { disconnectedStatus.await() }
        }
        current?.let(::release)
        ended(LOCAL_CLOSE_STATUS)
    }

    // --- Callbacks (binder thread) ---------------------------------------------------------

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        lastStatus = status
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            connected.complete(true)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            connected.complete(false)
            ended(status)
            release(gatt)
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = finish(OpKind.DISCOVER, status)

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = finish(OpKind.MTU, status, mtu)

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
        finish(OpKind.DESCRIPTOR_WRITE, status)

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
        finish(OpKind.CHARACTERISTIC_WRITE, status)

    /** Android 13 and newer. Not calling super keeps the deprecated overload from running too. */
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid == BleUuids.TX) channel.trySend(value.copyOf())
    }

    /** Android 12 and older. The value is copied at once, before the next notification reuses it. */
    @Deprecated("Called on Android 12 and older only")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (characteristic.uuid == BleUuids.TX) characteristic.value?.let { channel.trySend(it.copyOf()) }
    }

    // --- Operations -------------------------------------------------------------------------

    private suspend fun runOp(kind: OpKind, timeout: Duration = OP_TIMEOUT, start: (BluetoothGatt) -> Boolean): OpResult? = opMutex.withLock {
        val gatt = gatt ?: return@withLock null
        if (disconnectedStatus.isCompleted) return@withLock null
        val op = PendingOp(kind)
        pending = op
        try {
            val started = try {
                start(gatt)
            } catch (e: SecurityException) {
                logger.warn(TAG, "$kind without permission", e)
                false
            }
            if (!started) return@withLock null
            withTimeoutOrNull(timeout) { op.result.await() }
        } finally {
            pending = null
        }
    }

    private fun finish(kind: OpKind, status: Int, value: Int = 0) {
        val op = pending
        // A callback for another kind (for example an MTU change the scanner started) is not ours.
        if (op != null && op.kind == kind) op.result.complete(OpResult(status, value))
    }

    /** Closes the client once, whichever of close() and the disconnect callback comes first. */
    private fun release(gatt: BluetoothGatt) {
        if (!released.compareAndSet(false, true)) return
        this.gatt = null
        try {
            gatt.close()
        } catch (e: SecurityException) {
            logger.warn(TAG, "closing GATT without permission", e)
        }
    }

    private fun ended(status: Int) {
        disconnectedStatus.complete(status)
        channel.close()
        pending?.result?.complete(OpResult(BluetoothGatt.GATT_FAILURE, 0))
    }

    private enum class OpKind { DISCOVER, MTU, DESCRIPTOR_WRITE, CHARACTERISTIC_WRITE }

    private class PendingOp(val kind: OpKind) {
        val result = CompletableDeferred<OpResult>()
    }

    private class OpResult(val status: Int, val value: Int)

    private companion object {
        const val TAG = "Gatt"
        const val LOCAL_CLOSE_STATUS = -1
        val OP_TIMEOUT = 10.seconds
        /** A write can wait for Android to pair first, including the user's confirmation. */
        val WRITE_TIMEOUT = 30.seconds
        /** The stack's GATT_AUTH_FAIL (0x89); Android has no public constant for it. */
        const val GATT_AUTH_FAIL = 0x89
        /** The write was refused for want of an encrypted link, after Android's pairing attempt. */
        val PAIRING_FAILED = setOf(
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
            GATT_AUTH_FAIL,
        )
        val DISCONNECT_TIMEOUT = 2.seconds
    }
}
