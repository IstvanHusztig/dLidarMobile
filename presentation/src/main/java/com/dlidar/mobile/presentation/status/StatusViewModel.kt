package com.dlidar.mobile.presentation.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.DisconnectScanner
import com.dlidar.mobile.domain.usecase.DismissWarning
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.RefreshStatus
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
import kotlinx.coroutines.launch

class StatusViewModel(
    private val observeScanner: ObserveScanner,
    private val refreshStatus: RefreshStatus,
    private val connectScanner: ConnectScanner,
    private val disconnectScanner: DisconnectScanner,
    private val dismissWarning: DismissWarning,
) : ViewModel() {

    private val refreshError = MutableStateFlow<DomainError?>(null)

    val state: StateFlow<StatusUiState> = combine(observeScanner(), refreshError) { snapshot, error ->
        StatusUiState.from(snapshot, error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState.from(observeScanner().value))

    init {
        // A failed refresh says nothing about a new connection.
        observeScanner().map { it.connection }.distinctUntilChanged().drop(1)
            .onEach { refreshError.value = null }
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
        }
    }
}
