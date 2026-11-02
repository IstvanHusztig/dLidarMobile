package com.dlidar.mobile.domain.model

/** Kind of a scanner warning. Values this app does not know map to [Unknown]. */
sealed interface WarningKind {
    data object LensDirty : WarningKind
    data object StorageFailed : WarningKind
    data object StorageSlow : WarningKind
    data object StorageLowSpace : WarningKind
    data object SensorFailed : WarningKind
    data object SensorSilent : WarningKind
    data object RecordingFailed : WarningKind
    data class Unknown(val raw: String) : WarningKind
}
