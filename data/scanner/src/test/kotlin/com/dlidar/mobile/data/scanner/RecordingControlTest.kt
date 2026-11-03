@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.RecordingCommandResult
import com.dlidar.mobile.domain.model.RecordingControl
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.testing.FakeScanner
import com.dlidar.mobile.testing.FakeStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** recording.start and recording.stop against the fake scanner, which follows the spec's answers. */
class RecordingControlTest {

    private val fake = FakeScanner()

    private fun TestScope.connected(): ScannerRepositoryImpl =
        ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH }).also {
            it.connect(ScannerCandidate("AA:BB", "dLidar", -50))
            runCurrent()
        }

    @After
    fun noProtocolViolations() {
        assertEquals(emptyList<String>(), fake.violations)
    }

    @Test
    fun `hello listing both commands offers recording control`() = runTest {
        assertTrue(connected().snapshot.value.canControlRecording)
    }

    @Test
    fun `an older scanner gets no recording command`() = runTest {
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.OLD_COMMANDS)
        val repository = connected()

        assertFalse(repository.snapshot.value.canControlRecording)
        assertEquals(Outcome.Failed(DomainError.NotSupported), repository.startRecording())
        assertFalse("recording.start" in fake.commandNames)
    }

    @Test
    fun `start from idle is accepted with the state it saw, and the events carry the rest`() = runTest {
        fake.recordingAdvances = false
        val repository = connected()

        val answer = repository.startRecording()

        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = true, seenState = RecordingState.Idle)), answer)
        runCurrent()
        assertEquals(RecordingState.Preparing, repository.snapshot.value.status?.state)
        fake.changeStatus(fake.status.copy(state = "recording"))
        runCurrent()
        assertEquals(RecordingState.Recording, repository.snapshot.value.status?.state)
    }

    @Test
    fun `start while recording is not accepted and is not an error`() = runTest {
        fake.status = FakeStatus(state = "recording")
        val repository = connected()

        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = false, seenState = RecordingState.Recording)), repository.startRecording())
    }

    @Test
    fun `start while finalizing is busy`() = runTest {
        fake.status = FakeStatus(state = "finalizing")
        val repository = connected()

        assertEquals(Outcome.Failed(DomainError.Busy), repository.startRecording())
        assertEquals(RecordingState.Finalizing, repository.snapshot.value.status?.state)
    }

    @Test
    fun `stop while recording finalizes, then idles`() = runTest {
        fake.status = FakeStatus(state = "recording")
        fake.recordingAdvances = false
        val repository = connected()

        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = true, seenState = RecordingState.Recording)), repository.stopRecording())
        runCurrent()
        assertEquals(RecordingState.Finalizing, repository.snapshot.value.status?.state)
    }

    @Test
    fun `stop while preparing abandons the start-up`() = runTest {
        fake.status = FakeStatus(state = "preparing")
        val repository = connected()

        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = true, seenState = RecordingState.Preparing)), repository.stopRecording())
        runCurrent()
        assertEquals(RecordingState.Idle, repository.snapshot.value.status?.state)
    }

    @Test
    fun `stop while idle is not accepted`() = runTest {
        val repository = connected()
        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = false, seenState = RecordingState.Idle)), repository.stopRecording())
    }

    @Test
    fun `nothing is sent while disconnected`() = runTest {
        val repository = connected()
        repository.disconnect()
        runCurrent()

        assertEquals(Outcome.Failed(DomainError.NotConnected), repository.stopRecording())
        assertFalse("recording.stop" in fake.commandNames)
    }

    @Test
    fun `a new connection forgets recording control until hello says so again`() = runTest {
        val repository = connected()
        fake.hello = FakeScanner.defaultHello(commands = FakeScanner.OLD_COMMANDS)

        fake.dropLink()
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertFalse(repository.snapshot.value.canControlRecording)
    }

    @Test
    fun `start from error is accepted, start while preparing is not`() = runTest {
        fake.status = FakeStatus(state = "error")
        fake.recordingAdvances = false
        val repository = connected()

        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = true, seenState = RecordingState.Error)), repository.startRecording())
        runCurrent()
        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = false, seenState = RecordingState.Preparing)), repository.startRecording())
    }

    @Test
    fun `stop while error or finalizing is not accepted`() = runTest {
        fake.status = FakeStatus(state = "error")
        val repository = connected()
        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = false, seenState = RecordingState.Error)), repository.stopRecording())

        fake.changeStatus(fake.status.copy(state = "finalizing"))
        runCurrent()
        assertEquals(Outcome.Ok(RecordingCommandResult(accepted = false, seenState = RecordingState.Finalizing)), repository.stopRecording())
    }

    @Test
    fun `nothing is sent after a protocol mismatch`() = runTest {
        fake.hello = FakeScanner.defaultHello(protocol = 2)
        val repository = connected()

        assertEquals(Outcome.Failed(DomainError.IncompatibleProtocol), repository.startRecording())
        assertFalse("recording.start" in fake.commandNames)
    }

    @Test
    fun `a hello with a malformed commands list offers no recording control`() = runTest {
        fake.hello = buildJsonObject {
            put("protocol", 1)
            put("commands", buildJsonArray {
                add(JsonPrimitive(7))
                add(JsonPrimitive("recording.start"))
            })
        }
        assertFalse(connected().snapshot.value.canControlRecording)
    }

    @Test
    fun `which command fits which state`() {
        assertTrue(RecordingControl.canStart(RecordingState.Idle))
        assertTrue(RecordingControl.canStart(RecordingState.Error))
        listOf(RecordingState.Preparing, RecordingState.Recording, RecordingState.Finalizing, RecordingState.Unknown("x"))
            .forEach { assertFalse(RecordingControl.canStart(it)) }
        assertTrue(RecordingControl.canStop(RecordingState.Preparing))
        assertTrue(RecordingControl.canStop(RecordingState.Recording))
        listOf(RecordingState.Idle, RecordingState.Error, RecordingState.Finalizing, RecordingState.Unknown("x"))
            .forEach { assertFalse(RecordingControl.canStop(it)) }
    }
}
