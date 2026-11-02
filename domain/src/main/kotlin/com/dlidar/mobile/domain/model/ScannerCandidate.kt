package com.dlidar.mobile.domain.model

/** A scanner found nearby. [id] is an opaque handle used to connect to it. */
data class ScannerCandidate(
    val id: String,
    val name: String?,
    val rssi: Int,
)
