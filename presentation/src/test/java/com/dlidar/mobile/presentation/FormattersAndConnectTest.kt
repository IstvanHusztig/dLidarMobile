package com.dlidar.mobile.presentation

import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.port.ScannerRepository
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.FindScanners
import com.dlidar.mobile.presentation.connect.ConnectAction
import com.dlidar.mobile.presentation.connect.ConnectViewModel
import com.dlidar.mobile.presentation.format.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class FormattersAndConnectTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `bytes use decimal units`() {
        assertEquals("999 B", Formatters.bytes(999, Locale.US))
        assertEquals("1.5 kB", Formatters.bytes(1_500, Locale.US))
        assertEquals("64.0 GB", Formatters.bytes(64_000_000_000, Locale.US))
        assertEquals("1.0 MB", Formatters.bytes(999_999, Locale.US))
        assertEquals("999.9 kB", Formatters.bytes(999_940, Locale.US))
        assertEquals("1,234,567", Formatters.count(1_234_567, Locale.US))
    }

    private class RecordingRepository : ScannerRepository {
        val connected = mutableListOf<ScannerCandidate>()
        override val snapshot: StateFlow<ScannerSnapshot> = MutableStateFlow(ScannerSnapshot())
        override fun connect(candidate: ScannerCandidate) { connected += candidate }
        override fun disconnect() = Unit
        override suspend fun refreshStatus(): Outcome<Unit> = Outcome.Ok(Unit)
        override fun dismissWarning(warningId: Long) = Unit
    }

    private val candidate = ScannerCandidate("AA:BB", "dLidar", -40)

    @Test
    fun `a search lists candidates, stops after its duration, and select connects`() = runTest(dispatcher) {
        val repository = RecordingRepository()
        val finder = FindScanners {
            flow {
                emit(Discovery.Found(listOf(candidate)))
                awaitCancellation()
            }
        }
        val viewModel = ConnectViewModel(finder, ConnectScanner(repository))

        viewModel.onAction(ConnectAction.StartScan)
        runCurrent()
        assertTrue(viewModel.state.value.scanning)
        assertEquals(listOf(candidate), viewModel.state.value.candidates)

        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(viewModel.state.value.scanning)

        viewModel.onAction(ConnectAction.Select(candidate))
        assertEquals(listOf(candidate), repository.connected)
    }

    @Test
    fun `a problem is shown`() = runTest(dispatcher) {
        val viewModel = ConnectViewModel(
            FindScanners { flow { emit(Discovery.Failed(Discovery.Problem.PermissionMissing)) } },
            ConnectScanner(RecordingRepository()),
        )
        viewModel.onAction(ConnectAction.StartScan)
        runCurrent()
        assertEquals(Discovery.Problem.PermissionMissing, viewModel.state.value.problem)
        assertFalse(viewModel.state.value.scanning)
    }
}
