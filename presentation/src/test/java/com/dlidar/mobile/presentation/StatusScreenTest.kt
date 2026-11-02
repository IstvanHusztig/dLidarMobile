package com.dlidar.mobile.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.presentation.status.StatusAction
import com.dlidar.mobile.presentation.status.StatusScreen
import com.dlidar.mobile.presentation.status.StatusUiState
import org.junit.Assert.assertEquals
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
        info = DeviceInfo("1.2.3", SensorType.LivoxMid360s, "MID-360S", 3, 1),
        warnings = listOf(Warning(7, WarningKind.Unknown("lens_fogged"), "The lens is fogged up.", Instant.EPOCH)),
        summary = RecordingSummary("fused", "keep", 100, 99, 1, Instant.EPOCH),
    )

    @Test
    fun `shows state, counts, unknown free space, warning text, device and summary`() {
        compose.setContent { StatusScreen(live, onAction = {}) }

        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("/media/usb/scans").assertExists()
        compose.onNodeWithText("Unknown").assertExists()
        compose.onNodeWithText("The lens is fogged up.").assertExists()
        compose.onNodeWithText("1.2.3").assertExists()
        compose.onNodeWithText("keep").assertExists()
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
    fun `the traffic log button shows only when the app offers the log`() {
        var opened = 0
        var offered by mutableStateOf(false)
        compose.setContent { StatusScreen(live, onTrafficLog = if (offered) ({ opened++ }) else null, onAction = {}) }
        compose.onNodeWithText("Log").assertDoesNotExist()

        offered = true
        compose.onNodeWithText("Log").performClick()
        assertEquals(1, opened)
    }
}
