package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.LogCursor
import com.dlidar.mobile.domain.model.LogPage
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.result.Outcome

/** The scanner service's log, read a page at a time. */
interface ScannerLog {
    /**
     * Reads the lines at [threshold] or above, starting at the newest line, or before [older]
     * when given. Pages stay stable while new lines arrive; to see new lines, read again
     * without a cursor.
     */
    suspend fun read(older: LogCursor?, threshold: LogThreshold): Outcome<LogPage>
}
