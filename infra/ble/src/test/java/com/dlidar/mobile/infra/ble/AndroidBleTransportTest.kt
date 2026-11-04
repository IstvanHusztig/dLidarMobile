@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.infra.ble

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.protocol.transport.WriteResult
import com.dlidar.mobile.infra.ble.gatt.BondOutcome
import com.dlidar.mobile.infra.ble.gatt.GattConnectOutcome
import com.dlidar.mobile.infra.ble.gatt.GattConnection
import com.dlidar.mobile.infra.ble.gatt.GattWrite
import com.dlidar.mobile.testing.ByteTransportContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scriptable GATT connection that records the order of setup steps. */
internal class FakeGattConnection(
    var negotiatedMtu: Int? = 247,
    var hasService: Boolean = true,
    var cccdWrite: GattWrite = GattWrite.Ok,
    var writes: GattWrite = GattWrite.Ok,
    /** When set, requestMtu waits for it, so a test can act in the middle of setup. */
    var mtuGate: CompletableDeferred<Unit>? = null,
) : GattConnection {
    val steps = mutableListOf<String>()
    val written = mutableListOf<ByteArray>()
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val ended = CompletableDeferred<Int>()
    override val notifications: ReceiveChannel<ByteArray> = channel
    override val disconnected: Deferred<Int> = ended

    override suspend fun discoverServices(): Boolean = true.also { steps += "discover" }
    override fun hasScannerService(): Boolean = hasService
    override suspend fun requestMtu(mtu: Int): Int? {
        steps += "mtu $mtu"
        mtuGate?.await()
        return negotiatedMtu
    }
    override suspend fun enableTxNotifications(): GattWrite = cccdWrite.also { steps += "notify" }

    override suspend fun writeRx(piece: ByteArray, withResponse: Boolean): GattWrite {
        steps += "write"
        if (writes == GattWrite.Ok) written += piece
        return writes
    }

    override suspend fun close() {
        steps += "close"
        drop(-1)
    }

    fun notify(bytes: ByteArray) {
        channel.trySend(bytes)
    }

    fun drop(status: Int) {
        ended.complete(status)
        channel.close()
    }
}

class AndroidBleTransportTest {

    private fun transport(
        gatt: FakeGattConnection,
        scope: CoroutineScope,
        problem: DownReason? = null,
        bond: BondOutcome = BondOutcome.Bonded,
    ) =
        AndroidBleTransport(
            connector = { GattConnectOutcome.Connected(gatt) },
            bonder = { address -> bond.also { gatt.steps += "bond $address" } },
            availability = { problem },
            scope = scope,
            logger = Logger.None,
        )

    @Test
    fun `the MTU is requested and notifications are on before the link is up`() = runTest {
        val gatt = FakeGattConnection()
        val transport = transport(gatt, backgroundScope)

        val connection = (transport.connect("AA") as ConnectResult.Connected).connection

        assertEquals(listOf("discover", "mtu 247", "bond AA", "notify"), gatt.steps)
        assertEquals(244, connection.maxWritePiece)
        connection.write(byteArrayOf(1))
        assertEquals("write", gatt.steps.last())
    }

    @Test
    fun `write pieces stop at the 512 byte attribute limit`() {
        assertEquals(244, AndroidBleTransport.writePiece(247))
        assertEquals(512, AndroidBleTransport.writePiece(515))
        // Android 14 and newer negotiate 517 whatever the app asks for.
        assertEquals(512, AndroidBleTransport.writePiece(517))
    }

    @Test
    fun `a failed MTU request continues with the default MTU`() = runTest {
        val transport = transport(FakeGattConnection(negotiatedMtu = null), backgroundScope)
        val connection = (transport.connect("AA") as ConnectResult.Connected).connection
        assertEquals(20, connection.maxWritePiece)
    }

    @Test
    fun `a device without the service is not a scanner`() = runTest {
        val gatt = FakeGattConnection(hasService = false)
        val transport = transport(gatt, backgroundScope)

        assertEquals(ConnectResult.Failed(DownReason.ServiceNotFound), transport.connect("AA"))
        assertEquals(LinkState.Down(DownReason.ServiceNotFound), transport.link.value)
        assertTrue("close" in gatt.steps)
    }

    @Test
    fun `failing to enable notifications fails the setup`() = runTest {
        val transport = transport(FakeGattConnection(cccdWrite = GattWrite.Failed), backgroundScope)
        val result = transport.connect("AA") as ConnectResult.Failed
        assertTrue(result.reason is DownReason.SetupFailed)
    }

