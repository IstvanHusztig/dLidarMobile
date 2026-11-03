@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.ProtocolLimits
import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.protocol.framing.DecodedFrame
import com.dlidar.mobile.data.protocol.framing.FrameDecoder
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProtocolSessionTest {

    /** Plays the scanner side of one connection, byte for byte. */
    private class ScriptedConnection(override val maxWritePiece: Int = 20) : ByteConnection {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()
        val pieces = mutableListOf<ByteArray>()
        var failWrites = false
        /** Writes after this many pieces fail. */
        var failAfterPieces: Int? = null
        /** When set, the second piece of a request waits for it. */
        var secondPieceGate: CompletableDeferred<Unit>? = null

        override suspend fun write(piece: ByteArray): WriteResult {
            if (failWrites) return WriteResult.Failed("gatt error")
            failAfterPieces?.let { if (pieces.size >= it) return WriteResult.Failed("gatt error") }
            if (pieces.size == 1) secondPieceGate?.await()
            pieces += piece
            return WriteResult.Ok
        }

        fun requests(): List<JsonObject> {
            val decoder = FrameDecoder()
            return pieces.flatMap { decoder.feed(it) }
                .map { Json.parseToJsonElement((it as DecodedFrame.Line).text).jsonObject }
        }

        fun lastId(): Int = requests().last()["id"]!!.jsonPrimitive.int

        fun send(line: String) {
            channel.trySend((line + "\n").toByteArray())
        }

        fun end() {
            channel.close()
        }
    }

    /** Result: the "value" field. */
    private object ValueCommand : ProtocolCommand<Int> {
        override val name = "test.value"
        override fun args(): JsonObject? = null
        override fun decode(result: JsonObject): Int =
            result["value"]?.jsonPrimitive?.int ?: throw IllegalArgumentException("no value")
    }

    private class BigCommand(private val size: Int) : ProtocolCommand<Unit> {
        override val name = "test.big"
        override fun args() = buildJsonObject { put("blob", "b".repeat(size)) }
        override fun decode(result: JsonObject) = Unit
    }

    private val connection = ScriptedConnection()
    private val signals = mutableListOf<SessionSignal>()

    private fun TestScope.startSession(): DefaultProtocolSession =
        DefaultProtocolSession(connection, { signals += it }, backgroundScope, ProtocolLimits()).also { it.start() }

    @Test
    fun `a call sends a v1 request in pieces of at most maxWritePiece and matches its response`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()

        assertTrue(connection.pieces.all { it.size <= connection.maxWritePiece })
        val request = connection.requests().single()
        assertEquals(1, request["v"]!!.jsonPrimitive.int)
        assertEquals("test.value", request["cmd"]!!.jsonPrimitive.content)
        assertTrue("args" !in request)

        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{"value":42,"extra":true}}""")
        assertEquals(CallResult.Success(42), call.await())
    }

    @Test
    fun `several requests in flight are matched by id, also out of order`() = runTest {
        val session = startSession()
        val first = async { session.call(ValueCommand) }
        val second = async { session.call(ValueCommand) }
        runCurrent()
        val (id1, id2) = connection.requests().map { it["id"]!!.jsonPrimitive.int }

        connection.send("""{"id":$id2,"ok":true,"result":{"value":2}}""")
        connection.send("""{"id":$id1,"ok":true,"result":{"value":1}}""")

        assertEquals(CallResult.Success(1), first.await())
        assertEquals(CallResult.Success(2), second.await())
    }

    @Test
    fun `a request without answer times out after 5 s and a late answer is ignored`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()
        val lateId = connection.lastId()

        advanceTimeBy(4_900)
        assertTrue(call.isActive)
        advanceTimeBy(200)
        assertEquals(CallResult.Failure(CallFailure.Timeout), call.await())

        connection.send("""{"id":$lateId,"ok":true,"result":{"value":1}}""")
        val next = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{"value":7}}""")
        assertEquals(CallResult.Success(7), next.await())
    }

    @Test
    fun `an error response keeps its code, unknown codes stay generic`() = runTest {
        val session = startSession()
        val known = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":false,"error":{"code":"busy","message":"later"}}""")
        val unknown = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":false,"error":{"code":"on_fire","message":"?"}}""")

        assertEquals(ErrorCode.Busy, ((known.await() as CallResult.Failure).failure as CallFailure.Remote).error.code)
        assertEquals(
            ErrorCode.Other("on_fire"),
            ((unknown.await() as CallResult.Failure).failure as CallFailure.Remote).error.code,
        )
    }

    @Test
    fun `a result that does not fit the command is a bad result`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{}}""")
        assertTrue((call.await() as CallResult.Failure).failure is CallFailure.BadResult)
    }

    @Test
    fun `an unsolicited error goes to the listener`() = runTest {
        startSession()
        connection.send("""{"id":null,"ok":false,"error":{"code":"too_many_clients","message":"one phone only"}}""")
        runCurrent()
        assertEquals(ErrorCode.TooManyClients, (signals.single() as SessionSignal.UnsolicitedError).error.code)
    }

    @Test
    fun `too_large and bad_request without id answer the oldest pending request`() = runTest {
        val session = startSession()
        val first = async { session.call(ValueCommand) }
        val second = async { session.call(ValueCommand) }
        runCurrent()
        val secondId = connection.requests().last()["id"]!!.jsonPrimitive.int

        connection.send("""{"id":null,"ok":false,"error":{"code":"too_large","message":"line too long"}}""")
        val failure = (first.await() as CallResult.Failure).failure as CallFailure.Remote
        assertEquals(ErrorCode.TooLarge, failure.error.code)
        assertTrue(second.isActive)

        connection.send("""{"id":null,"ok":false,"error":{"code":"bad_request","message":"no id"}}""")
        assertEquals(ErrorCode.BadRequest, ((second.await() as CallResult.Failure).failure as CallFailure.Remote).error.code)
        assertTrue(signals.isEmpty())

        // A late answer for the request that already got the error is ignored.
        connection.send("""{"id":$secondId,"ok":true,"result":{"value":1}}""")
        runCurrent()
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `an error without id and without a pending request goes to the listener`() = runTest {
        startSession()
        connection.send("""{"id":null,"ok":false,"error":{"code":"bad_request","message":"no id"}}""")
        runCurrent()
        assertEquals(ErrorCode.BadRequest, (signals.single() as SessionSignal.UnsolicitedError).error.code)
    }

    @Test
    fun `too_many_clients never answers a pending request`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":null,"ok":false,"error":{"code":"too_many_clients","message":"one phone only"}}""")
        runCurrent()
        assertTrue(call.isActive)
        assertEquals(ErrorCode.TooManyClients, (signals.single() as SessionSignal.UnsolicitedError).error.code)
        call.cancel()
    }

    @Test
    fun `events reach the listener and a seq jump or dropped field adds a gap`() = runTest {
        startSession()
        connection.send("""{"event":"progress","seq":1,"data":{}}""")
        connection.send("""{"event":"progress","seq":3,"data":{}}""")
        connection.send("""{"event":"progress","seq":4,"dropped":5,"data":{}}""")
        connection.send("""{"event":"progress","seq":5,"data":{}}""")
        runCurrent()

        assertEquals(6, signals.size)
        assertEquals(SequenceGap(expected = 2, actual = 3, dropped = null), (signals[2] as SessionSignal.GapDetected).gap)
        assertEquals(SequenceGap(expected = 4, actual = 4, dropped = 5), (signals[4] as SessionSignal.GapDetected).gap)
    }

    @Test
    fun `onAnswer runs before a message that arrived after the response`() = runTest {
        val order = mutableListOf<String>()
        val listening = DefaultProtocolSession(connection, { order += "event" }, backgroundScope)
        listening.start()

        val call = async { listening.call(ValueCommand) { order += "answer" } }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{"value":1}}""")
        connection.send("""{"event":"state","seq":1,"data":{}}""")
        call.await()
        runCurrent()

        assertEquals(listOf("answer", "event"), order)
    }

    @Test
    fun `onSent gets the request id before the request is written`() = runTest {
        val session = startSession()
        var seen: Int? = null
        var writtenWhenSeen = -1
        val call = async {
            session.call(ValueCommand, onSent = { id ->
                seen = id
                writtenWhenSeen = connection.pieces.size
            })
        }
        runCurrent()

        assertEquals(connection.lastId(), seen)
        assertEquals(0, writtenWhenSeen)
        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{"value":1}}""")
        assertEquals(CallResult.Success(1), call.await())
    }

    @Test
    fun `lines that are not protocol messages are ignored`() = runTest {
        val session = startSession()
        connection.send("echo anything")
        connection.send("{broken")
        val call = async { session.call(ValueCommand) }
        runCurrent()
        connection.send("""{"id":${connection.lastId()},"ok":true,"result":{"value":3}}""")
        assertEquals(CallResult.Success(3), call.await())
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `the end of the link fails pending calls and later calls`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()
        connection.end()
        assertEquals(CallResult.Failure(CallFailure.LinkLost), call.await())
        assertEquals(CallResult.Failure(CallFailure.NotConnected), session.call(ValueCommand))
    }

    @Test
    fun `close fails pending calls`() = runTest {
        val session = startSession()
        val call = async { session.call(ValueCommand) }
        runCurrent()
        session.close()
        assertEquals(CallResult.Failure(CallFailure.LinkLost), call.await())
    }

    @Test
    fun `a request over the limit is refused before anything is written`() = runTest {
        val session = startSession()
        val result = session.call(BigCommand(5000))
        assertTrue((result as CallResult.Failure).failure is CallFailure.TooLarge)
        assertTrue(connection.pieces.isEmpty())
    }

    @Test
    fun `limits from hello apply to later requests`() = runTest {
        val session = startSession()
        session.applyLimits(maxRequestBytes = 64, maxMessageBytes = null)
        val result = session.call(BigCommand(100))
        assertTrue((result as CallResult.Failure).failure is CallFailure.TooLarge)
    }

    @Test
    fun `a request cut off after its first piece breaks the stream`() = runTest {
        val session = startSession()
        connection.failAfterPieces = 1

        val result = session.call(ValueCommand)

        assertEquals(CallResult.Failure(CallFailure.WriteFailed("gatt error")), result)
        assertEquals(listOf<SessionSignal>(SessionSignal.StreamBroken), signals)
        assertEquals(CallResult.Failure(CallFailure.NotConnected), session.call(ValueCommand))
    }

    @Test
    fun `a call cancelled mid-request still writes the whole request`() = runTest {
        val session = startSession()
        val gate = CompletableDeferred<Unit>()
        connection.secondPieceGate = gate
        val call = launch { session.call(ValueCommand) }
        runCurrent()
        assertEquals(1, connection.pieces.size)

        call.cancel()
        gate.complete(Unit)
        runCurrent()

        assertEquals("test.value", connection.requests().single()["cmd"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a message limit from hello applies to incoming messages`() = runTest {
        val session = startSession()
        session.applyLimits(maxRequestBytes = null, maxMessageBytes = 40)
        connection.send("""{"event":"progress","seq":1,"data":{"note":"${"x".repeat(60)}"}}""")
        connection.send("""{"event":"progress","seq":2,"data":{}}""")
        runCurrent()

        assertEquals(2L, (signals.first() as SessionSignal.EventReceived).event.seq)
    }

    @Test
    fun `only protocol messages count as received`() = runTest {
        val session = startSession()
        connection.send("echo hello")
        runCurrent()
        assertFalse(session.hasReceivedMessages)

        connection.send("""{"event":"state","seq":1,"data":{}}""")
        runCurrent()
        assertTrue(session.hasReceivedMessages)
    }

    @Test
    fun `a failed write fails the call`() = runTest {
        val session = startSession()
        connection.failWrites = true
        assertEquals(CallResult.Failure(CallFailure.WriteFailed("gatt error")), session.call(ValueCommand))
    }

    private object SecretCommand : ProtocolCommand<Unit> {
        override val name = "test.secret"
        override val sensitiveArgs = setOf("psk")
        override fun args() = buildJsonObject {
            put("ssid", "home")
            put("psk", "hunter22")
        }
        override fun decode(result: JsonObject) = Unit
    }

    @Test
    fun `the traffic listener sees sent lines redacted, received lines and dropped messages`() = runTest {
        val seen = mutableListOf<Traffic>()
        val session = DefaultProtocolSession(
            connection, { signals += it }, backgroundScope, ProtocolLimits(maxMessageBytes = 40),
            traffic = { seen += it },
        ).also { it.start() }
        val call = async { session.call(SecretCommand) }
        runCurrent()
        val id = connection.lastId()
        connection.send("""{"event":"progress","seq":1,"data":{"note":"${"x".repeat(60)}"}}""")
        connection.send("""{"id":$id,"ok":true,"result":{}}""")
        call.await()

        val sent = seen[0] as Traffic.Sent
        assertTrue(sent.line.contains("\"ssid\":\"home\""))
        assertFalse(sent.line.contains("hunter22"))
        assertTrue(connection.requests().single().toString().contains("hunter22"))
        assertTrue(seen[1] is Traffic.Dropped)
        assertEquals(Traffic.Received("""{"id":$id,"ok":true,"result":{}}"""), seen[2])
        assertEquals(3, seen.size)
    }
}
