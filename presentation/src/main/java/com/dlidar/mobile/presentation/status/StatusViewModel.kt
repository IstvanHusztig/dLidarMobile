package com.dlidar.mobile.presentation.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.RecordingCommandResult
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.DisconnectScanner
import com.dlidar.mobile.domain.usecase.DismissWarning
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.RefreshStatus
import com.dlidar.mobile.domain.usecase.StartRecording
import com.dlidar.mobile.domain.usecase.StopRecording
import com.dlidar.mobile.presentation.status.StatusUiState.CommandFailure
import com.dlidar.mobile.presentation.status.StatusUiState.RecordingCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class StatusViewModel(
    private val observeScanner: ObserveScanner,
    private val refreshStatus: RefreshStatus,
    private val connectScanner: ConnectScanner,
    private val disconnectScanner: DisconnectScanner,
    private val dismissWarning: DismissWarning,
    private val startRecording: StartRecording,
    private val stopRecording: StopRecording,
) : ViewModel() {

    private val refreshError = MutableStateFlow<DomainError?>(null)
    /** A start or stop is waiting for its answer; both buttons are off meanwhile. */
    private val sending = MutableStateFlow(false)
    private val commandFailure = MutableStateFlow<CommandFailure?>(null)
    /** Counts connection changes, so a command's failure from an older connection is dropped. */
    @Volatile private var connectionEpoch = 0

    val state: StateFlow<StatusUiState> = combine(observeScanner(), refreshError, sending, commandFailure) { snapshot, error, busy, failure ->
        StatusUiState.from(snapshot, error, busy, failure)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState.from(observeScanner().value))

    init {
        // A failed refresh says nothing about a new connection.
        observeScanner().map { it.connection }.distinctUntilChanged().drop(1)
            .onEach {
                connectionEpoch++
                refreshError.value = null
                commandFailure.value = null
            }
            .launchIn(viewModelScope)
        // A failed start or stop is old news once the recording state moves (busy, then idle).
        observeScanner().map { it.status?.state }.distinctUntilChanged().drop(1)
            .onEach { commandFailure.value = null }
            .launchIn(viewModelScope)
    }

    fun onAction(action: StatusAction) {
        when (action) {
            StatusAction.Refresh -> viewModelScope.launch {
                refreshError.value = (refreshStatus() as? Outcome.Failed)?.error
            }
            StatusAction.Retry -> observeScanner().value.target?.let(connectScanner::invoke)
            StatusAction.Disconnect -> disconnectScanner()
            is StatusAction.DismissWarning -> dismissWarning(action.warningId)
            StatusAction.StartRecording -> send(RecordingCommand.Start) { startRecording() }
            StatusAction.StopRecording -> send(RecordingCommand.Stop) { stopRecording() }
        }
    }

    /**
     * Sends one command at a time. A not-accepted answer needs nothing (the scanner was already
     * there). After an accepted one the buttons stay off until the state event, which follows
     * the answer, so Start does not flash back on in between.
     */
    private fun send(command: RecordingCommand, call: suspend () -> Outcome<RecordingCommandResult>) {
        if (!sending.compareAndSet(expect = false, update = true)) return
        commandFailure.value = null
        val epoch = connectionEpoch
        val stateBefore = observeScanner().value.status?.state
        viewModelScope.launch {
            try {
                when (val outcome = call()) {
                    is Outcome.Failed -> if (epoch == connectionEpoch) commandFailure.value = CommandFailure(command, outcome.error)
                    is Outcome.Ok -> if (outcome.value.accepted) {
                        withTimeoutOrNull(STATE_WAIT) {
                            observeScanner().first { it.status?.state != stateBefore || it.connection != ConnectionState.Connected }
                        }
                    }
                }
            } finally {
                sending.value = false
            }
        }
    }

    private companion object {
        /** App choice: how long the buttons wait for the state event after an accepted command. */
        val STATE_WAIT = 5.seconds
    }
}