    @Test
    fun `notifications refused for want of pairing fail the setup as not paired`() = runTest {
        val gatt = FakeGattConnection(cccdWrite = GattWrite.NotPaired)
        val transport = transport(gatt, backgroundScope)

        assertEquals(ConnectResult.Failed(DownReason.NotPaired), transport.connect("AA"))
        assertEquals(LinkState.Down(DownReason.NotPaired), transport.link.value)
        assertTrue("close" in gatt.steps)
    }

    @Test
    fun `a failed bonding fails the setup as not paired before notifications are asked for`() = runTest {
        val gatt = FakeGattConnection()
        val transport = transport(gatt, backgroundScope, bond = BondOutcome.Failed)

        assertEquals(ConnectResult.Failed(DownReason.NotPaired), transport.connect("AA"))
        assertFalse("notify" in gatt.steps)
        assertEquals("close", gatt.steps.last())
    }

    @Test
    fun `bonding that cannot start leaves pairing to Android`() = runTest {
        val gatt = FakeGattConnection()
        val transport = transport(gatt, backgroundScope, bond = BondOutcome.Unavailable)

        assertTrue(transport.connect("AA") is ConnectResult.Connected)
        assertEquals(listOf("discover", "mtu 247", "bond AA", "notify"), gatt.steps)
    }

    @Test
    fun `a write refused for want of pairing ends the link as not paired`() = runTest {
        val gatt = FakeGattConnection(writes = GattWrite.NotPaired)
        val transport = transport(gatt, backgroundScope)
        val connection = (transport.connect("AA") as ConnectResult.Connected).connection

        assertEquals(WriteResult.Failed("not paired"), connection.write(byteArrayOf(1)))
        runCurrent()

        assertEquals(LinkState.Down(DownReason.NotPaired), transport.link.value)
        assertEquals("close", gatt.steps.last())
        assertEquals(WriteResult.Failed("not connected"), connection.write(byteArrayOf(1)))
    }

    @Test
    fun `cancelling connect during setup closes the GATT connection`() = runTest {
        val gatt = FakeGattConnection(mtuGate = CompletableDeferred())
        val transport = transport(gatt, backgroundScope)
        val connecting = launch { transport.connect("AA") }
        runCurrent()
        assertEquals("mtu 247", gatt.steps.last())

        connecting.cancel()
        runCurrent()

        assertEquals("close", gatt.steps.last())
        assertFalse(transport.link.value is LinkState.Up)
    }

    @Test
    fun `a setup that hangs times out and closes the GATT connection`() = runTest {
        val gatt = FakeGattConnection(mtuGate = CompletableDeferred())
        val transport = transport(gatt, backgroundScope)

        val result = transport.connect("AA") as ConnectResult.Failed

        assertEquals(DownReason.SetupFailed("timeout"), result.reason)
        assertEquals("close", gatt.steps.last())
    }

    @Test
    fun `a link lost after Up is reported with its status`() = runTest {
        val gatt = FakeGattConnection()
        val transport = transport(gatt, backgroundScope)
        transport.connect("AA")
        gatt.drop(19)
        runCurrent()
        assertEquals(LinkState.Down(DownReason.ConnectionLost(19)), transport.link.value)
    }

    @Test
    fun `bluetooth off stops before connecting`() = runTest {
        val gatt = FakeGattConnection()
        val transport = transport(gatt, backgroundScope, problem = DownReason.BluetoothOff)
        assertEquals(ConnectResult.Failed(DownReason.BluetoothOff), transport.connect("AA"))
        assertTrue(gatt.steps.isEmpty())
    }
}

/** The BLE transport obeys the same contract as the fake scanner. */
class AndroidBleTransportContractTest : ByteTransportContract() {
    override fun createPeer(scope: CoroutineScope): Peer = object : Peer {
        private val gatt = FakeGattConnection(negotiatedMtu = 185)
        override val transport: ByteTransport = AndroidBleTransport(
            connector = { GattConnectOutcome.Connected(gatt) },
            bonder = { BondOutcome.Bonded },
            availability = { null },
            scope = scope,
            logger = Logger.None,
        )
        override val target = "AA:BB"
        override val expectedMaxWritePiece = 182
        override fun peerSends(bytes: ByteArray) = gatt.notify(bytes)
        override fun peerReceived(): List<ByteArray> = gatt.written
        override fun peerDropsLink() = gatt.drop(8)
    }
}
