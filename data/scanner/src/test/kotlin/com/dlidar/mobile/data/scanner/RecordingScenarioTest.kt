@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.testing.FakeScanner
import com.dlidar.mobile.testing.FakeStatus
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A whole recording, played by the fake scanner with a small MTU so every message is split. */
class RecordingScenarioTest {

    @Test
    fun `preparing, recording, progress, finalizing, idle, summary`() = runTest {
        val fake = FakeScanner(mtu = 23)
        val repository = ScannerRepositoryImpl(fake, DefaultProtocolSessionFactory(), backgroundScope, { Instant.EPOCH })
        repository.connect(ScannerCandidate("AA:BB", "dLidar", -40))
        runCurrent()
        assertEquals(ConnectionState.Connected, repository.snapshot.value.connection)
        assertEquals(RecordingState.Idle, repository.snapshot.value.status?.state)

        fake.changeStatus(FakeStatus(state = "preparing"))
        runCurrent()
        assertEquals(RecordingState.Preparing, repository.snapshot.value.status?.state)

        fake.changeStatus(FakeStatus(state = "recording"))
        for (second in 1L..5L) fake.progress(chunks = second, points = second * 200_000)
        runCurrent()
        repository.snapshot.value.status!!.let {
            assertEquals(RecordingState.Recording, it.state)
            assertEquals(5, it.chunks)
            assertEquals(1_000_000, it.points)
        }

        fake.changeStatus(fake.status.copy(state = "finalizing"))
        runCurrent()
        assertEquals(RecordingState.Finalizing, repository.snapshot.value.status?.state)
        assertNull(repository.snapshot.value.lastSummary)

        fake.changeStatus(fake.status.copy(state = "idle", lastOk = true))
        fake.summary(imuMode = "fused", decision = "keep", imuIn = 50_000, imuOut = 49_990, lostSamples = 10)
        runCurrent()

        val snapshot = repository.snapshot.value
        assertEquals(RecordingState.Idle, snapshot.status?.state)
        assertEquals(5L, snapshot.status?.chunks)
        assertTrue(snapshot.status!!.lastOk)
        snapshot.lastSummary!!.let {
            assertEquals("fused", it.imuMode)
            assertEquals("keep", it.decision)
            assertEquals(50_000, it.imuIn)
            assertEquals(49_990, it.imuOut)
            assertEquals(10, it.lostSamples)
        }
        // No gaps: status.get was sent only once, on connect.
        assertEquals(1, fake.commandNames.count { it == "status.get" })
        assertEquals(emptyList<String>(), fake.violations)
    }
}
