@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.testing.FakeScanner
import com.dlidar.mobile.testing.FakeStatus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScannerRepositoryTest {

    private val fake = FakeScanner()
    private val candidate = ScannerCandidate(id = "AA:BB", name = "dLidar", rssi = -50)

    private fun TestScope.repository() = ScannerRepositoryImpl(
        transport = fake,
        sessionFactory = DefaultProtocolSessionFactory(),
        scope = backgroundScope,
        clock = { Instant.EPOCH },
    )

    private fun TestScope.connected(): ScannerRepositoryImpl = repository().also {
        it.connect(candidate)
        runCurrent()
    }

    @After
    fun noProtocolViolations() {
        assertEquals(emptyList<String>(), fake.violations)
    }

    @Test
    fun `on connect it sends hello first, then info get and status get`() = runTest {
        val repository = connected()

        assertEquals("hello", fake.commandNames.first())
        assertEquals(setOf("info.get", "status.get"), fake.commandNames.drop(1).toSet())
        val snapshot = repository.snapshot.value
        assertEquals(ConnectionState.Connected, snapshot.connection)
        assertEquals(Compatibility.Compatible, snapshot.compatibility)
        assertEquals(RecordingState.Idle, snapshot.status?.state)
        assertEquals(64_000_000_000, snapshot.freeBytes)
        assertEquals(SensorType.UnitreeL2, snapshot.info?.sensor)
    }

    @Test
    fun `a seq gap leads to status get`() = runTest {
        connected()
        fake.progress(1, 10)
        runCurrent()
        val before = fake.commandNames.count { it == "status.get" }

        fake.emitEvent("progress", buildJsonObject { put("chunks", 3); put("points", 30) }, skip = 2)
        runCurrent()

        assertEquals(before + 1, fake.commandNames.count { it == "status.get" })
    }

    @Test
    fun `a dropped field leads to status get`() = runTest {
        connected()
        val before = fake.commandNames.count { it == "status.get" }

        fake.emitEvent("progress", buildJsonObject { put("chunks", 3); put("points", 30) }, dropped = 4)
        runCurrent()

        assertEquals(before + 1, fake.commandNames.count { it == "status.get" })
    }

    @Test
    fun `many gaps while status get is pending send only one more`() = runTest {
        connected()
        fake.silentCommands += "status.get"
        val before = fake.commandNames.count { it == "status.get" }
        repeat(5) { fake.emitEvent("progress", buildJsonObject { put("chunks", 1); put("points", 1) }, skip = 1) }
        runCurrent()

        assertEquals(before + 1, fake.commandNames.count { it == "status.get" })
    }

    @Test
    fun `after the link drops it reconnects and calls status get again`() = runTest {
        val repository = connected()
        fake.dropLink()
        runCurrent()
        assertEquals(ConnectionState.WaitingToRetry(nextAttempt = 2, retryInMillis = 1000), repository.snapshot.value.connection)

        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertEquals(2, fake.commandNames.count { it == "status.get" })
        assertEquals(2, fake.commandNames.count { it == "hello" })
    }

    @Test
    fun `failed attempts back off and reconnect`() = runTest {
        val repository = repository()
        fake.connectFailures += listOf(DownReason.ConnectFailed(133), DownReason.ConnectFailed(133))
        repository.connect(candidate)
        runCurrent()
        assertEquals(ConnectionState.WaitingToRetry(nextAttempt = 2, retryInMillis = 1000), repository.snapshot.value.connection)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ConnectionState.WaitingToRetry(nextAttempt = 3, retryInMillis = 2000), repository.snapshot.value.connection)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertEquals(3, fake.connectAttempts)
    }

    @Test
    fun `bluetooth off waits and reconnects once it is back`() = runTest {
        val repository = repository()
        fake.connectFailures += listOf(DownReason.BluetoothOff, DownReason.BluetoothOff)
        repository.connect(candidate)
        runCurrent()
        assertEquals(ConnectionState.WaitingForBluetooth, repository.snapshot.value.connection)

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(ConnectionState.WaitingForBluetooth, repository.snapshot.value.connection)

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertEquals(3, fake.connectAttempts)
    }

    @Test
    fun `a missing permission is not retried`() = runTest {
        val repository = repository()
        fake.connectFailures += DownReason.PermissionMissing
        repository.connect(candidate)
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(ConnectionState.Failed(ConnectionState.Failed.Reason.PermissionMissing), repository.snapshot.value.connection)
        assertEquals(1, fake.connectAttempts)
    }

    @Test
    fun `a busy first status get is retried until it succeeds`() = runTest {
        fake.scriptedErrors["status.get"] = ArrayDeque(listOf("busy", "busy"))
        val repository = connected()
        assertNull(repository.snapshot.value.status)

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(repository.snapshot.value.status)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(RecordingState.Idle, repository.snapshot.value.status?.state)
        assertEquals(3, fake.commandNames.count { it == "status.get" })
    }

    @Test
    fun `a first info get that times out is retried`() = runTest {
        fake.silentCommands += "info.get"
        val repository = connected()
        advanceTimeBy(5_001)
        runCurrent()
        fake.silentCommands -= "info.get"

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(SensorType.UnitreeL2, repository.snapshot.value.info?.sensor)
        assertEquals(2, fake.commandNames.count { it == "info.get" })
    }

    @Test
    fun `a status get the scanner rejects is not repeated`() = runTest {
        fake.scriptedErrors["status.get"] = ArrayDeque(listOf("bad_request"))
        connected()
        advanceTimeBy(60_000)
        assertEquals(1, fake.commandNames.count { it == "status.get" })
    }

    @Test
    fun `a hello that never answers still leads to status get`() = runTest {
        fake.silentCommands += "hello"
        val repository = connected()
        assertFalse("status.get" in fake.commandNames)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(Compatibility.Unknown, repository.snapshot.value.compatibility)
        assertEquals(RecordingState.Idle, repository.snapshot.value.status?.state)
    }

    @Test
    fun `a hello with an unexpected shape still reveals a protocol mismatch`() = runTest {
        fake.hello = buildJsonObject {
            put("protocol", 2)
            put("commands", "all of them")
            put("limits", "none")
        }
        val repository = connected()
        assertEquals(Compatibility.UpdateAppRequired(2), repository.snapshot.value.compatibility)
    }

    @Test
    fun `refresh after a protocol mismatch sends nothing`() = runTest {
        fake.hello = FakeScanner.defaultHello(protocol = 2)
        val repository = connected()
        assertEquals(Outcome.Failed(DomainError.IncompatibleProtocol), repository.refreshStatus())
        assertEquals(listOf("hello"), fake.commandNames)
    }

    @Test
    fun `too many clients right before the link drops still counts as refused`() = runTest {
        val repository = connected()
        fake.sendUnsolicitedError("too_many_clients")
        fake.dropLink()
        runCurrent()
        advanceTimeBy(60_000)

        assertTrue(repository.snapshot.value.connection is ConnectionState.Refused)
        assertEquals(1, fake.connectAttempts)
    }

    @Test
    fun `links that drop before the scanner says anything back off further`() = runTest {
        fake.silentCommands += listOf("hello", "status.get", "info.get")
        val repository = connected()
        fake.dropLink()
        runCurrent()
        assertEquals(ConnectionState.WaitingToRetry(nextAttempt = 2, retryInMillis = 1000), repository.snapshot.value.connection)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        fake.dropLink()
        runCurrent()
        assertEquals(ConnectionState.WaitingToRetry(nextAttempt = 3, retryInMillis = 2000), repository.snapshot.value.connection)
    }

    @Test
    fun `connect right after disconnect waits for the old cleanup`() = runTest {
        val repository = connected()
        repository.disconnect()
        repository.connect(candidate)
        runCurrent()

        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertTrue(fake.isConnected)
        assertTrue(fake.link.value is LinkState.Up)
    }

    @Test
    fun `three quick connects end with one live connection`() = runTest {
        val repository = repository()
        repeat(3) { repository.connect(candidate) }
        runCurrent()

        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertTrue(fake.link.value is LinkState.Up)
    }

    @Test
    fun `too many clients refuses and closes the link without retrying`() = runTest {
        val repository = connected()
        fake.sendUnsolicitedError("too_many_clients", "another phone is connected")
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(
            ConnectionState.Refused(ConnectionState.Refused.Reason.AnotherPhoneConnected),
            repository.snapshot.value.connection,
        )
        assertEquals(LinkState.Down(DownReason.Closed), fake.link.value)
        assertEquals(1, fake.connectAttempts)
    }

    @Test
    fun `a different protocol asks for an app update and sends nothing more`() = runTest {
        fake.hello = FakeScanner.defaultHello(protocol = 2)
        val repository = connected()

        assertEquals(Compatibility.UpdateAppRequired(2), repository.snapshot.value.compatibility)
        assertEquals(listOf("hello"), fake.commandNames)
    }

    @Test
    fun `a scanner without hello still gets status get`() = runTest {
        fake.hello = null
        val repository = connected()

        assertEquals(Compatibility.Unknown, repository.snapshot.value.compatibility)
        assertTrue("status.get" in fake.commandNames)
        assertEquals(RecordingState.Idle, repository.snapshot.value.status?.state)
    }

    @Test
    fun `unknown fields, unknown events and unknown enum values are tolerated`() = runTest {
        val repository = connected()
        fake.emitEvent("battery", buildJsonObject { put("level", 80) })
        fake.emitEvent(
            "state",
            FakeStatus(state = "calibrating").toStateEvent().let { data ->
                buildJsonObject {
                    data.forEach { (key, value) -> put(key, value) }
                    put("future_field", true)
                }
            },
            extra = mapOf("future_top_level" to JsonPrimitive(1)),
        )
        fake.warning("lens_fogged", "The lens is fogged up.")
        runCurrent()

        val snapshot = repository.snapshot.value
        assertEquals(RecordingState.Unknown("calibrating"), snapshot.status?.state)
        assertEquals(WarningKind.Unknown("lens_fogged"), snapshot.warnings.single().kind)
        assertEquals("The lens is fogged up.", snapshot.warnings.single().text)
    }

    @Test
    fun `a state event keeps the last free bytes`() = runTest {
        val repository = connected()
        fake.changeStatus(FakeStatus(state = "recording", freeBytes = null))
        runCurrent()

        assertEquals(RecordingState.Recording, repository.snapshot.value.status?.state)
        assertEquals(64_000_000_000, repository.snapshot.value.freeBytes)
    }

    @Test
    fun `free bytes null in status get means unknown`() = runTest {
        fake.status = FakeStatus(freeBytes = null)
        val repository = connected()
        assertNull(repository.snapshot.value.freeBytes)
    }

    @Test
    fun `refresh status returns the outcome`() = runTest {
        val repository = connected()
        fake.status = FakeStatus(state = "recording", chunks = 4)
        assertEquals(Outcome.Ok(Unit), repository.refreshStatus())
        assertEquals(4L, repository.snapshot.value.status?.chunks)

        fake.silentCommands += "status.get"
        assertEquals(Outcome.Failed(DomainError.Timeout), repository.refreshStatus())
    }

    @Test
    fun `refresh status without a connection fails`() = runTest {
        assertEquals(Outcome.Failed(DomainError.NotConnected), repository().refreshStatus())
    }

    @Test
    fun `disconnect closes the link and stops reconnecting`() = runTest {
        val repository = connected()
        repository.disconnect()
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(ConnectionState.Disconnected, repository.snapshot.value.connection)
        assertFalse(fake.isConnected)
        assertEquals(1, fake.connectAttempts)
    }

    @Test
    fun `dismissing a warning removes it`() = runTest {
        val repository = connected()
        fake.warning("lens_dirty", "Clean the lens.")
        runCurrent()
        val warning = repository.snapshot.value.warnings.single()

        repository.dismissWarning(warning.id)

        assertTrue(repository.snapshot.value.warnings.isEmpty())
    }

    @Test
    fun `connecting to another scanner starts from an empty snapshot`() = runTest {
        val repository = connected()
        fake.warning("lens_dirty", "Clean the lens.")
        runCurrent()

        repository.connect(ScannerCandidate(id = "CC:DD", name = "dLidar", rssi = -60))
        assertTrue(repository.snapshot.value.warnings.isEmpty())
        runCurrent()
        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
    }
}
