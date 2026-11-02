package com.dlidar.mobile.domain.model

/** Progress of a search for nearby scanners. */
sealed interface Discovery {
    data class Found(val candidates: List<ScannerCandidate>) : Discovery
    data class Failed(val problem: Problem) : Discovery

    enum class Problem { BluetoothOff, PermissionMissing, LocationOff, ScanFailed }
}
