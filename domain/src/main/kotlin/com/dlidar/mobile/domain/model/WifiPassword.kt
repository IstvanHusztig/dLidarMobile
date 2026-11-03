package com.dlidar.mobile.domain.model

/**
 * A Wi-Fi password, kept only in memory. It is never logged ([toString] hides it) and never
 * stored; [clear] overwrites it once the request is sent.
 */
class WifiPassword(chars: CharArray) {
    private val chars: CharArray = chars.copyOf()

    val length: Int get() = chars.size

    /** A copy; the caller clears it after use. */
    fun toCharArray(): CharArray = chars.copyOf()

    fun clear() = chars.fill('\u0000')

    override fun toString() = "WifiPassword(***)"
}
