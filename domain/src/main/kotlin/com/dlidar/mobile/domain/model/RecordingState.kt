package com.dlidar.mobile.domain.model

/** Recording state reported by the scanner. Values this app does not know map to [Unknown]. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Preparing : RecordingState
    data object Recording : RecordingState
    data object Finalizing : RecordingState
    data object Error : RecordingState
    data class Unknown(val raw: String) : RecordingState
}
