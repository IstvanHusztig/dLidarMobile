package com.dlidar.mobile.debug.traffic

import com.dlidar.mobile.data.protocol.session.Traffic
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class TrafficLogTest {

    private var now = 0L
    private val log = TrafficLog(capacity = 3, clock = { now++ })

    private val connection = object : ByteConnection {
        override val maxWritePiece = 514
        override val incoming: Flow<ByteArray> = emptyFlow()
        override suspend fun write(piece: ByteArray) = WriteResult.Ok
    }

    @Test
    fun `entries keep their order and kind, and the oldest go first when full`() {
        log.onTraffic(Traffic.Sent("a"))
        log.onTraffic(Traffic.Received("b"))
        log.onTraffic(Traffic.Dropped("a message of 99 bytes, over the limit"))
        log.onLink(LinkState.Up(connection))

        assertEquals(
            listOf(
                TrafficEntry(1, TrafficEntry.Kind.Received, "b"),
                TrafficEntry(2, TrafficEntry.Kind.Dropped, "dropped a message of 99 bytes, over the limit"),
                TrafficEntry(3, TrafficEntry.Kind.Link, "link up, write piece 514 B"),
            ),
            log.entries.value,
        )
    }

    @Test
    fun `idle is not logged, down names its reason`() {
        log.onLink(LinkState.Idle)
        log.onLink(LinkState.Down(DownReason.ConnectionLost(8)))
        assertEquals(listOf("link down: connection lost (status 8)"), log.entries.value.map { it.text })
    }

    @Test
    fun `export formats each entry and leaves out the oldest past the limit`() {
        log.onTraffic(Traffic.Sent("""{"id":1}"""))
        log.onTraffic(Traffic.Received("""{"id":1,"ok":true}"""))

        assertEquals(
            "00:00:00.000 -> {\"id\":1}\n00:00:00.001 <- {\"id\":1,\"ok\":true}",
            log.export(zone = ZoneOffset.UTC),
        )
        assertEquals(
            "(1 older entries left out)\n00:00:00.001 <- {\"id\":1,\"ok\":true}",
            log.export(maxChars = 40, zone = ZoneOffset.UTC),
        )
    }

    @Test
    fun `clear empties the log`() {
        log.onTraffic(Traffic.Sent("a"))
        log.clear()
        assertEquals(emptyList<TrafficEntry>(), log.entries.value)
    }
}
