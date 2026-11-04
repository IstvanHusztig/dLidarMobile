package com.dlidar.mobile.presentation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogLevel
import com.dlidar.mobile.domain.model.LogSource
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.presentation.log.LogAction
import com.dlidar.mobile.presentation.log.LogExport
import com.dlidar.mobile.presentation.log.LogRow
import com.dlidar.mobile.presentation.log.LogScreen
import com.dlidar.mobile.presentation.log.LogUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class LogScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(text: String, level: LogLevel = LogLevel.Info, source: LogSource = LogSource.Dlidar, boot: String = "36276c97") =
        LogEntry(Instant.EPOCH, boot, level, source, text)

    private val lines = listOf(
        entry("dlidar.service: Failed with result 'signal'.", LogLevel.Warning, LogSource.System),
        entry("Ready: press the button"),
        entry("old line", boot = "40d4c4f4"),
    )

    private val live = LogUiState(rows = LogRow.of(lines), access = LogUiState.Access.Live, canLoadOlder = true)

    @Test
    fun `lines show with level, source and a header per boot`() {
        val actions = mutableListOf<LogAction>()
        compose.setContent { LogScreen(live, onAction = { actions += it }, onShare = {}) }

        compose.onNodeWithText("dlidar.service: Failed with result 'signal'.").assertExists()
        compose.onNodeWithText("Warning").assertExists()
        compose.onNodeWithText("system").assertExists()
        compose.onNodeWithText("Boot 36276c97").assertExists()
        compose.onNodeWithText("Boot 40d4c4f4").assertExists()
        // The tab asks for its first page when shown.
        assertEquals(LogAction.Opened, actions.first())
    }

    @Test
    fun `filters, refresh, share and load older send their actions`() {
        val actions = mutableListOf<LogAction>()
        var shared = 0
        compose.setContent { LogScreen(live, onAction = { actions += it }, onShare = { shared++ }) }

        compose.onNodeWithText("Errors").performClick()
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Load older lines").performClick()

        assertTrue(LogAction.SetThreshold(LogThreshold.Error) in actions)
        assertTrue(LogAction.Refresh in actions)
        assertTrue(LogAction.LoadOlder in actions)
        assertEquals(1, shared)
    }

    @Test
    fun `the start of the log is marked`() {
        compose.setContent { LogScreen(live.copy(canLoadOlder = false, startReached = true), onAction = {}, onShare = {}) }
        compose.onNodeWithText("Start of the log").assertExists()
        compose.onNodeWithText("Load older lines").assertDoesNotExist()
    }

    @Test
    fun `not connected without lines asks to connect, and nothing can be shared`() {
        compose.setContent { LogScreen(LogUiState(access = LogUiState.Access.NotConnected), onAction = {}, onShare = {}) }
        compose.onNodeWithText("Connect to the scanner to read its log.").assertExists()
        compose.onNodeWithText("Share").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
    }

    @Test
    fun `lines from before the link dropped are marked as not live`() {
        compose.setContent { LogScreen(live.copy(access = LogUiState.Access.NotConnected, canLoadOlder = false), onAction = {}, onShare = {}) }
        compose.onNodeWithText("Lines loaded earlier; not live.").assertExists()
        compose.onNodeWithText("Ready: press the button").assertExists()
    }

    @Test
    fun `an older scanner is told to update`() {
        compose.setContent { LogScreen(LogUiState(access = LogUiState.Access.NotSupported), onAction = {}, onShare = {}) }
        compose.onNodeWithText("This scanner cannot send its log. Update the scanner's software.").assertExists()
    }

    @Test
    fun `a failed newest read shows the scanner's text and retries with refresh`() {
        val actions = mutableListOf<LogAction>()
        val failure = LogUiState.ReadFailure(LogUiState.Loading.Newest, DomainError.Remote("unavailable", "cannot open the journal: Permission denied"))
        compose.setContent { LogScreen(LogUiState(access = LogUiState.Access.Live, failure = failure), onAction = { actions += it }, onShare = {}) }

        compose.onNodeWithText("The scanner could not read its log: cannot open the journal: Permission denied").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(LogAction.Refresh, actions.last())
    }

    @Test
    fun `a failed older read retries the older page`() {
        val actions = mutableListOf<LogAction>()
        val failure = LogUiState.ReadFailure(LogUiState.Loading.Older, DomainError.Timeout)
        compose.setContent { LogScreen(live.copy(canLoadOlder = false, failure = failure), onAction = { actions += it }, onShare = {}) }

        compose.onNodeWithText("Could not read the log.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(LogAction.LoadOlder, actions.last())
    }

    @Test
    fun `debug builds switch to the protocol traffic`() {
        compose.setContent { LogScreen(live, onAction = {}, onShare = {}, traffic = { Text("traffic entries") }) }
        compose.onNodeWithText("traffic entries").assertDoesNotExist()

        compose.onNodeWithText("Protocol traffic").performClick()
        compose.onNodeWithText("traffic entries").assertExists()
        compose.onNodeWithText("Ready: press the button").assertDoesNotExist()
    }

    @Test
    fun `the export keeps the newest lines when it does not fit`() {
        val entries = (9 downTo 0).map { entry("line $it") }
        val text = LogExport.text(entries, ZoneOffset.UTC, maxChars = 3 * 53)

        assertEquals(
            listOf(
                "[7 older lines left out]",
                "1970-01-01 00:00:00.000 36276c97 INFO dlidar: line 7",
                "1970-01-01 00:00:00.000 36276c97 INFO dlidar: line 8",
                "1970-01-01 00:00:00.000 36276c97 INFO dlidar: line 9",
            ),
            text.lines(),
        )
    }
}
