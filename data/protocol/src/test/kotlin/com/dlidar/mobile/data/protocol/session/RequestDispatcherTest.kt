package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDispatcherTest {

    private object Command : ProtocolCommand<Unit> {
        override val name = "test"
        override fun args(): JsonObject? = null
        override fun decode(result: JsonObject) = Unit
    }

    private fun call() = PendingCall(Command, null)

    @Test
    fun `ids increase from the first id`() {
        val dispatcher = RequestDispatcher(firstId = 1)
        assertEquals(1, dispatcher.register(call()))
        assertEquals(2, dispatcher.register(call()))
    }

    @Test
    fun `ids wrap after the maximum to 0`() {
        val dispatcher = RequestDispatcher(firstId = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, dispatcher.register(call()))
        assertEquals(0, dispatcher.register(call()))
    }

    @Test
    fun `take reports whether the response came in request order`() {
        val dispatcher = RequestDispatcher()
        val first = dispatcher.register(call())!!
        val second = dispatcher.register(call())!!
        assertFalse(dispatcher.take(second)!!.inOrder)
        assertTrue(dispatcher.take(first)!!.inOrder)
        assertNull(dispatcher.take(first))
    }

    @Test
    fun `close fails pending calls and refuses new ones`() {
        val dispatcher = RequestDispatcher()
        val pending = call()
        dispatcher.register(pending)
        dispatcher.close(CallFailure.LinkLost)
        assertEquals(CallResult.Failure(CallFailure.LinkLost), runBlocking { pending.result.await() })
        assertNull(dispatcher.register(call()))
    }
}
