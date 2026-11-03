package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.RecordingCommandResult
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.result.DomainError
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

    /** Asks the scanner to start a recording; [DomainError.Busy] while the last one is saved. */
    suspend fun startRecording(): Outcome<RecordingCommandResult>

    /** Asks the scanner to stop the recording, or to abandon one still preparing. */
    suspend fun stopRecording(): Outcome<RecordingCommandResult>

    /** Removes a warning from the list. Local only; cannot fail. */
    fun dismissWarning(warningId: Long)
}
