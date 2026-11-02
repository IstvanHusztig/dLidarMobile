package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.result.Outcome
import kotlinx.coroutines.flow.StateFlow

/** The selected scanner: its connection and everything it reports. */
interface ScannerRepository {
    val snapshot: StateFlow<ScannerSnapshot>

    /** Selects [candidate] and keeps a connection to it, reconnecting when the link drops. */
    fun connect(candidate: ScannerCandidate)

    /** Closes the connection and stops reconnecting. */
    fun disconnect()

    /** Asks the scanner for its status now. */
    suspend fun refreshStatus(): Outcome<Unit>

    /** Removes a warning from the list. Local only; cannot fail. */
    fun dismissWarning(warningId: Long)
}
