package com.dlidar.mobile.data.protocol.log

/** Logging port. The app decides where messages go and whether debug messages are kept. */
interface Logger {
    fun debug(tag: String, message: () -> String)
    fun warn(tag: String, message: String, error: Throwable? = null)

    companion object {
        val None: Logger = object : Logger {
            override fun debug(tag: String, message: () -> String) = Unit
            override fun warn(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}
