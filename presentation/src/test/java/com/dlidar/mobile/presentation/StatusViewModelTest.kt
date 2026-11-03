package com.dlidar.mobile.presentation

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.DisconnectScanner
import com.dlidar.mobile.domain.usecase.DismissWarning
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.RefreshStatus
import com.dlidar.mobile.domain.usecase.StartRecording
import com.dlidar.mobile.domain.usecase.StopRecording
import com.dlidar.mobile.presentation.status.StatusAction
import com.dlidar.mobile.presentation.status.StatusUiState
import com.dlidar.mobile.presentation.status.StatusViewModel
import com.dlidar.mobile.testing.FakeScanner
import com.dlidar.mobile.testing.FakeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** The status screen's ViewModel on the real repository and protocol stack, against the fake scanner. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val fake = FakeScanner()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): Pair<StatusViewModel, ScannerRepositoryImpl> {
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH })
        val viewModel = StatusViewModel(
            ObserveScanner(repository),
            RefreshStatus(repository),
            ConnectScanner(repository),
            DisconnectScanner(repository),
            DismissWarning(repository),
            StartRecording(repository),
            StopRecording(repository),
        )
        repository.connect(ScannerCandidate("AA:BB", "dLidar", -50))
        runCurrent()
        return viewModel to repository
    }

    @Test
    fun `the screen follows a recording`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        assertTrue(viewModel.state.value.isLive)
        assertEquals(RecordingState.Idle, viewModel.state.value.status?.state)

        fake.changeStatus(FakeStatus(state = "recording"))
        fake.progress(3, 600)
        fake.warning("storage_slow", "The USB drive is slow; the recording may drop points.")
        runCurrent()
        viewModel.state.value.let {
            assertEquals(RecordingState.Recording, it.status?.state)
            assertEquals(3L, it.status?.chunks)
            assertEquals("The USB drive is slow; the recording may drop points.", it.warnings.single().text)
        }

        fake.changeStatus(fake.status.copy(state = "idle"))
        fake.summary()
        runCurrent()
        assertEquals("keep", viewModel.state.value.summary?.decision)
    }

    @Test
    fun `values stay visible but not live when the link drops`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.dropLink()
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLive)
        assertTrue(state.connection is ConnectionState.WaitingToRetry)
        assertEquals(RecordingState.Idle, state.status?.state)

        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(viewModel.state.value.isLive)
    }

    @Test
    fun `a failed refresh is shown`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.silentCommands += "status.get"
        viewModel.onAction(StatusAction.Refresh)
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(DomainError.Timeout, viewModel.state.value.refreshError)

        // A new connection clears it.
        fake.dropLink()
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(viewModel.state.value.isLive)
        assertEquals(null, viewModel.state.value.refreshError)
    }

    @Test
    fun `dismiss removes a warning and disconnect stops the link`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.warning("lens_dirty", "Clean the lens.")
        runCurrent()
        viewModel.onAction(StatusAction.DismissWarning(viewModel.state.value.warnings.single().id))
        runCurrent()
        assertTrue(viewModel.state.value.warnings.isEmpty())

        viewModel.onAction(StatusAction.Disconnect)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, viewModel.state.value.connection)
        assertTrue(viewModel.state.value.canRetry)
        assertFalse(fake.isConnected)
    }

    @Test
    fun `start and stop follow the recording state`() = runTest(dispatcher) {
        fake.recordingAdvances = false
        val (viewModel, _) = viewModel()
        viewModel.state.value.let {
            assertTrue(it.showRecordingControls)
            assertTrue(it.canStart)
            assertFalse(it.canStop)
        }

        viewModel.onAction(StatusAction.StartRecording)
        runCurrent()
        assertEquals(RecordingState.Preparing, viewModel.state.value.status?.state)
        assertFalse(viewModel.state.value.canStart)
        assertTrue(viewModel.state.value.canStop)

        fake.changeStatus(fake.status.copy(state = "recording"))
        runCurrent()
        viewModel.onAction(StatusAction.StopRecording)
        runCurrent()
        viewModel.state.value.let {
            assertEquals(RecordingState.Finalizing, it.status?.state)
            assertTrue(it.saving)
            assertFalse(it.canStart)
            assertFalse(it.canStop)
        }
        assertEquals(listOf("recording.start", "recording.stop"), fake.commandNames.filter { it.startsWith("recording.") })
        assertEquals(emptyList<String>(), fake.violations)
    }

    @Test
    fun `busy is shown, and cleared when the state moves on`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        // The tap and the scanner's switch to finalizing cross: Start was still on.
        viewModel.onAction(StatusAction.StartRecording)
        fake.changeStatus(fake.status.copy(state = "finalizing"))
        runCurrent()
        assertTrue(viewModel.state.value.saving)
        assertEquals(
            StatusUiState.CommandFailure(StatusUiState.RecordingCommand.Start, DomainError.Busy),
            viewModel.state.value.commandFailure,
        )

        fake.changeStatus(fake.status.copy(state = "idle"))
        runCurrent()
        assertEquals(null, viewModel.state.value.commandFailure)
        assertTrue(viewModel.state.value.canStart)
    }

    @Test
    fun `a second tap while a command is on its way sends nothing`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.silentCommands += "recording.start"

        viewModel.onAction(StatusAction.StartRecording)
        runCurrent()
        assertFalse(viewModel.state.value.canStart)
        viewModel.onAction(StatusAction.StartRecording)
        runCurrent()
        assertEquals(1, fake.commandNames.count { it == "recording.start" })

        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(DomainError.Timeout, viewModel.state.value.commandFailure?.error)
        assertTrue(viewModel.state.value.canStart)
    }

    @Test
    fun `a command lost with the link leaves no notice on the new connection`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.silentCommands += "recording.start"
        viewModel.onAction(StatusAction.StartRecording)
        runCurrent()

        fake.dropLink()
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()

        assertTrue(viewModel.state.value.isLive)
        assertEquals(null, viewModel.state.value.commandFailure)
        assertTrue(viewModel.state.value.canStart)
    }

    @Test
    fun `an older scanner shows no recording buttons`() = runTest(dispatcher) {
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.OLD_COMMANDS)
        val (viewModel, _) = viewModel()
        assertTrue(viewModel.state.value.isLive)
        assertFalse(viewModel.state.value.showRecordingControls)
        assertFalse(viewModel.state.value.canStart)
    }

    @Test
    fun `another phone connected can be retried`() = runTest(dispatcher) {
        val (viewModel, _) = viewModel()
        fake.sendUnsolicitedError("too_many_clients")
        runCurrent()
        assertTrue(viewModel.state.value.connection is ConnectionState.Refused)
        assertTrue(viewModel.state.value.canRetry)

        viewModel.onAction(StatusAction.Retry)
        runCurrent()
        assertTrue(viewModel.state.value.isLive)
    }
}
