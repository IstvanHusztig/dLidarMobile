package com.dlidar.mobile.presentation.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.JoinWifi
import com.dlidar.mobile.domain.usecase.ReadScannerWifi
import com.dlidar.mobile.domain.usecase.ScanWifiNetworks
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WifiSetupUiState(
    val scanning: Boolean = false,
    /** The phone's networks, merged, strongest first. */
    val networks: List<WifiNetwork> = emptyList(),
    /** Android refused a new scan; [networks] come from an earlier one. */
    val cached: Boolean = false,
    val scanProblem: WifiScan.Problem? = null,
    /** The scanner's Wi-Fi as wifi.status reported it; null until it answered. */
    val scannerWifi: ScannerWifiStatus? = null,
    val statusFailed: Boolean = false,
    /** The network whose password (or confirmation, when open) is being asked for. */
    val selected: WifiNetwork? = null,
    /** A join on its way; it can take about a minute. */
    val joining: WifiNetwork? = null,
    val outcome: JoinOutcome? = null,
) {
    /** False when the scanner has no working Wi-Fi: no join is offered. */
    val canJoin: Boolean get() = scannerWifi?.state != WifiState.Unavailable && joining == null
}

/** How the last join ended: with a result, or not taken at all. */
sealed interface JoinOutcome {
    val network: WifiNetwork

    data class Finished(override val network: WifiNetwork, val result: WifiJoinResult) : JoinOutcome
    data class Rejected(override val network: WifiNetwork, val error: DomainError) : JoinOutcome
}

sealed interface WifiSetupAction {
    /** The screen is shown: read the scanner's Wi-Fi and search for networks. */
    data object Opened : WifiSetupAction
    data object Scan : WifiSetupAction
    data class Select(val network: WifiNetwork) : WifiSetupAction
    data object CancelSelection : WifiSetupAction

    /**
     * Joins the selected network. [password] is null for an open network; it is copied into a
     * [WifiPassword] and the caller's array is cleared.
     */
    class Join(val password: CharArray?) : WifiSetupAction {
        override fun toString() = "Join(***)"
    }

    data object DismissOutcome : WifiSetupAction
}

class WifiSetupViewModel(
    private val scanWifiNetworks: ScanWifiNetworks,
    private val joinWifi: JoinWifi,
    private val readScannerWifi: ReadScannerWifi,
) : ViewModel() {

    private val mutableState = MutableStateFlow(WifiSetupUiState())
    val state: StateFlow<WifiSetupUiState> = mutableState.asStateFlow()
    private var scan: Job? = null

    fun onAction(action: WifiSetupAction) {
        when (action) {
            WifiSetupAction.Opened -> {
                // The ViewModel outlives the screen; an old outcome is not news on a new visit.
                if (mutableState.value.joining == null) mutableState.update { it.copy(outcome = null, selected = null) }
                refreshStatus()
                startScan()
            }
            WifiSetupAction.Scan -> startScan()
            is WifiSetupAction.Select ->
                if (action.network.security !is WifiSecurity.Unsupported && mutableState.value.canJoin) {
                    mutableState.update { it.copy(selected = action.network, outcome = null) }
                }
            WifiSetupAction.CancelSelection -> mutableState.update { it.copy(selected = null) }
            is WifiSetupAction.Join -> join(action.password)
            WifiSetupAction.DismissOutcome -> mutableState.update { it.copy(outcome = null) }
        }
    }

    private fun join(chars: CharArray?) {
        val network = mutableState.value.selected
        val password = chars?.let(::WifiPassword)
        chars?.fill('\u0000')
        if (network == null || !mutableState.value.canJoin) {
            password?.clear()
            return
        }
        mutableState.update { it.copy(selected = null, joining = network, outcome = null) }
        viewModelScope.launch {
            val outcome = when (val result = joinWifi(network, password)) {
                is Outcome.Ok -> JoinOutcome.Finished(network, result.value)
                is Outcome.Failed -> JoinOutcome.Rejected(network, result.error)
            }
            mutableState.update { it.copy(joining = null, outcome = outcome) }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        viewModelScope.launch {
            when (val status = readScannerWifi()) {
                is Outcome.Ok -> mutableState.update { it.copy(scannerWifi = status.value, statusFailed = false) }
                is Outcome.Failed -> mutableState.update { it.copy(statusFailed = true) }
            }
        }
    }

    private fun startScan() {
        if (scan?.isActive == true) return
        mutableState.update { it.copy(scanning = true, scanProblem = null) }
        scan = viewModelScope.launch {
            when (val result = scanWifiNetworks()) {
                is WifiScan.Networks -> mutableState.update {
                    it.copy(scanning = false, networks = result.networks, cached = result.cached)
                }
                is WifiScan.Failed -> mutableState.update { it.copy(scanning = false, scanProblem = result.problem) }
            }
        }
    }
}
