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
)

@Serializable
internal data class StatusDto(
    val state: String,
    val chunks: Long,
    val points: Long,
    @SerialName("last_ok") val lastOk: Boolean,
    val location: String,
    @SerialName("free_bytes") val freeBytes: Long? = null,
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
