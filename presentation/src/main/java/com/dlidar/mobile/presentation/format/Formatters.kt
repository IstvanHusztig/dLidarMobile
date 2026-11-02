package com.dlidar.mobile.presentation.format

import java.util.Locale

/** Number formatting for the screens. Pure, so it is tested on the JVM. */
object Formatters {

    private val UNITS = listOf("kB", "MB", "GB", "TB", "PB", "EB")

    /** Decimal units (1 GB = 10^9 bytes), as Android's storage settings show them, one decimal. */
    fun bytes(value: Long, locale: Locale = Locale.getDefault()): String {
        if (value < 1000) return "$value B"
        var scaled = value / 1000.0
        var unit = 0
        // Move up while the value would print as 1000.0 or more, so 999 999 B is "1.0 MB".
        while (scaled >= 999.95 && unit < UNITS.lastIndex) {
            scaled /= 1000
            unit++
        }
        return String.format(locale, "%.1f %s", scaled, UNITS[unit])
    }

    fun count(value: Long, locale: Locale = Locale.getDefault()): String = String.format(locale, "%,d", value)
}
