package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.scanner.session.PendingOperations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOperationsTest {
    private val event = JsonObject(mapOf("op" to JsonPrimitive(7)))

    @Test
    fun `a result reaches the request with its op, once`() {
        val operations = PendingOperations()
        val waiting = operations.register(7)
        assertTrue(operations.complete(7, event))
        assertEquals(event, waiting.getCompleted())
        assertFalse(operations.complete(7, event))
        assertFalse(operations.complete(8, event))
    }

    @Test
    fun `the session end, forgetting and a wrapped id release the waiting request`() {
        val operations = PendingOperations()
        val forgotten = operations.register(1)
        operations.forget(1)
        assertNull(forgotten.getCompleted())

        val old = operations.register(2)
        val wrapped = operations.register(2)
        assertNull(old.getCompleted())
        assertFalse(wrapped.isCompleted)

        operations.close()
        assertNull(wrapped.getCompleted())
        assertNull(operations.register(3).getCompleted())
    }
}
