package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.Discovery
import kotlinx.coroutines.flow.Flow

/** Finds scanners nearby. The search runs while the flow is collected. */
fun interface ScannerFinder {
    fun find(): Flow<Discovery>
}
