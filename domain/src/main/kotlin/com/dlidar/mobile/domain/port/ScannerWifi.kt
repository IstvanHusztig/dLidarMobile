package com.dlidar.mobile.domain.port

import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.result.Outcome

/** The scanner's Wi-Fi: join a network, read the state. */
interface ScannerWifi {
    /**
     * Asks the scanner to join [ssid] and suspends until the outcome is known (about a minute at
     * most), recovering through the scanner's status when the link drops meanwhile. A failure
     * means the request was not taken (busy, bad request, not connected). [password] is cleared
     * once sent.
     */
    suspend fun join(ssid: String, security: WifiSecurity, password: WifiPassword?): Outcome<WifiJoinResult>

    suspend fun status(): Outcome<ScannerWifiStatus>
}
