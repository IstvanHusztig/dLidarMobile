package com.dlidar.mobile.presentation

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.data.scanner.wifi.ScannerWifiImpl
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.domain.port.WifiNetworkScanner
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.usecase.JoinWifi
import com.dlidar.mobile.domain.usecase.ReadScannerWifi
import com.dlidar.mobile.domain.usecase.ScanWifiNetworks
import com.dlidar.mobile.presentation.wifi.JoinOutcome
import com.dlidar.mobile.presentation.wifi.WifiSetupAction
import com.dlidar.mobile.presentation.wifi.WifiSetupViewModel
import com.dlidar.mobile.testing.FakeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The Wi-Fi screen's ViewModel on the real repository and protocol stack, against the fake scanner. */
@OptIn(ExperimentalCoroutinesApi::class)
class WifiSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeScanner()
    private val home = WifiNetwork("Home", WifiSecurity.Wpa2Psk, -50)
    private val cafe = WifiNetwork("Cafe", WifiSecurity.Open, -70)
    private val office = WifiNetwork("Office", WifiSecurity.Unsupported("Enterprise"), -60)
    private var scan: WifiScan = WifiScan.Networks(listOf(cafe, home, office, home.copy(rssi = -80)), cached = false)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        assertEquals(emptyList<String>(), fake.violations)
    }

    private fun TestScope.viewModel(): WifiSetupViewModel {
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH })
        repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()
        val wifi = ScannerWifiImpl(repository)
        return WifiSetupViewModel(ScanWifiNetworks(WifiNetworkScanner { scan }), JoinWifi(wifi), ReadScannerWifi(wifi)).also {
            it.onAction(WifiSetupAction.Opened)
            runCurrent()
        }
    }

    @Test
    fun `opening reads the scanner's Wi-Fi and lists the merged networks`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.state.value.let {
            assertEquals(WifiState.Disconnected, it.scannerWifi?.state)
            assertEquals(listOf(home, office, cafe), it.networks)
            assertFalse(it.scanning)
        }
    }

    @Test
    fun `joining with a password shows the result and the new status`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(WifiSetupAction.Select(home))
        assertEquals(home, viewModel.state.value.selected)

        val typed = "secret123".toCharArray()
        viewModel.onAction(WifiSetupAction.Join(typed))
        assertTrue(typed.all { it == '\u0000' })
        assertEquals(home, viewModel.state.value.joining)
        runCurrent()

        viewModel.state.value.let {
            assertNull(it.joining)
            assertEquals(JoinOutcome.Finished(home, WifiJoinResult.Connected("Home", "192.168.1.50")), it.outcome)
            assertEquals(WifiState.Connected, it.scannerWifi?.state)
        }
    }

    @Test
    fun `a wrong password can be retried`() = runTest(dispatcher) {
        fake.wifiJoinOutcome = "wrong_password"
        val viewModel = viewModel()
        viewModel.onAction(WifiSetupAction.Select(home))
        viewModel.onAction(WifiSetupAction.Join("secret123".toCharArray()))
        runCurrent()
        assertEquals(JoinOutcome.Finished(home, WifiJoinResult.WrongPassword), viewModel.state.value.outcome)

        viewModel.onAction(WifiSetupAction.Select(home))
        assertEquals(home, viewModel.state.value.selected)
        assertNull(viewModel.state.value.outcome)
    }

    @Test
    fun `an open network is joined without a password, an unsupported one cannot be picked`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(WifiSetupAction.Select(office))
        assertNull(viewModel.state.value.selected)

        viewModel.onAction(WifiSetupAction.Select(cafe))
        viewModel.onAction(WifiSetupAction.Join(null))
        runCurrent()
        assertEquals(JoinOutcome.Finished(cafe, WifiJoinResult.Connected("Cafe", "192.168.1.50")), viewModel.state.value.outcome)
    }

    @Test
    fun `a password that breaks the rule is not sent`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(WifiSetupAction.Select(home))
        viewModel.onAction(WifiSetupAction.Join("short".toCharArray()))
        runCurrent()

        assertEquals(JoinOutcome.Rejected(home, DomainError.InvalidInput("PasswordFormat")), viewModel.state.value.outcome)
        assertFalse("wifi.connect" in fake.commandNames)
    }

    @Test
    fun `a scanner without Wi-Fi offers no join`() = runTest(dispatcher) {
        fake.wifiDevice = "unavailable"
        val viewModel = viewModel()
        assertFalse(viewModel.state.value.canJoin)
        viewModel.onAction(WifiSetupAction.Select(home))
        assertNull(viewModel.state.value.selected)
    }

    @Test
    fun `scan problems and cached results are reported`() = runTest(dispatcher) {
        scan = WifiScan.Failed(WifiScan.Problem.LocationOff)
        val viewModel = viewModel()
        assertEquals(WifiScan.Problem.LocationOff, viewModel.state.value.scanProblem)

        scan = WifiScan.Networks(listOf(home), cached = true)
        viewModel.onAction(WifiSetupAction.Scan)
        runCurrent()
        assertNull(viewModel.state.value.scanProblem)
        assertTrue(viewModel.state.value.cached)
    }

    @Test
    fun `an old outcome is cleared when the screen opens again`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onAction(WifiSetupAction.Select(home))
        viewModel.onAction(WifiSetupAction.Join("secret123".toCharArray()))
        runCurrent()
        viewModel.onAction(WifiSetupAction.Opened)
        runCurrent()
        assertNull(viewModel.state.value.outcome)
    }
}
