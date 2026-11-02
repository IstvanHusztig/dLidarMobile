package com.dlidar.mobile.domain.model

import java.time.Instant

/**
 * A warning raised by the scanner. [text] is complete and readable and is shown as it is.
 * [id] is assigned by the app and identifies the warning for dismissal.
 */
data class Warning(
    val id: Long,
    val kind: WarningKind,
    val text: String,
    val receivedAt: Instant,
)
