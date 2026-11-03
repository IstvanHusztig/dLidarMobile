package com.dlidar.mobile.domain.model

/**
 * Turns the phone's raw scan results into the list the user picks from: hidden (empty) SSIDs
 * are dropped, entries with the same SSID merge into the strongest one (whose security is
 * shown), and the strongest come first.
 */
object NetworkListMerger {
    fun merge(found: List<WifiNetwork>): List<WifiNetwork> =
        found.filter { it.ssid.isNotEmpty() }
            .groupBy { it.ssid }
            .map { (_, same) -> same.maxBy { it.rssi } }
            .sortedWith(compareByDescending<WifiNetwork> { it.rssi }.thenBy { it.ssid })
}
