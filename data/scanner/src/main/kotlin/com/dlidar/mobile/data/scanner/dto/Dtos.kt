package com.dlidar.mobile.data.scanner.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Wire shapes of protocol v1. Enum-like fields stay strings here; the mappers turn unknown
// values into generic ones. Unknown fields are ignored by ScannerJson.

internal val ScannerJson = Json { ignoreUnknownKeys = true }

/** What the app reads from hello. */
internal data class HelloInfo(
    val protocol: Int?,
    val maxRequestBytes: Int?,
    val maxMessageBytes: Int?,
    /** The commands hello lists; null when it lists none (or hello failed). */
    val commands: Set<String>? = null,
)

/** The answer to wifi.connect: the join started; the outcome comes in a result event. */
@Serializable
internal data class WifiConnectAnswerDto(
    val accepted: Boolean,
    @SerialName("timeout_s") val timeoutS: Int? = null,
)

@Serializable
internal data class WifiStatusDto(
    val state: String,
    val ssid: String? = null,
    val ip: String? = null,
    val last: LastJoinDto? = null,
)

/** [result] is "pending", "connected" or an error code. */
@Serializable
internal data class LastJoinDto(val ssid: String, val result: String)

/** The answer to recording.start and recording.stop. */
@Serializable
internal data class RecordingCommandDto(
    val accepted: Boolean,
    /** The state the command saw, not the state after it. */
    val state: String,
)

@Serializable
internal data class StatusDto(
    val state: String,
    val chunks: Long,
    val points: Long,
    @SerialName("last_ok") val lastOk: Boolean,
    val location: String,
    @SerialName("free_bytes") val freeBytes: Long? = null,
    /** The current or last recording's warnings, the 16 newest. Null when the field is absent. */
    val warnings: List<WarningDto>? = null,
    /** The last recording's summary; null before the first recording. */
    val summary: SummaryDto? = null,
)

@Serializable
internal data class StateEventDto(
    val state: String,
    val chunks: Long,
    val points: Long,
    @SerialName("last_ok") val lastOk: Boolean,
    val location: String,
)

@Serializable
internal data class InfoDto(
    val version: String,
    val sensor: String,
    @SerialName("sensor_model") val sensorModel: String,
    @SerialName("recording_format") val recordingFormat: Int,
    val protocol: Int,
)

@Serializable
internal data class WarningDto(
    val kind: String,
    val text: String,
)

@Serializable
internal data class ProgressDto(
    val chunks: Long,
    val points: Long,
)

@Serializable
internal data class SummaryDto(
    @SerialName("imu_mode") val imuMode: String,
    val decision: String,
    @SerialName("imu_in") val imuIn: Long,
    @SerialName("imu_out") val imuOut: Long,
    @SerialName("lost_samples") val lostSamples: Long,
)
