package com.dlidar.mobile.domain.model

/** A Wi-Fi network the phone found. The scanner never scans; the user picks from this list. */
data class WifiNetwork(val ssid: String, val security: WifiSecurity, val rssi: Int)

/** Security types the scanner can join, as decided with the scanner side. */
sealed interface WifiSecurity {
    data object Wpa2Psk : WifiSecurity
    data object Wpa3Sae : WifiSecurity
    data object Open : WifiSecurity

    /** Shown but not selectable: enterprise, WEP, OWE and the like. [kind] is for display. */
    data class Unsupported(val kind: String) : WifiSecurity

    val needsPassword: Boolean get() = this == Wpa2Psk || this == Wpa3Sae
}
