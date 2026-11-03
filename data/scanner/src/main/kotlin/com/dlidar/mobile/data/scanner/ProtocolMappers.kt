package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.scanner.dto.InfoDto
import com.dlidar.mobile.data.scanner.dto.RecordingCommandDto
import com.dlidar.mobile.data.scanner.dto.StateEventDto
import com.dlidar.mobile.data.scanner.dto.StatusDto
import com.dlidar.mobile.data.scanner.dto.SummaryDto
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingCommandResult
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.result.DomainError
import java.time.Instant

/** Wire DTOs to domain types. Unknown enum values become the generic `Unknown(raw)`. */
internal object ProtocolMappers {

    fun recordingState(raw: String): RecordingState = when (raw) {
        "idle" -> RecordingState.Idle
        "preparing" -> RecordingState.Preparing
        "recording" -> RecordingState.Recording
        "finalizing" -> RecordingState.Finalizing
        "error" -> RecordingState.Error
        else -> RecordingState.Unknown(raw)
    }

    fun sensor(raw: String): SensorType = when (raw) {
        "unitree_l2" -> SensorType.UnitreeL2
        "livox_mid360s" -> SensorType.LivoxMid360s
        else -> SensorType.Unknown(raw)
    }

    fun warningKind(raw: String): WarningKind = when (raw) {
        "lens_dirty" -> WarningKind.LensDirty
        "storage_failed" -> WarningKind.StorageFailed
        "storage_slow" -> WarningKind.StorageSlow
        "storage_low_space" -> WarningKind.StorageLowSpace
        "sensor_failed" -> WarningKind.SensorFailed
        "sensor_silent" -> WarningKind.SensorSilent
        "recording_failed" -> WarningKind.RecordingFailed
        else -> WarningKind.Unknown(raw)
    }

    fun status(dto: StatusDto) = ScannerStatus(recordingState(dto.state), dto.chunks, dto.points, dto.lastOk, dto.location)

    fun status(dto: StateEventDto) = ScannerStatus(recordingState(dto.state), dto.chunks, dto.points, dto.lastOk, dto.location)

    fun info(dto: InfoDto) = DeviceInfo(dto.version, sensor(dto.sensor), dto.sensorModel, dto.recordingFormat, dto.protocol)

    fun recordingCommand(dto: RecordingCommandDto) = RecordingCommandResult(dto.accepted, recordingState(dto.state))

    fun summary(dto: SummaryDto, receivedAt: Instant) =
        RecordingSummary(dto.imuMode, dto.decision, dto.imuIn, dto.imuOut, dto.lostSamples, receivedAt)

    fun error(failure: CallFailure): DomainError = when (failure) {
        is CallFailure.Remote -> when (failure.error.code) {
            ErrorCode.Busy -> DomainError.Busy
            ErrorCode.TooLarge -> DomainError.RequestTooLarge
            else -> DomainError.Remote(failure.error.code.wire, failure.error.message)
        }
        CallFailure.Timeout -> DomainError.Timeout
        CallFailure.LinkLost -> DomainError.LinkLost
        CallFailure.NotConnected -> DomainError.NotConnected
        is CallFailure.TooLarge -> DomainError.RequestTooLarge
        is CallFailure.WriteFailed -> DomainError.LinkLost
        is CallFailure.BadResult -> DomainError.InvalidResponse
    }
}
