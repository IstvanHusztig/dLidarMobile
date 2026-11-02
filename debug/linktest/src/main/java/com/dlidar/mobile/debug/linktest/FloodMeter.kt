package com.dlidar.mobile.debug.linktest

/**
 * Checks the answer to `flood N SIZE`: N numbered lines `seq=00001 xxx…` arrive complete, in
 * order, and at what rate. Pure; time comes in as milliseconds.
 */
class FloodMeter(private val expected: Int, private val startedAtMillis: Long) {
    private var received = 0
    private var outOfOrder = 0
    private var bytes = 0L
    private var lastSeq = 0
    private var lastAtMillis = startedAtMillis
    private val sizes = sortedSetOf<Int>()

    /** Returns true when [line] belongs to the flood. */
    fun onLine(line: String, nowMillis: Long): Boolean {
        val seq = SEQ.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        received++
        if (seq != lastSeq + 1) outOfOrder++
        lastSeq = seq
        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
        bytes += lineBytes
        sizes += lineBytes
        lastAtMillis = nowMillis
        return true
    }

    fun stats(): FloodStats {
        val seconds = (lastAtMillis - startedAtMillis).coerceAtLeast(1) / 1000.0
        return FloodStats(
            expected = expected,
            received = received,
            outOfOrder = outOfOrder,
            bytes = bytes,
            bytesPerSecond = (bytes / seconds).toLong(),
            lineSizes = sizes.toList(),
        )
    }

    private companion object {
        val SEQ = Regex("^seq=(\\d+)")
    }
}

data class FloodStats(
    val expected: Int,
    val received: Int,
    val outOfOrder: Int,
    val bytes: Long,
    val bytesPerSecond: Long,
    /** Distinct line sizes seen, newline included. */
    val lineSizes: List<Int>,
) {
    val missing: Int get() = (expected - received).coerceAtLeast(0)
}
