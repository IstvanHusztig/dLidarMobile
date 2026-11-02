package com.dlidar.mobile.domain.model

/** LiDAR sensor fitted to the scanner. Values this app does not know map to [Unknown]. */
sealed interface SensorType {
    data object UnitreeL2 : SensorType
    data object LivoxMid360s : SensorType
    data class Unknown(val raw: String) : SensorType
}
