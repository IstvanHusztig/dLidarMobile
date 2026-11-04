package com.dlidar.mobile.presentation

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.dlidar.mobile.domain.model.LastJoin
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.presentation.wifi.JoinForm
import com.dlidar.mobile.presentation.wifi.JoinOutcome
import com.dlidar.mobile.presentation.wifi.WifiSetupAction
import com.dlidar.mobile.presentation.wifi.WifiSetupScreen
import com.dlidar.mobile.presentation.wifi.WifiSetupUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class WifiSetupScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val home = WifiNetwork("Home", WifiSecurity.Wpa2Psk, -50)
    private val connected = ScannerWifiStatus(WifiState.Connected, "Home", "192.168.1.50", LastJoin("Home", WifiJoinResult.Connected("Home", "192.168.1.50")))
    private val listed = WifiSetupUiState(
        scannerWifi = connected,
        networks = listOf(home, WifiNetwork("Office", WifiSecurity.Unsupported("Enterprise"), -60)),
    )

    private fun show(state: WifiSetupUiState, actions: MutableList<WifiSetupAction> = mutableListOf()) =
        compose.setContent { WifiSetupScreen(state, onAction = { actions += it }, onFixProblem = {}, onBack = {}) }

    @Test
    fun `shows the scanner's network and the phone's networks`() {
        show(listed)
        compose.onNodeWithText("Connected to Home").assertExists()
        compose.onNodeWithText("Address 192.168.1.50").assertExists()
        compose.onNodeWithText("Office").assertExists()
        compose.onNodeWithText("Enterprise: not supported by the scanner").assertExists()
    }

    @Test
    fun `tapping a network selects it`() {
        val actions = mutableListOf<WifiSetupAction>()
        show(listed, actions)
        compose.onNodeWithText("Home").performClick()
        assertEquals(listOf<WifiSetupAction>(WifiSetupAction.Select(home)), actions)
    }

    @Test
    fun `the password dialog enables Connect only for a valid password`() {
        val actions = mutableListOf<WifiSetupAction>()
        // The form on its own: a text field inside a dialog window never idles under Robolectric.
        compose.setContent { JoinForm(home, onAction = { actions += it }) }
        compose.onNodeWithText("Connect").assertIsNotEnabled()

        compose.onNode(hasSetTextAction()).performTextInput("short")
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("8 to 63 characters", substring = true).assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("123")
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        val join = actions.single() as WifiSetupAction.Join
        assertEquals("short123", String(join.password!!))
    }

    @Test
    fun `an open network asks for a confirmation, without a password field`() {
        val actions = mutableListOf<WifiSetupAction>()
        compose.setContent { JoinForm(WifiNetwork("Cafe", WifiSecurity.Open, -70), onAction = { actions += it }) }
        compose.onNodeWithText("This network has no password", substring = true).assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Connect").performClick()
        assertEquals(null, (actions.single() as WifiSetupAction.Join).password)
    }

    @Test
    fun `results are explained, with retry when it failed`() {
        show(listed.copy(outcome = JoinOutcome.Finished(home, WifiJoinResult.WrongPassword)))
        compose.onNodeWithText("Wrong password", substring = true).assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun `a failed join shows the scanner's own message`() {
        show(listed.copy(outcome = JoinOutcome.Finished(home, WifiJoinResult.Failed("NetworkManager is not running"))))
        compose.onNodeWithText("NetworkManager is not running").assertExists()
    }

    @Test
    fun `no Wi-Fi on the scanner is said, and a scan problem offers its fix`() {
        show(
            WifiSetupUiState(
                scannerWifi = ScannerWifiStatus(WifiState.Unavailable, null, null, null),
                scanProblem = WifiScan.Problem.PermissionMissing,
            ),
        )
        compose.onNodeWithText("Wi-Fi is not available on the scanner.").assertExists()
        compose.onNodeWithText("Grant permission").assertExists()
    }
}
