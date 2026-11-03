@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.log.Logger
import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.scanner.wifi.ScannerWifiImpl
import com.dlidar.mobile.domain.model.LastJoin
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.testing.FakeScanner
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** wifi.connect and wifi.status against the fake scanner, which plays the scanner's contract. */
class WifiJoinTest {

    private val fake = FakeScanner()
    private val logged = CopyOnWriteArrayList<String>()
    private val logger = object : Logger {
        override fun debug(tag: String, message: () -> String) {
            logged += message()
        }
        override fun warn(tag: String, message: String, error: Throwable?) {
            logged += message
        }
    }

    private fun TestScope.connected(): Pair<ScannerRepositoryImpl, ScannerWifiImpl> {
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(logger = logger), backgroundScope, { Instant.EPOCH }, logger = logger)
        repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()
        return repository to ScannerWifiImpl(repository, logger)
    }

    private fun password(text: String) = WifiPassword(text.toCharArray())

    private val connectArgs: JsonObject
        get() = fake.requests.last { it["cmd"]?.jsonPrimitive?.content == "wifi.connect" }["args"] as JsonObject

    @After
    fun noProtocolViolations() {
        assertEquals(emptyList<String>(), fake.violations)
    }

    @Test
    fun `hello listing both commands offers Wi-Fi setup`() = runTest {
        assertTrue(connected().first.snapshot.value.canSetUpWifi)
    }

    @Test
    fun `an older scanner gets no Wi-Fi command`() = runTest {
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.RECORDING_COMMANDS)
        val (repository, wifi) = connected()

        assertFalse(repository.snapshot.value.canSetUpWifi)
        assertEquals(Outcome.Failed(DomainError.NotSupported), wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")))
        assertEquals(Outcome.Failed(DomainError.NotSupported), wifi.status())
        assertFalse("wifi.connect" in fake.commandNames)
    }

    @Test
    fun `a join that succeeds returns the network and address, and the password is gone`() = runTest {
        val (_, wifi) = connected()
        val secret = password("secret123")

        val outcome = wifi.join("Home", WifiSecurity.Wpa2Psk, secret)

        assertEquals(Outcome.Ok(WifiJoinResult.Connected("Home", "192.168.1.50")), outcome)
        assertEquals("Home", connectArgs["ssid"]?.jsonPrimitive?.content)
        assertEquals("wpa2-psk", connectArgs["security"]?.jsonPrimitive?.content)
        assertEquals("secret123", connectArgs["psk"]?.jsonPrimitive?.content)
        assertTrue(secret.toCharArray().all { it == '\u0000' })
        assertEquals(listOf("Home"), fake.savedNetworks)
        // Never in a log line, only on the wire.
        assertTrue(logged.any { "wifi.connect" in it })
        assertFalse(logged.any { "secret123" in it })
    }

    @Test
    fun `an open network is joined without a psk`() = runTest {
        val (_, wifi) = connected()
        assertEquals(Outcome.Ok(WifiJoinResult.Connected("Cafe", "192.168.1.50")), wifi.join("Cafe", WifiSecurity.Open, null))
        assertEquals("open", connectArgs["security"]?.jsonPrimitive?.content)
        assertNull(connectArgs["psk"])
    }

    @Test
    fun `failed joins map to their results`() = runTest {
        val (_, wifi) = connected()
        val expected = mapOf(
            "wrong_password" to WifiJoinResult.WrongPassword,
            "not_found" to WifiJoinResult.NotFound,
            "timeout" to WifiJoinResult.Timeout,
            "failed" to WifiJoinResult.Failed("dLidar may not change the network: install systemd/50-dlidar-network.rules"),
            "radio_jammed" to WifiJoinResult.Failed("radio_jammed"),
        )
        for ((code, result) in expected) {
            fake.wifiJoinOutcome = code
            assertEquals(code, Outcome.Ok(result), wifi.join("Home", WifiSecurity.Wpa3Sae, password("secret123")))
        }
        assertTrue(fake.savedNetworks.isEmpty())
    }

    @Test
    fun `a second join while one runs is busy`() = runTest {
        fake.wifiJoinFinishes = false
        val (_, wifi) = connected()
        val first = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }
        runCurrent()

        assertEquals(Outcome.Failed(DomainError.Busy), wifi.join("Work", WifiSecurity.Wpa2Psk, password("secret456")))

        fake.finishWifiJoin()
        runCurrent()
        assertEquals(Outcome.Ok(WifiJoinResult.Connected("Home", "192.168.1.50")), first.await())
    }

    @Test
    fun `a result lost with the link is read from wifi status after the reconnect`() = runTest {
        fake.wifiJoinFinishes = false
        val (_, wifi) = connected()
        val join = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }
        runCurrent()

        // Joining drops the BLE link; the Pi joins meanwhile, and its result event is lost.
        fake.dropLink()
        runCurrent()
        fake.finishWifiJoin()
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(Outcome.Ok(WifiJoinResult.Connected("Home", "192.168.1.50")), join.await())
        assertTrue("wifi.status" in fake.commandNames)
    }

    @Test
    fun `a join still pending after the reconnect is waited for`() = runTest {
        fake.wifiJoinFinishes = false
        fake.wifiJoinOutcome = "wrong_password"
        val (_, wifi) = connected()
        val join = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }
        runCurrent()
        fake.dropLink()
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(join.isCompleted)

        // The join ends on the Pi; its event went to the old connection, so status tells.
        fake.finishWifiJoin()
        advanceTimeBy(2_100)
        runCurrent()

        assertEquals(Outcome.Ok(WifiJoinResult.WrongPassword), join.await())
    }

    @Test
    fun `no result in time and a status that stays pending ends as unknown`() = runTest {
        fake.wifiJoinFinishes = false
        fake.wifiTimeoutS = 10
        val (_, wifi) = connected()
        val join = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }

        advanceTimeBy(19_000)
        runCurrent()
        assertFalse(join.isCompleted)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(Outcome.Ok(WifiJoinResult.Unknown), join.await())
        fake.finishWifiJoin()
    }

    @Test
    fun `status reports the state, the network and the last join`() = runTest {
        val (_, wifi) = connected()
        assertEquals(WifiState.Disconnected, (wifi.status() as Outcome.Ok).value.state)

        wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123"))
        val status = (wifi.status() as Outcome.Ok).value
        assertEquals(WifiState.Connected, status.state)
        assertEquals("Home", status.ssid)
        assertEquals("192.168.1.50", status.ip)
        assertEquals(LastJoin("Home", WifiJoinResult.Connected("Home", "192.168.1.50")), status.last)
    }

    @Test
    fun `no Wi-Fi device shows as unavailable, and a join fails with the scanner's message`() = runTest {
        fake.wifiDevice = "unavailable"
        val (_, wifi) = connected()
        assertEquals(WifiState.Unavailable, (wifi.status() as Outcome.Ok).value.state)
        assertTrue((wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) as Outcome.Ok).value is WifiJoinResult.Failed)
    }

    @Test
    fun `nothing is sent while disconnected`() = runTest {
        val (repository, wifi) = connected()
        repository.disconnect()
        runCurrent()
        val secret = password("secret123")

        assertEquals(Outcome.Failed(DomainError.NotConnected), wifi.join("Home", WifiSecurity.Wpa2Psk, secret))
        assertTrue(secret.toCharArray().all { it == '\u0000' })
        assertFalse("wifi.connect" in fake.commandNames)
    }

    @Test
    fun `busy and a lost link clear the password too`() = runTest {
        fake.wifiJoinFinishes = false
        val (_, wifi) = connected()
        val first = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }
        runCurrent()
        val second = password("secret456")
        assertEquals(Outcome.Failed(DomainError.Busy), wifi.join("Work", WifiSecurity.Wpa2Psk, second))
        assertTrue(second.toCharArray().all { it == '\u0000' })
        fake.finishWifiJoin()
        runCurrent()
        first.await()
    }

    @Test
    fun `a scanner that restarted during the join leaves the result unknown`() = runTest {
        fake.wifiJoinFinishes = false
        val (_, wifi) = connected()
        val join = async { wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")) }
        runCurrent()

        fake.restart()
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(Outcome.Ok(WifiJoinResult.Unknown), join.await())
    }

    @Test
    fun `an answer lost before the scanner saw the request does not pass an old outcome off`() = runTest {
        fake.wifiJoinOutcome = "wrong_password"
        val (_, wifi) = connected()
        assertEquals(Outcome.Ok(WifiJoinResult.WrongPassword), wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret123")))

        // The next request never reaches the scanner: its answer times out, last is unchanged.
        fake.silentCommands += "wifi.connect"
        val outcome = wifi.join("Home", WifiSecurity.Wpa2Psk, password("secret789"))

        assertEquals(Outcome.Ok(WifiJoinResult.Unknown), outcome)
    }

    @Test
    fun `cancelling a join releases its pending result and clears the password`() = runTest {
        fake.wifiJoinFinishes = false
        val (repository, wifi) = connected()
        val secret = password("secret123")
        val join = async { wifi.join("Home", WifiSecurity.Wpa2Psk, secret) }
        runCurrent()

        join.cancel()
        runCurrent()

        assertTrue(secret.toCharArray().all { it == '\u0000' })
        val op = fake.requests.last { it["cmd"]?.jsonPrimitive?.content == "wifi.connect" }["id"]!!.jsonPrimitive.content.toInt()
        assertFalse(repository.liveSessionSource.liveSession.value!!.operations.complete(op, JsonObject(emptyMap())))
        fake.finishWifiJoin()
    }
}
