@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.testing

import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour every [ByteTransport] must have, so implementations can replace each other (Liskov).
 * Run it for each implementation by subclassing.
 */
abstract class ByteTransportContract {

    /** Controls the far end of a transport under test. */
    interface Peer {
        val transport: ByteTransport
        val target: String
        val expectedMaxWritePiece: Int
        fun peerSends(bytes: ByteArray)
        fun peerReceived(): List<ByteArray>
        fun peerDropsLink()
    }

    protected abstract fun createPeer(scope: CoroutineScope): Peer

    private fun contractTest(body: suspend TestScope.(Peer) -> Unit) = runTest {
        body(createPeer(backgroundScope))
    }

    @Test
    fun `connect reports Up with the same connection`() = contractTest { peer ->
        val result = peer.transport.connect(peer.target)
        val connection = (result as ConnectResult.Connected).connection
        assertSame(connection, (peer.transport.link.value as LinkState.Up).connection)
        assertEquals(peer.expectedMaxWritePiece, connection.maxWritePiece)
    }

    @Test
    fun `written pieces reach the peer and notifications arrive in order`() = contractTest { peer ->
        val connection = (peer.transport.connect(peer.target) as ConnectResult.Connected).connection
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { connection.incoming.collect { received += it } }
        runCurrent()

        assertEquals(WriteResult.Ok, connection.write("ping\n".toByteArray()))
        peer.peerSends("one".toByteArray())
        peer.peerSends("two".toByteArray())
        runCurrent()

        assertArrayEquals("ping\n".toByteArray(), peer.peerReceived().last())
        assertEquals(listOf("one", "two"), received.map { it.decodeToString() })
    }

    @Test
    fun `incoming completes and the link is Down when the peer drops it`() = contractTest { peer ->
        val connection = (peer.transport.connect(peer.target) as ConnectResult.Connected).connection
        val collected = backgroundScope.launch { connection.incoming.toList() }
        runCurrent()

        peer.peerDropsLink()
        runCurrent()

        assertTrue(collected.isCompleted)
        assertTrue((peer.transport.link.value as LinkState.Down).reason is DownReason.ConnectionLost)
    }

    @Test
    fun `disconnect reports Down Closed and later writes fail`() = contractTest { peer ->
        val connection = (peer.transport.connect(peer.target) as ConnectResult.Connected).connection
        peer.transport.disconnect()
        runCurrent()

        assertEquals(LinkState.Down(DownReason.Closed), peer.transport.link.value)
        assertTrue(connection.write("x".toByteArray()) is WriteResult.Failed)
    }
}
