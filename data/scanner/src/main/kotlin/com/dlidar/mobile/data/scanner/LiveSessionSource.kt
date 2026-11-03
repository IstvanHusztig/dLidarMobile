package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.ProtocolSession
import com.dlidar.mobile.data.scanner.dto.StatusDto
import com.dlidar.mobile.data.scanner.session.PendingOperations
import com.dlidar.mobile.domain.model.ScannerSnapshot
import kotlinx.coroutines.flow.StateFlow

/** What a feature beside the repository needs to send requests: the live session and the snapshot. */
internal interface LiveSessionSource {
    /** The session requests may use, once hello allowed them; null between links. */
    val liveSession: StateFlow<LiveSession?>
    val snapshot: StateFlow<ScannerSnapshot>
}

/** A session past hello, with the requests whose outcome comes later on it. */
internal class LiveSession(
    val session: ProtocolSession,
    val operations: PendingOperations,
    /** Applies a status.get answer through the run that owns the session. */
    val applyStatus: (StatusDto) -> Unit,
)
