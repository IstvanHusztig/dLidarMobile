package com.dlidar.mobile.domain.model

/** One search for networks by the phone. */
sealed interface WifiScan {
    /**
     * [cached]: Android refused a new scan (it allows only a few every two minutes), so these
     * are the results of an earlier one.
     */
    data class Networks(val networks: List<WifiNetwork>, val cached: Boolean) : WifiScan
    data class Failed(val problem: Problem) : WifiScan

    enum class Problem { PermissionMissing, LocationOff, WifiOff, ScanFailed }
}
