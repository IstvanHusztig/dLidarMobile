package com.dlidar.mobile.data.protocol.framing

sealed interface DecodedFrame {
    /** One complete message, without its newline. */
    data class Line(val text: String) : DecodedFrame

    /** A message longer than the limit was dropped. */
    data class Oversized(val bytes: Int) : DecodedFrame

    /** A message that was not valid UTF-8 was dropped. */
    data class Malformed(val bytes: Int) : DecodedFrame
}
