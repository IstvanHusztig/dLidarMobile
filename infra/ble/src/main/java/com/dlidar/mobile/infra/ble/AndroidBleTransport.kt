package com.dlidar.mobile.infra.ble

import android.content.Context
import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.protocol.transport.WriteResult
import com.dlidar.mobile.infra.ble.gatt.AndroidBluetoothAvailability
import com.dlidar.mobile.infra.ble.gatt.AndroidGattConnector
import com.dlidar.mobile.infra.ble.gatt.BluetoothAvailability
import com.dlidar.mobile.infra.ble.gatt.GattConnectOutcome
import com.dlidar.mobile.infra.ble.gatt.GattConnection
import com.dlidar.mobile.infra.ble.gatt.GattConnector
import com.dlidar.mobile.infra.ble.gatt.GattWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ByteTransport] over BLE GATT. The link is reported `Up` only after the services are found,
 * the MTU is negotiated (247 requested) and TX notifications are on, so nothing is written to RX
 * before that. Writes use write-with-response, so GATT does the flow control (an app choice).
 * The scanner needs a bonded phone; when Android's own pairing attempt fails, the link goes down
 * with [DownReason.NotPaired].
 */
class AndroidBleTransport internal constructor(
    private val connector: GattConnector,
    private val availability: BluetoothAvailability,
    private val scope: CoroutineScope,
    private val logger: Logger,
    /** Long enough for Android to pair during setup, including the user's confirmation. */
    private val setupTimeout: Duration = 45.seconds,
) : ByteTransport {

    private val linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val link: StateFlow<LinkState> = linkState.asStateFlow()

    private val mutex = Mutex()
    private var active: BleConnection? = null

    override suspend fun connect(target: String): ConnectResult = mutex.withLock {
        closeActive()
        availability.problem()?.let { return@withLock fail(it) }
        linkState.value = LinkState.Connecting

        // A GATT connection that is not adopted as the active one is closed on every way out:
        // failure, timeout, cancellation, even when the connector's result arrived as the
        // timeout or a cancel struck.
        var opened: GattConnection? = null
        var adopted = false
        try {
            val outcome = withTimeoutOrNull(setupTimeout) {
                connector.connect(target).also { if (it is GattConnectOutcome.Connected) opened = it.connection }
            }
            val gatt = when (outcome) {
                null -> return@withLock fail(DownReason.ConnectFailed(null))
                is GattConnectOutcome.Failed -> return@withLock fail(outcome.reason)
                is GattConnectOutcome.Connected -> outcome.connection
            }
            when (val setup = withTimeoutOrNull(setupTimeout) { setUp(gatt) } ?: Setup.Failed(DownReason.SetupFailed("timeout"))) {
                is Setup.Failed -> fail(setup.reason)
                is Setup.Ready -> {
                    val connection = BleConnection(gatt, maxWritePiece = setup.mtu - ATT_HEADER_BYTES, onNotPaired = ::dropNotPaired)
                    adopted = true
                    active = connection
                    linkState.value = LinkState.Up(connection)
                    scope.launch { onDisconnected(connection, gatt.disconnected.await()) }
                    logger.debug(TAG) { "link up, MTU ${setup.mtu}" }
                    ConnectResult.Connected(connection)
                }
            }
        } finally {
            if (!adopted) opened?.close()
        }
    }

    override suspend fun disconnect() {
        mutex.withLock {
            val hadLink = closeActive()
            if (hadLink || linkState.value !is LinkState.Idle) linkState.value = LinkState.Down(DownReason.Closed)
        }
    }

    private suspend fun setUp(gatt: GattConnection): Setup {
        if (!gatt.discoverServices()) return Setup.Failed(DownReason.SetupFailed("service discovery"))
        if (!gatt.hasScannerService()) return Setup.Failed(DownReason.ServiceNotFound)
        val mtu = gatt.requestMtu(BleUuids.REQUESTED_MTU) ?: run {
            logger.warn(TAG, "MTU request failed; continuing with the default MTU ${BleUuids.DEFAULT_MTU}")
            BleUuids.DEFAULT_MTU
        }
        return when (gatt.enableTxNotifications()) {
            GattWrite.Ok -> Setup.Ready(mtu)
            GattWrite.NotPaired -> Setup.Failed(DownReason.NotPaired)
            GattWrite.Failed -> Setup.Failed(DownReason.SetupFailed("notifications"))
        }
    }

    private suspend fun onDisconnected(connection: BleConnection, status: Int) {
        mutex.withLock {
            if (active !== connection) return@withLock
            active = null
            connection.close()
            linkState.value = LinkState.Down(DownReason.ConnectionLost(status))
            logger.debug(TAG) { "link lost, status $status" }
        }
    }

    /** Ends a link whose writes are refused because the phone is not paired. */
    private fun dropNotPaired(connection: BleConnection) {
        scope.launch {
            mutex.withLock {
                if (active !== connection) return@withLock
                active = null
                connection.close()
                linkState.value = LinkState.Down(DownReason.NotPaired)
                logger.debug(TAG) { "link closed: not paired" }
            }
        }
    }

    /** Callers hold [mutex]. Returns whether a connection was open. */
    private suspend fun closeActive(): Boolean {
        val connection = active ?: return false
        active = null
        connection.close()
        return true
    }

    private fun fail(reason: DownReason): ConnectResult {
        linkState.value = LinkState.Down(reason)
        logger.debug(TAG) { "connect failed: $reason" }
        return ConnectResult.Failed(reason)
    }

    private sealed interface Setup {
        data class Ready(val mtu: Int) : Setup
        data class Failed(val reason: DownReason) : Setup
    }

    private class BleConnection(
        private val gatt: GattConnection,
        override val maxWritePiece: Int,
        private val onNotPaired: (BleConnection) -> Unit,
    ) : ByteConnection {
        @Volatile private var open = true
        override val incoming: Flow<ByteArray> = gatt.notifications.receiveAsFlow()

        override suspend fun write(piece: ByteArray): WriteResult {
            if (!open) return WriteResult.Failed("not connected")
            require(piece.size <= maxWritePiece) { "piece of ${piece.size} bytes exceeds $maxWritePiece" }
            return when (gatt.writeRx(piece, withResponse = true)) {
                GattWrite.Ok -> WriteResult.Ok
                GattWrite.Failed -> WriteResult.Failed("GATT write failed")
                GattWrite.NotPaired -> {
                    open = false
                    onNotPaired(this)
                    WriteResult.Failed("not paired")
                }
            }
        }

        suspend fun close() {
            open = false
            gatt.close()
        }
    }

    companion object {
        private const val TAG = "Ble"
        private const val ATT_HEADER_BYTES = 3

        fun create(context: Context, scope: CoroutineScope, logger: Logger): AndroidBleTransport {
            val appContext = context.applicationContext
            return AndroidBleTransport(
                connector = AndroidGattConnector(appContext, logger),
                availability = AndroidBluetoothAvailability(appContext),
                scope = scope,
                logger = logger,
            )
        }
    }
}
