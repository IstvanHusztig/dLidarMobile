package com.dlidar.mobile.infra.wifi

import com.dlidar.mobile.domain.model.WifiSecurity

/**
 * Reads `ScanResult.capabilities` (for example `[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][ESS]`).
 * A transition network that offers both PSK and SAE is sent as `wpa2-psk`, as the scanner side
 * decided. Enterprise, OWE and WEP networks are shown but cannot be joined.
 */
internal object SecurityTypeResolver {
    fun resolve(capabilities: String): WifiSecurity {
        val caps = capabilities.uppercase()
        val protected = "RSN" in caps || "WPA" in caps
        return when {
            "PSK" in caps -> WifiSecurity.Wpa2Psk
            "SAE" in caps -> WifiSecurity.Wpa3Sae
            "EAP" in caps -> WifiSecurity.Unsupported("Enterprise")
            protected && "OWE" in caps -> WifiSecurity.Unsupported("OWE")
            "WEP" in caps -> WifiSecurity.Unsupported("WEP")
            // [ESS], or an open network that only announces an OWE transition partner.
            !protected -> WifiSecurity.Open
            else -> WifiSecurity.Unsupported(capabilities)
        }
    }
}
