package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.WifiScan

/** The phone's own search for Wi-Fi networks. */
fun interface WifiNetworkScanner {
    /** Searches once and returns what the phone found, unmerged. */
    suspend fun scan(): WifiScan
}
