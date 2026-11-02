package com.dlidar.mobile.testing

import com.dlidar.mobile.data.protocol.transport.ByteTransport
import kotlinx.coroutines.CoroutineScope

/** The fake obeys the same transport contract as the BLE implementation. */
class FakeScannerContractTest : ByteTransportContract() {

    override fun createPeer(scope: CoroutineScope): Peer = object : Peer {
        private val fake = FakeScanner(mtu = 100).apply { protocolEnabled = false }
        override val transport: ByteTransport = fake
        override val target = "fake"
        override val expectedMaxWritePiece = 97

        override fun peerSends(bytes: ByteArray) = fake.notifyRaw(bytes)
        override fun peerReceived(): List<ByteArray> = fake.writtenPieces
        override fun peerDropsLink() = fake.dropLink()
    }
}
