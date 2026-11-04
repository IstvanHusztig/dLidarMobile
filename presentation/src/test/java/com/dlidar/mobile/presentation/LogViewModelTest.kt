package com.dlidar.mobile.presentation

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.data.scanner.log.ScannerLogImpl
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.ReadScannerLog
import com.dlidar.mobile.presentation.log.LogAction
import com.dlidar.mobile.presentation.log.LogRow
import com.dlidar.mobile.presentation.log.LogUiState
import com.dlidar.mobile.presentation.log.LogViewModel
import com.dlidar.mobile.testing.FakeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The Diagnostics tab's ViewModel on the real repository and protocol stack, against the fake scanner. */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeScanner()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        assertEquals(emptyList<String>(), fake.violations)
    }

    private fun TestScope.viewModel(connect: Boolean = true): Pair<LogViewModel, ScannerRepositoryImpl> {
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH })
        val viewModel = LogViewModel(ObserveScanner(repository), ReadScannerLog(ScannerLogImpl(repository)))
        if (connect) repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()
        return viewModel to repository
    }

    private val logReads: Int get() = fake.requests.count { it["cmd"]?.jsonPrimitive?.content == "log.get" }

    private fun LogUiState.texts() = rows.filterIsInstance<LogRow.Line>().map { it.entry.text }

    @Test
    fun `nothing is read until the tab is opened, then the newest page once`() = runTest(dispatcher) {
        fake.log("started")
        val (viewModel, _) = viewModel()
        assertEquals(0, logReads)

        viewModel.onAction(LogAction.Opened)
        runCurrent()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        assertEquals(listOf("started"), viewModel.state.value.texts())
        assertEquals(1, logReads)
        assertTrue(viewModel.state.value.startReached)
        assertFalse(viewModel.state.value.canLoadOlder)
    }

    @Test
    fun `opened before the scanner connects, the log is read once it can answer`() = runTest(dispatcher) {
        fake.log("started")
        val (viewModel, repository) = viewModel(connect = false)
        viewModel.onAction(LogAction.Opened)
        runCurrent()
        assertEquals(LogUiState.Access.NotConnected, viewModel.state.value.access)

        repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()

        assertEquals(listOf("started"), viewModel.state.value.texts())
    }

    @Test
    fun `older pages are appended until the start of the log`() = runTest(dispatcher) {
        repeat(150) { fake.log("line $it") }
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()
        assertTrue(viewModel.state.value.canLoadOlder)

        viewModel.onAction(LogAction.LoadOlder)
        runCurrent()

        assertEquals((149 downTo 0).map { "line $it" }, viewModel.state.value.texts())
        assertTrue(viewModel.state.value.startReached)
        // Nothing more to read.
        viewModel.onAction(LogAction.LoadOlder)
        runCurrent()
        assertEquals(2, logReads)
    }

    @Test
    fun `refresh reads the newest page again and replaces what is held`() = runTest(dispatcher) {
        repeat(150) { fake.log("line $it") }
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()
        viewModel.onAction(LogAction.LoadOlder)
        runCurrent()

        fake.log("new line")
        viewModel.onAction(LogAction.Refresh)
        runCurrent()

        assertEquals(listOf("new line") + (149 downTo 51).map { "line $it" }, viewModel.state.value.texts())
        assertTrue(viewModel.state.value.canLoadOlder)
    }

    @Test
    fun `a new level reads from the newest line with that level`() = runTest(dispatcher) {
        fake.log("info")
        fake.log("bad", level = "error")
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        viewModel.onAction(LogAction.SetThreshold(LogThreshold.Error))
        runCurrent()

        assertEquals(LogThreshold.Error, viewModel.state.value.threshold)
        assertEquals(listOf("bad"), viewModel.state.value.texts())
    }

    @Test
    fun `a boot header starts each boot's lines`() = runTest(dispatcher) {
        fake.logBoot = "40d4c4f4"
        fake.log("before reboot")
        fake.logBoot = "36276c97"
        fake.log("after reboot")
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        val rows = viewModel.state.value.rows
        assertEquals(listOf(LogRow.BootStart("36276c97"), LogRow.BootStart("40d4c4f4")), rows.filterIsInstance<LogRow.BootStart>())
        assertEquals(listOf(0, 2), rows.indices.filter { rows[it] is LogRow.BootStart })
    }

    @Test
    fun `a failed read keeps the lines and says which read to repeat`() = runTest(dispatcher) {
        repeat(150) { fake.log("line $it") }
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        fake.logUnavailable = "cannot open the journal: Permission denied"
        viewModel.onAction(LogAction.LoadOlder)
        runCurrent()

        val failure = viewModel.state.value.failure!!
        assertEquals(LogUiState.Loading.Older, failure.read)
        assertEquals(DomainError.Remote("unavailable", "cannot open the journal: Permission denied"), failure.error)
        assertEquals(100, viewModel.state.value.texts().size)

        fake.logUnavailable = null
        viewModel.onAction(LogAction.LoadOlder)
        runCurrent()
        assertNull(viewModel.state.value.failure)
        assertEquals(150, viewModel.state.value.texts().size)
    }

    @Test
    fun `an older scanner is told apart from one that has not said yet`() = runTest(dispatcher) {
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.WIFI_COMMANDS)
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        assertEquals(LogUiState.Access.NotSupported, viewModel.state.value.access)
        assertEquals(0, logReads)
    }

    @Test
    fun `lines stay after the link drops, and go when another scanner is chosen`() = runTest(dispatcher) {
        fake.log("started")
        val (viewModel, repository) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        fake.dropLink()
        runCurrent()
        assertEquals(listOf("started"), viewModel.state.value.texts())
        assertFalse(viewModel.state.value.access == LogUiState.Access.Live)

        // Another scanner's log is read anew (the fake plays it with the same lines).
        repository.connect(ScannerCandidate("CC:DD", "dLidar 2", -50))
        runCurrent()
        assertEquals(2, logReads)
        assertEquals(listOf("started"), viewModel.state.value.texts())
    }

    @Test
    fun `the export is oldest first`() = runTest(dispatcher) {
        fake.log("first", t = 0)
        fake.log("second", level = "warning", source = "system", t = 1_000)
        val (viewModel, _) = viewModel()
        viewModel.onAction(LogAction.Opened)
        runCurrent()

        assertEquals(
            "1970-01-01 00:00:00.000 36276c97 INFO dlidar: first\n1970-01-01 00:00:01.000 36276c97 WARNING system: second",
            viewModel.exportText(ZoneOffset.UTC),
        )
    }
}
