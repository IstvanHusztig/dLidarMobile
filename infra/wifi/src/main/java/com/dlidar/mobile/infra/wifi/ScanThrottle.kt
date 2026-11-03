package com.dlidar.mobile.infra.wifi

/**
 * Android lets a foreground app start at most 4 Wi-Fi scans every 2 minutes; more are refused
 * and only return the last results. This keeps count, so a refused scan is known in advance
 * and the cached results are shown with a note instead.
 */
internal class ScanThrottle(
    private val nowMillis: () -> Long,
    private val maxScans: Int = 4,
    private val windowMillis: Long = 120_000,
) {
    private val started = ArrayDeque<Long>()

    /** Records a scan and returns true when one is allowed now. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMillis()
        while (started.isNotEmpty() && now - started.first() >= windowMillis) started.removeFirst()
        if (started.size >= maxScans) return false
        started.addLast(now)
        return true
    }
}
