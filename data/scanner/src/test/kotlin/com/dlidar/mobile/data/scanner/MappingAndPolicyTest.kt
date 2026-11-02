package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.codec.ProtocolEvent
import com.dlidar.mobile.data.protocol.codec.RemoteError
import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.scanner.dto.HelloInfo
import com.dlidar.mobile.data.scanner.event.EventContext
import com.dlidar.mobile.data.scanner.event.EventDecoding
import com.dlidar.mobile.data.scanner.event.ScannerEventDecoders
import com.dlidar.mobile.domain.model.Compatibility
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.result.DomainError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class MappingAndPolicyTest {

    @Test
    fun `known and unknown enum values`() {
        assertEquals(RecordingState.Finalizing, ProtocolMappers.recordingState("finalizing"))
        assertEquals(RecordingState.Unknown("paused"), ProtocolMappers.recordingState("paused"))
        assertEquals(SensorType.LivoxMid360s, ProtocolMappers.sensor("livox_mid360s"))
        assertEquals(SensorType.Unknown("ouster"), ProtocolMappers.sensor("ouster"))
        assertEquals(WarningKind.StorageLowSpace, ProtocolMappers.warningKind("storage_low_space"))
        assertEquals(WarningKind.Unknown("hot"), ProtocolMappers.warningKind("hot"))
    }

    @Test
    fun `failures map to domain errors, unknown codes stay generic`() {
        fun remote(code: String) = CallFailure.Remote(RemoteError(ErrorCode.fromWire(code), "m", JsonObject(emptyMap())))
        assertEquals(DomainError.Busy, ProtocolMappers.error(remote("busy")))
        assertEquals(DomainError.Remote("weird", "m"), ProtocolMappers.error(remote("weird")))
        assertEquals(DomainError.Timeout, ProtocolMappers.error(CallFailure.Timeout))
        assertEquals(DomainError.InvalidResponse, ProtocolMappers.error(CallFailure.BadResult("x")))
    }

    @Test
    fun `hello decides compatibility`() {
        fun hello(protocol: Int?) = CallResult.Success(HelloInfo(protocol = protocol, maxRequestBytes = null, maxMessageBytes = null))
        assertEquals(Compatibility.Compatible, CompatibilityChecker.check(hello(1)))
        assertEquals(Compatibility.UpdateAppRequired(2), CompatibilityChecker.check(hello(2)))
        assertEquals(Compatibility.Unknown, CompatibilityChecker.check(hello(null)))

        val unsupported = RemoteError(
            ErrorCode.UnsupportedVersion,
            "no",
            buildJsonObject { put("supported", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(2)) }) },
        )
        assertEquals(
            Compatibility.UpdateAppRequired(2),
            CompatibilityChecker.check(CallResult.Failure(CallFailure.Remote(unsupported))),
        )
        val unknownCommand = RemoteError(ErrorCode.UnknownCommand, "no", JsonObject(emptyMap()))
        assertEquals(Compatibility.Unknown, CompatibilityChecker.check(CallResult.Failure(CallFailure.Remote(unknownCommand))))
        assertEquals(Compatibility.Unknown, CompatibilityChecker.check(CallResult.Failure(CallFailure.Timeout)))
    }

    @Test
    fun `reconnect delays double up to the maximum`() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30), (1..7).map { policy.delayAfter(it).inWholeSeconds.toInt() })
        assertEquals(30.seconds, policy.delayAfter(1000))
        assertTrue(policy.shouldRetry(DownReason.ConnectionLost(8)))
        assertFalse(policy.shouldRetry(DownReason.PermissionMissing))
        assertTrue(policy.shouldRetry(DownReason.BluetoothOff))
        assertEquals(listOf(1, 2, 4, 8, 8), (1..5).map { CallRetry.delayAfter(it).inWholeSeconds.toInt() })
    }

    @Test
    fun `event decoders ignore unknown events and report invalid data`() {
        val decoders = ScannerEventDecoders.v1()
        val context = EventContext(Instant.EPOCH) { 1 }
        fun event(name: String, data: JsonObject) = ProtocolEvent(name, 1, null, data, JsonObject(emptyMap()))

        assertEquals(EventDecoding.Unknown, decoders.decode(event("battery", JsonObject(emptyMap())), context))
        assertTrue(decoders.decode(event("progress", buildJsonObject { put("chunks", "x") }), context) is EventDecoding.Invalid)
        val progress = decoders.decode(event("progress", buildJsonObject { put("chunks", 1); put("points", 2); put("new", 3) }), context)
        assertEquals(EventDecoding.Decoded(ScannerInput.ProgressReported(1, 2)), progress)
    }

    @Test
    fun `the reducer keeps the newest warnings and ignores progress before any status`() {
        var snapshot = ScannerSnapshot()
        snapshot = ScannerStateReducer.reduce(snapshot, ScannerInput.ProgressReported(1, 1))
        assertEquals(null, snapshot.status)

        repeat(25) { i ->
            snapshot = ScannerStateReducer.reduce(
                snapshot,
                ScannerInput.WarningRaised(Warning(i.toLong(), WarningKind.LensDirty, "w$i", Instant.EPOCH)),
            )
        }
        assertEquals(ScannerStateReducer.MAX_WARNINGS, snapshot.warnings.size)
        assertEquals("w24", snapshot.warnings.last().text)

        val status = ScannerStatus(RecordingState.Recording, 1, 2, true, "/x")
        snapshot = ScannerStateReducer.reduce(snapshot, ScannerInput.StatusLoaded(status, 10))
        snapshot = ScannerStateReducer.reduce(snapshot, ScannerInput.ProgressReported(3, 4))
        assertEquals(status.copy(chunks = 3, points = 4), snapshot.status)
        assertEquals(10L, snapshot.freeBytes)
    }
}
