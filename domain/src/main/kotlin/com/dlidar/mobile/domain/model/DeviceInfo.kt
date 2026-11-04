package com.dlidar.mobile.domain.model

/** Software and sensor information, as reported by info.get. */
data class DeviceInfo(
    val version: String,
    val sensor: SensorType,
    val sensorModel: String,
    val recordingFormat: Int,
    val protocol: Int,
    /** The scanner's host name; null until the scanner reports it (open question 37). */
    val hostname: String? = null,
)
