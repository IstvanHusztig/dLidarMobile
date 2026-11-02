package com.dlidar.mobile.data.protocol.framing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDecoderTest {

    private val decoder = FrameDecoder(maxMessageBytes = 32)

    private fun lines(vararg pieces: String): List<DecodedFrame> =
        pieces.flatMap { decoder.feed(it.toByteArray()) }

    @Test
    fun `a whole message in one piece`() {
        assertEquals(listOf(DecodedFrame.Line("""{"a":1}""")), lines("{\"a\":1}\n"))
    }

    @Test
    fun `a message split over several pieces is joined`() {
        assertEquals(emptyList<DecodedFrame>(), lines("{\"a\"", ":"))
        assertEquals(listOf(DecodedFrame.Line("""{"a":1}""")), lines("1}\n"))
    }

    @Test
    fun `two messages merged in one piece are both returned`() {
        assertEquals(
            listOf(DecodedFrame.Line("one"), DecodedFrame.Line("two")),
            lines("one\ntwo\n"),
        )
    }

    @Test
    fun `the end of one message and the start of the next in one piece`() {
        assertEquals(listOf(DecodedFrame.Line("first")), lines("fir", "st\nsec"))
        assertEquals(listOf(DecodedFrame.Line("second")), lines("ond\n"))
    }

    @Test
    fun `a multibyte character split across pieces is decoded correctly`() {
        val bytes = "\"é€\"\n".toByteArray(Charsets.UTF_8)
        val frames = decoder.feed(bytes.copyOfRange(0, 2)) +
            decoder.feed(bytes.copyOfRange(2, 4)) +
            decoder.feed(bytes.copyOfRange(4, bytes.size))
        assertEquals(listOf(DecodedFrame.Line("\"é€\"")), frames)
    }

    @Test
    fun `an oversized line is dropped up to its newline and decoding resumes`() {
        val long = "x".repeat(40)
        val frames = lines(long.substring(0, 20), long.substring(20) + "\n", "ok\n")
        assertEquals(listOf(DecodedFrame.Oversized(40), DecodedFrame.Line("ok")), frames)
    }

    @Test
    fun `a line of exactly the limit is accepted`() {
        val exact = "y".repeat(32)
        assertEquals(listOf(DecodedFrame.Line(exact)), lines(exact + "\n"))
    }

    @Test
    fun `empty lines are ignored`() {
        assertEquals(listOf(DecodedFrame.Line("a")), lines("\n\na\n\n"))
    }

    @Test
    fun `invalid UTF-8 is reported and decoding resumes`() {
        val frames = decoder.feed(byteArrayOf(0xC3.toByte(), 0x28, 0x0A)) + decoder.feed("ok\n".toByteArray())
        assertEquals(listOf(DecodedFrame.Malformed(2), DecodedFrame.Line("ok")), frames)
    }

    @Test
    fun `reset drops a partial line`() {
        assertTrue(lines("partial").isEmpty())
        decoder.reset()
        assertEquals(listOf(DecodedFrame.Line("next")), lines("next\n"))
    }
}
