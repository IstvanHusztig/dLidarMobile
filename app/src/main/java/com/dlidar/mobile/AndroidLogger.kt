package com.dlidar.mobile

import android.util.Log
import com.dlidar.mobile.data.protocol.log.Logger

/** Sends logs to Logcat. Debug messages (they may hold raw payloads) only in debug builds. */
class AndroidLogger(private val debugEnabled: Boolean) : Logger {
    override fun debug(tag: String, message: () -> String) {
        if (debugEnabled) Log.d(PREFIX + tag, message())
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w(PREFIX + tag, message, error)
    }

    private companion object {
        const val PREFIX = "dLidar."
    }
}
