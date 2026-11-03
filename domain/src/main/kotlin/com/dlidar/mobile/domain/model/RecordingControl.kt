package com.dlidar.mobile.domain.model

/**
 * The scanner's answer to a start or stop. [seenState] is the state the command found, not the
 * state after it; the state events that follow tell what really happened. [accepted] false means
 * there was nothing to do (already recording, or nothing to stop); it is not an error.
 */
data class RecordingCommandResult(val accepted: Boolean, val seenState: RecordingState)

/** Which recording commands make sense in a state. The scanner decides; this only guides the UI. */
object RecordingControl {
    /** Spec: start begins a recording from idle or error; while finalizing it answers busy. */
    fun canStart(state: RecordingState): Boolean = state == RecordingState.Idle || state == RecordingState.Error

    /** Spec: stop ends a recording, or abandons one still preparing. */
    fun canStop(state: RecordingState): Boolean = state == RecordingState.Preparing || state == RecordingState.Recording
}
