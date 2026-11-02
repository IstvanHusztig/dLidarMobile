package com.dlidar.mobile.presentation.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.FindScanners
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class ConnectUiState(
    val scanning: Boolean = false,
    val candidates: List<ScannerCandidate> = emptyList(),
    val problem: Discovery.Problem? = null,
)

sealed interface ConnectAction {
    data object StartScan : ConnectAction
    data object StopScan : ConnectAction
    data class Select(val candidate: ScannerCandidate) : ConnectAction
}

class ConnectViewModel(
    private val findScanners: FindScanners,
    private val connectScanner: ConnectScanner,
    /** Scanning drains the battery, so a search stops on its own. */
    private val scanDuration: Duration = 30.seconds,
) : ViewModel() {

    private val mutableState = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = mutableState.asStateFlow()
    private var scan: Job? = null

    fun onAction(action: ConnectAction) {
        when (action) {
            ConnectAction.StartScan -> startScan()
            ConnectAction.StopScan -> stopScan()
            is ConnectAction.Select -> {
                stopScan()
                connectScanner(action.candidate)
            }
        }
    }

    private fun startScan() {
        scan?.cancel()
        mutableState.update { it.copy(scanning = true, problem = null) }
        scan = viewModelScope.launch {
            withTimeoutOrNull(scanDuration) {
                findScanners().collect { discovery ->
                    when (discovery) {
                        is Discovery.Found -> mutableState.update { it.copy(candidates = discovery.candidates) }
                        is Discovery.Failed -> mutableState.update { it.copy(problem = discovery.problem, scanning = false) }
                    }
                }
            }
            mutableState.update { it.copy(scanning = false) }
        }
    }

    private fun stopScan() {
        scan?.cancel()
        scan = null
        mutableState.update { it.copy(scanning = false) }
    }
}
