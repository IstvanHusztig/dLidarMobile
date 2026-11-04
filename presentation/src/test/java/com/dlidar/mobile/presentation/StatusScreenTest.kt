package com.dlidar.mobile.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.presentation.status.StatusAction
import com.dlidar.mobile.presentation.status.StatusScreen
import com.dlidar.mobile.presentation.status.StatusUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class StatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val live = StatusUiState(
        scannerName = "dLidar",
        connection = ConnectionState.Connected,
        isLive = true,
        status = ScannerStatus(RecordingState.Recording, 12, 2_400_000, true, "/media/usb/scans"),
        freeBytes = null,
        info = DeviceInfo("1.2.3", SensorType.LivoxMid360s, "MID-360S", 3, 1, hostname = "dlidar-pi"),
        warnings = listOf(Warning(7, WarningKind.Unknown("lens_fogged"), "The lens is fogged up.", Instant.EPOCH)),
        summary = RecordingSummary("fused", "keep", 100, 99, 1, Instant.EPOCH),
    )

    @Test
    fun `the Device tab shows state, counts, unknown free space, warning text and summary, Info shows the scanner`() {
        val wifi = ScannerWifiStatus(WifiState.Connected, "Home", "10.0.0.5", null)
        compose.setContent { StatusScreen(live.copy(scannerAddress = "AA:BB", showWifiSetup = true, scannerWifi = wifi), onAction = {}) }

        compose.onNodeWithContentDescription("Connected").assertIsDisplayed()
        compose.onNodeWithText("/media/usb/scans").assertExists()
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("The lens is fogged up.").assertExists()
        compose.onNodeWithText("keep").assertExists()
        compose.onNodeWithText("AA:BB").assertDoesNotExist()

        compose.onNodeWithText("Info").performClick()
        compose.onNodeWithText("AA:BB").assertExists()
        compose.onNodeWithText("Livox Mid-360S").assertExists()
        compose.onNodeWithText("10.0.0.5").assertExists()
        compose.onNodeWithText("dlidar-pi").assertExists()
        compose.onNodeWithText("1.2.3").assertDoesNotExist()
        compose.onNodeWithText("/media/usb/scans").assertDoesNotExist()
    }

    @Test
    fun `an unknown recording state is shown generically`() {
        compose.setContent { StatusScreen(live.copy(status = live.status!!.copy(state = RecordingState.Unknown("calibrating"))), onAction = {}) }
        compose.onNodeWithText("Unknown (calibrating)").assertExists()
    }

    @Test
    fun `update the app is shown when the scanner is newer`() {
        compose.setContent { StatusScreen(live.copy(updateAppRequired = true), onAction = {}) }
        compose.onNodeWithText("Please update the app.", substring = true).assertExists()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test
    fun `update the scanner is shown when the scanner is older`() {
        compose.setContent { StatusScreen(live.copy(updateScannerRequired = true), onAction = {}) }
        compose.onNodeWithText("The scanner speaks an older protocol version than this app. Please update the scanner.").assertExists()
    }

    @Test
    fun `a scanner that does not answer is explained`() {
        compose.setContent { StatusScreen(live.copy(notAnswering = true), onAction = {}) }
        compose.onNodeWithText("does not answer", substring = true).assertExists()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test
    fun `a failed pairing says what to do and offers retry`() {
        val failed = live.copy(
            connection = ConnectionState.Failed(ConnectionState.Failed.Reason.NotPaired),
            isLive = false,
            canRetry = true,
        )
        compose.setContent { StatusScreen(failed, onAction = {}) }
        compose.onNodeWithText("pairing window", substring = true).assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun `the IMU mode is shown readably`() {
        compose.setContent { StatusScreen(live.copy(summary = live.summary!!.copy(imuMode = "host_aligned")), onAction = {}) }
        compose.onNodeWithText("Host aligned (fallback)").assertExists()
    }

    @Test
    fun `another phone connected offers retry`() {
        val actions = mutableListOf<StatusAction>()
        val refused = live.copy(
            connection = ConnectionState.Refused(ConnectionState.Refused.Reason.AnotherPhoneConnected),
            isLive = false,
            canRetry = true,
        )
        compose.setContent { StatusScreen(refused, onAction = { actions += it }) }

        compose.onNodeWithText("Last known values; not live.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf<StatusAction>(StatusAction.Retry), actions)
    }

    @Test
    fun `dismissing a warning sends its id`() {
        val actions = mutableListOf<StatusAction>()
        compose.setContent { StatusScreen(live, onAction = { actions += it }) }
        compose.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf<StatusAction>(StatusAction.DismissWarning(7)), actions)
    }

    @Test
    fun `the Log tab shows the log the app offers, or a note`() {
        var offered by mutableStateOf(false)
        compose.setContent { StatusScreen(live, logTab = if (offered) ({ Text("traffic entries") }) else null, onAction = {}) }
        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("Scanner logs come in a later version.").assertExists()

        offered = true
        compose.onNodeWithText("traffic entries").assertExists()
    }

    @Test
    fun `the Wi-Fi row shows the scanner's network and opens the setup`() {
        var opened = 0
        val wifi = ScannerWifiStatus(WifiState.Connected, "Home", "10.0.0.5", null)
        compose.setContent { StatusScreen(live.copy(showWifiSetup = true, scannerWifi = wifi), onWifiSetup = { opened++ }, onAction = {}) }
        compose.onNodeWithText("Home").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no Wi-Fi row when the scanner does not take the Wi-Fi commands`() {
        compose.setContent { StatusScreen(live, onAction = {}) }
        compose.onNodeWithText("Wi-Fi").assertDoesNotExist()
    }

    @Test
    fun `the connection dot retries when retry is offered`() {
        val actions = mutableListOf<StatusAction>()
        val dropped = live.copy(connection = ConnectionState.Disconnected, isLive = false, canRetry = true)
        compose.setContent { StatusScreen(dropped, onAction = { actions += it }) }
        compose.onNodeWithContentDescription("Disconnected").performClick()
        assertEquals(listOf<StatusAction>(StatusAction.Retry), actions)
    }

    @Test
    fun `start and stop are shown when offered and send their actions`() {
        val actions = mutableListOf<StatusAction>()
        compose.setContent { StatusScreen(live.copy(showRecordingControls = true, canStop = true), onAction = { actions += it }) }

        compose.onNodeWithText("Start recording").assertIsNotEnabled()
        compose.onNodeWithText("Stop recording").assertIsEnabled().performClick()
        assertEquals(listOf<StatusAction>(StatusAction.StopRecording), actions)
    }

    @Test
    fun `no recording buttons when not offered`() {
        compose.setContent { StatusScreen(live, onAction = {}) }
        compose.onNodeWithText("Start recording").assertDoesNotExist()
    }

    @Test
    fun `saving and busy are explained`() {
        val failure = StatusUiState.CommandFailure(StatusUiState.RecordingCommand.Start, DomainError.Busy)
        compose.setContent { StatusScreen(live.copy(showRecordingControls = true, saving = true, commandFailure = failure), onAction = {}) }
        compose.onNodeWithText("The last recording is being saved", substring = true).assertExists()
        compose.onNodeWithText("Try again when the state is Idle.", substring = true).assertExists()
    }

    @Test
    fun `controls follow the snapshot`() {
        val snapshot = ScannerSnapshot(
            connection = ConnectionState.Connected,
            compatibility = Compatibility.Compatible,
            canControlRecording = true,
            status = ScannerStatus(RecordingState.Idle, 0, 0, true, ""),
        )
        StatusUiState.from(snapshot).let {
            assertTrue(it.showRecordingControls)
            assertTrue(it.canStart)
            assertFalse(it.canStop)
        }
        assertFalse(StatusUiState.from(snapshot, sending = true).canStart)
        assertFalse(StatusUiState.from(snapshot.copy(status = null)).canStart)
        assertFalse(StatusUiState.from(snapshot.copy(connection = ConnectionState.Disconnected)).showRecordingControls)
        assertFalse(StatusUiState.from(snapshot.copy(compatibility = Compatibility.UpdateAppRequired(2))).showRecordingControls)
    }
}
