package com.dlidar.mobile.infra.wifi

import com.dlidar.mobile.domain.model.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiScanRulesTest {

    @Test
    fun `capabilities strings from real phones map to the scanner's security types`() {
        val table = mapOf(
            "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" to WifiSecurity.Wpa2Psk,
            "[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS][WPS]" to WifiSecurity.Wpa2Psk,
            "[RSN-SAE-CCMP][ESS][MFPR]" to WifiSecurity.Wpa3Sae,
            "[RSN-SAE_EXT_KEY-GCMP-256][ESS]" to WifiSecurity.Wpa3Sae,
            // Transition network: sent as wpa2-psk (scanner decision).
            "[WPA2-PSK+SAE-CCMP][RSN-PSK+SAE-CCMP][ESS][MFPC]" to WifiSecurity.Wpa2Psk,
            "[ESS]" to WifiSecurity.Open,
            "" to WifiSecurity.Open,
            "[ESS][OWE_TRANSITION]" to WifiSecurity.Open,
            "[RSN-OWE-CCMP][ESS][MFPR]" to WifiSecurity.Unsupported("OWE"),
            "[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]" to WifiSecurity.Unsupported("Enterprise"),
            "[RSN-EAP-SUITE-B-192-GCMP-256][ESS]" to WifiSecurity.Unsupported("Enterprise"),
            "[WEP][ESS]" to WifiSecurity.Unsupported("WEP"),
        )
        for ((caps, expected) in table) assertEquals(caps, expected, SecurityTypeResolver.resolve(caps))
    }

    @Test
    fun `at most 4 scans every 2 minutes`() {
        var now = 0L
        val throttle = ScanThrottle({ now })
        repeat(4) { assertTrue(throttle.tryAcquire()) }
        assertFalse(throttle.tryAcquire())

        now = 119_999
        assertFalse(throttle.tryAcquire())
        now = 120_000
        assertTrue(throttle.tryAcquire())
    }
}
