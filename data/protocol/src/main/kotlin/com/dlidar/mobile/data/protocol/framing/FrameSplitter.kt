package com.dlidar.mobile.data.protocol.framing

import com.dlidar.mobile.data.protocol.ProtocolLimits

/**
 * Turns one message into newline-terminated pieces of at most `maxPiece` bytes. A message larger
 * than [maxRequestBytes] (newline included) is refused before anything is sent.
 */
class FrameSplitter(maxRequestBytes: Int = ProtocolLimits.MAX_REQUEST_BYTES) {

    @Volatile
    var maxRequestBytes: Int = maxRequestBytes
        set(value) {
            require(value > 0) { "maxRequestBytes must be positive" }
            field = value
        }

    init {
        require(maxRequestBytes > 0) { "maxRequestBytes must be positive" }
    }

    fun split(message: String, maxPiece: Int): SplitResult {
        require(maxPiece > 0) { "maxPiece must be positive" }
        require('\n' !in message) { "a message must not contain a raw newline" }
        val bytes = (message + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxRequestBytes) return SplitResult.TooLarge(bytes.size)
        val pieces = (bytes.indices step maxPiece).map { start ->
            bytes.copyOfRange(start, minOf(start + maxPiece, bytes.size))
        }
        return SplitResult.Pieces(pieces)
    }
}

sealed interface SplitResult {
    class Pieces(val pieces: List<ByteArray>) : SplitResult
    data class TooLarge(val bytes: Int) : SplitResult
}
