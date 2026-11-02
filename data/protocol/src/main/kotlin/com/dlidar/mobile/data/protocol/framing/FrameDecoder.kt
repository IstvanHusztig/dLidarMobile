package com.dlidar.mobile.data.protocol.framing

import com.dlidar.mobile.data.protocol.ProtocolLimits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Joins notification pieces into newline-terminated messages.
 *
 * Bytes are collected up to `\n` and decoded as UTF-8 only once a line is complete, so a
 * character split across two pieces is safe. The decoder does not rely on each message starting
 * in a fresh notification; several messages in one piece work too. A line longer than
 * [maxMessageBytes] is dropped up to its newline and reported as [DecodedFrame.Oversized].
 *
 * Not thread-safe; one reader feeds it.
 */
class FrameDecoder(maxMessageBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES) {

    @Volatile
    var maxMessageBytes: Int = maxMessageBytes
        set(value) {
            require(value > 0) { "maxMessageBytes must be positive" }
            field = value
        }

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var discarding = false
    private var discarded = 0

    init {
        require(maxMessageBytes > 0) { "maxMessageBytes must be positive" }
    }

    fun feed(piece: ByteArray): List<DecodedFrame> {
        var frames: MutableList<DecodedFrame>? = null
        for (byte in piece) {
            if (byte == NEWLINE) {
                val frame = finishLine() ?: continue
                (frames ?: ArrayList<DecodedFrame>(1).also { frames = it }).add(frame)
            } else if (discarding) {
                discarded++
            } else if (size >= maxMessageBytes) {
                discarding = true
                discarded = size + 1
                size = 0
            } else {
                append(byte)
            }
        }
        return frames ?: emptyList()
    }

    /** Drops a partial line. Call it whenever the link changes. */
    fun reset() {
        size = 0
        discarding = false
        discarded = 0
    }

    private fun finishLine(): DecodedFrame? {
        if (discarding) {
            val frame = DecodedFrame.Oversized(discarded)
            reset()
            return frame
        }
        if (size == 0) return null
        val bytes = buffer.copyOf(size)
        size = 0
        return decodeUtf8(bytes)
    }

    private fun decodeUtf8(bytes: ByteArray): DecodedFrame = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        DecodedFrame.Line(decoder.decode(ByteBuffer.wrap(bytes)).toString())
    } catch (e: CharacterCodingException) {
        DecodedFrame.Malformed(bytes.size)
    }

    private fun append(byte: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(minOf(buffer.size * 2, maxMessageBytes))
        buffer[size++] = byte
    }

    private companion object {
        const val NEWLINE: Byte = 0x0A
        const val INITIAL_CAPACITY = 256
    }
}
