package com.dlidar.mobile.data.protocol.codec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCodecTest {

    private val codec = MessageCodec()

    @Test
    fun `a request is compact v1 JSON without args when there are none`() {
        assertEquals("""{"v":1,"id":7,"cmd":"status.get"}""", codec.encodeRequest(7, "status.get", null))
    }

    @Test
    fun `a request carries its args`() {
        val line = codec.encodeRequest(1, "x", buildJsonObject { put("a", 2) })
        assertEquals(2, Json.parseToJsonElement(line).jsonObject["args"]!!.jsonObject["a"]!!.jsonPrimitive.int)
        assertFalse('\n' in line)
    }

    @Test
    fun `an ok response with unknown fields`() {
        val inbound = codec.decode("""{"id":3,"ok":true,"result":{"x":1},"future":"yes"}""")
        val response = inbound as Inbound.Response
        assertEquals(3, response.id)
        assertEquals(1, (response.body as ResponseBody.Ok).result["x"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an error response keeps its code, message and extra fields`() {
        val inbound = codec.decode(
            """{"id":4,"ok":false,"error":{"code":"unsupported_version","message":"no","supported":[1]}}""",
        )
        val error = ((inbound as Inbound.Response).body as ResponseBody.Err).error
        assertEquals(ErrorCode.UnsupportedVersion, error.code)
        assertEquals("no", error.message)
        assertTrue("supported" in error.details)
    }

    @Test
    fun `an unknown error code becomes a generic one`() {
        val inbound = codec.decode("""{"id":4,"ok":false,"error":{"code":"melted","message":"m"}}""")
        assertEquals(ErrorCode.Other("melted"), ((inbound as Inbound.Response).body as ResponseBody.Err).error.code)
    }

    @Test
    fun `an error with a null id is unsolicited`() {
        val inbound = codec.decode("""{"id":null,"ok":false,"error":{"code":"too_many_clients","message":"busy"}}""")
        assertEquals(ErrorCode.TooManyClients, (inbound as Inbound.UnsolicitedError).error.code)
    }

    @Test
    fun `an event with dropped and unknown fields`() {
        val inbound = codec.decode("""{"event":"progress","seq":9,"dropped":2,"data":{"chunks":1},"new":0}""")
        val event = (inbound as Inbound.Event).event
        assertEquals("progress", event.name)
        assertEquals(9L, event.seq)
        assertEquals(2L, event.dropped)
        assertEquals(1, event.data["chunks"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an event without data gets an empty object`() {
        val event = (codec.decode("""{"event":"ping","seq":1}""") as Inbound.Event).event
        assertTrue(event.data.isEmpty())
    }

    @Test
    fun `lines that are not protocol messages are unparsed`() {
        assertTrue(codec.decode("echo anything") is Inbound.Unparsed)
        assertTrue(codec.decode("[1,2]") is Inbound.Unparsed)
        assertTrue(codec.decode("""{"hello":1}""") is Inbound.Unparsed)
        assertTrue(codec.decode("""{"id":"7","ok":true}""") is Inbound.Unparsed)
        assertTrue(codec.decode("""{"id":-1,"ok":true}""") is Inbound.Unparsed)
    }
}
