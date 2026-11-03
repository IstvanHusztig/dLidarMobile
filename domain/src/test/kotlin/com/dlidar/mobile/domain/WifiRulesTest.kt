package com.dlidar.mobile.domain

import com.dlidar.mobile.domain.model.NetworkListMerger
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiCredentials
import com.dlidar.mobile.domain.model.WifiCredentials.Problem
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.port.ScannerWifi
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.JoinWifi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRulesTest {

    @Test
    fun `ssids are 1 to 32 bytes of UTF-8`() {
        assertNull(WifiCredentials.ssidProblem("Home"))
        assertNull(WifiCredentials.ssidProblem("x".repeat(32)))
        assertEquals(Problem.SsidLength, WifiCredentials.ssidProblem(""))
        assertEquals(Problem.SsidLength, WifiCredentials.ssidProblem("x".repeat(33)))
        // 11 characters, but 33 bytes.
        assertEquals(Problem.SsidLength, WifiCredentials.ssidProblem("ő".repeat(11) + "ő".repeat(5) + "x"))
    }

    @Test
    fun `passwords follow the scanner's rule`() {
        val wpa2 = WifiSecurity.Wpa2Psk
        assertNull(WifiCredentials.passwordProblem(wpa2, "12345678".toCharArray()))
        assertNull(WifiCredentials.passwordProblem(wpa2, "a b~!".repeat(12).take(63).toCharArray()))
        assertNull(WifiCredentials.passwordProblem(WifiSecurity.Wpa3Sae, "0123456789abcdefABCDEF".repeat(3).take(64).toCharArray()))
        assertEquals(Problem.PasswordFormat, WifiCredentials.passwordProblem(wpa2, "1234567".toCharArray()))
        assertEquals(Problem.PasswordFormat, WifiCredentials.passwordProblem(wpa2, "g".repeat(64).toCharArray()))
        assertEquals(Problem.PasswordFormat, WifiCredentials.passwordProblem(wpa2, "passwörd1".toCharArray()))
        assertEquals(Problem.PasswordMissing, WifiCredentials.passwordProblem(wpa2, null))
        assertNull(WifiCredentials.passwordProblem(WifiSecurity.Open, null))
        assertEquals(Problem.PasswordForOpenNetwork, WifiCredentials.passwordProblem(WifiSecurity.Open, "12345678".toCharArray()))
        assertEquals(Problem.UnsupportedSecurity, WifiCredentials.passwordProblem(WifiSecurity.Unsupported("EAP"), null))
    }

    @Test
    fun `the list drops hidden networks, merges by ssid keeping the strongest, strongest first`() {
        val merged = NetworkListMerger.merge(
            listOf(
                WifiNetwork("Home", WifiSecurity.Wpa2Psk, -70),
                WifiNetwork("", WifiSecurity.Open, -30),
                WifiNetwork("Home", WifiSecurity.Wpa3Sae, -50),
                WifiNetwork("Cafe", WifiSecurity.Open, -60),
            ),
        )
        assertEquals(listOf(WifiNetwork("Home", WifiSecurity.Wpa3Sae, -50), WifiNetwork("Cafe", WifiSecurity.Open, -60)), merged)
    }

    @Test
    fun `the password hides itself and can be cleared`() {
        val password = WifiPassword("secret123".toCharArray())
        assertFalse("secret" in password.toString())
        password.clear()
        assertTrue(password.toCharArray().all { it == '\u0000' })
    }

    @Test
    fun `join checks the rules before sending, and sends no password to an open network`() = runTest {
        val sent = mutableListOf<Pair<String, WifiPassword?>>()
        val wifi = object : ScannerWifi {
            override suspend fun join(ssid: String, security: WifiSecurity, password: WifiPassword?): Outcome<WifiJoinResult> {
                sent += ssid to password
                return Outcome.Ok(WifiJoinResult.Connected(ssid, null))
            }
            override suspend fun status(): Outcome<ScannerWifiStatus> = error("unused")
        }
        val join = JoinWifi(wifi)
        val short = WifiPassword("short".toCharArray())

        assertEquals(Outcome.Failed(DomainError.InvalidInput("PasswordFormat")), join(WifiNetwork("Home", WifiSecurity.Wpa2Psk, -50), short))
        assertTrue(short.toCharArray().all { it == '\u0000' })
        assertTrue(sent.isEmpty())

        join(WifiNetwork("Cafe", WifiSecurity.Open, -50), null)
        assertEquals(listOf<Pair<String, WifiPassword?>>("Cafe" to null), sent)
    }
}
