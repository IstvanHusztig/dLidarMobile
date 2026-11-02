package com.dlidar.mobile.data.protocol.framing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSplitterTest {

    private val splitter = FrameSplitter()

    private fun pieces(message: String, maxPiece: Int): List<ByteArray> =
        (splitter.split(message, maxPiece) as SplitResult.Pieces).pieces

    @Test
    fun `pieces are never larger than the limit and join to the message plus newline`() {
        val message = "m".repeat(1000)
        for (maxPiece in listOf(20, 182, 244, 1000, 1001)) {
            val result = pieces(message, maxPiece)
            assertTrue(result.all { it.size <= maxPiece })
            assertEquals(message + "\n", result.joinToString("") { it.decodeToString() })
        }
    }

    @Test
    fun `a short message fits one piece`() {
        assertEquals(1, pieces("""{"v":1}""", 244).size)
    }

    @Test
    fun `the request limit counts the newline`() {
        val fits = "a".repeat(4095)
        val tooLarge = "a".repeat(4096)
        assertTrue(splitter.split(fits, 244) is SplitResult.Pieces)
        assertEquals(SplitResult.TooLarge(4097), splitter.split(tooLarge, 244))
    }

    @Test
    fun `the limit counts bytes, not characters`() {
        val small = FrameSplitter(maxRequestBytes = 4)
        assertEquals(SplitResult.TooLarge(5), small.split("éé", 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a raw newline inside a message is refused`() {
        splitter.split("a\nb", 20)
    }
}
