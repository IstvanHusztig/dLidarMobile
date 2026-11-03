package com.dlidar.mobile.presentation.wifi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiCredentials
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.model.WifiSecurity
import com.dlidar.mobile.domain.model.WifiState
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.presentation.R

/**
 * The scanner's Wi-Fi: its current network, the networks the phone found, and joining one.
 * Fixing a scan problem (permission, Wi-Fi or location off) needs the Activity, so
 * [onFixProblem] is handled by the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSetupScreen(
    state: WifiSetupUiState,
    onAction: (WifiSetupAction) -> Unit,
    onFixProblem: (WifiScan.Problem) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wifi_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
                actions = {
                    if (!state.scanning) {
                        TextButton(onClick = { onAction(WifiSetupAction.Scan) }) { Text(stringResource(R.string.connect_scan)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ScannerWifiCard(state.scannerWifi, state.statusFailed) }
                state.outcome?.let { outcome -> item { OutcomeCard(outcome, onAction) } }
                state.scanProblem?.let { problem -> item { ProblemCard(problem, onFixProblem) } }
                if (state.cached) item { Text(stringResource(R.string.wifi_cached), style = MaterialTheme.typography.bodySmall) }
                if (state.networks.isEmpty() && state.scanProblem == null) {
                    item {
                        Text(
                            stringResource(if (state.scanning) R.string.wifi_scanning else R.string.wifi_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.networks, key = { it.ssid }) { network ->
                    NetworkRow(network, enabled = state.canJoin, onSelect = { onAction(WifiSetupAction.Select(network)) })
                }
            }
        }
    }
    state.selected?.let { network -> JoinDialog(network, onAction) }
    state.joining?.let { network -> JoiningDialog(network) }
}

@Composable
private fun ScannerWifiCard(status: ScannerWifiStatus?, failed: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.wifi_scanner_section), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val text = when {
                status == null && failed -> stringResource(R.string.wifi_status_failed)
                status == null -> stringResource(R.string.value_waiting)
                else -> when (val wifiState = status.state) {
                    WifiState.Connected -> stringResource(R.string.wifi_state_connected, status.ssid.orEmpty())
                    WifiState.Connecting -> stringResource(R.string.wifi_state_connecting, status.ssid.orEmpty())
                    WifiState.Disconnected -> stringResource(R.string.wifi_state_disconnected)
                    WifiState.Unavailable -> stringResource(R.string.wifi_state_unavailable)
                    is WifiState.Unknown -> stringResource(R.string.state_unknown, wifiState.raw)
                }
            }
            Text(text)
            status?.ip?.let { Text(stringResource(R.string.wifi_ip, it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun NetworkRow(network: WifiNetwork, enabled: Boolean, onSelect: () -> Unit) {
    val supported = network.security !is WifiSecurity.Unsupported
    val clickable = enabled && supported
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = clickable, onClick = onSelect)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    network.ssid,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (clickable) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(securityLabel(network.security), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.connect_signal, network.rssi), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun securityLabel(security: WifiSecurity): String = when (security) {
    WifiSecurity.Wpa2Psk -> stringResource(R.string.wifi_security_wpa2)
    WifiSecurity.Wpa3Sae -> stringResource(R.string.wifi_security_wpa3)
    WifiSecurity.Open -> stringResource(R.string.wifi_security_open)
    is WifiSecurity.Unsupported -> stringResource(R.string.wifi_security_unsupported, security.kind)
}

@Composable
private fun JoinDialog(network: WifiNetwork, onAction: (WifiSetupAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(WifiSetupAction.CancelSelection) },
        title = { Text(network.ssid) },
        text = { JoinForm(network, onAction) },
        confirmButton = {},
    )
}

/**
 * Asks for the password (or, for an open network, a confirmation), with Cancel and Connect.
 * Compose keeps the text as a String while typing; it is handed over as a CharArray and the
 * field is emptied on Connect and when the form closes.
 */
@Composable
internal fun JoinForm(network: WifiNetwork, onAction: (WifiSetupAction) -> Unit) {
    var text by remember(network) { mutableStateOf("") }
    var visible by remember(network) { mutableStateOf(false) }
    DisposableEffect(network) { onDispose { text = "" } }
    val needsPassword = network.security.needsPassword
    val problem = if (needsPassword) WifiCredentials.passwordProblem(network.security, text.toCharArray()) else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (needsPassword) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.wifi_password)) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            if (text.isNotEmpty() && problem != null) {
                Text(stringResource(R.string.wifi_password_rule), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = visible, onCheckedChange = { visible = it })
                Text(stringResource(R.string.wifi_show_password))
            }
        } else {
            Text(stringResource(R.string.wifi_open_confirm))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onAction(WifiSetupAction.CancelSelection) }) { Text(stringResource(R.string.action_cancel)) }
            Button(
                enabled = problem == null,
                onClick = {
                    val chars = if (needsPassword) text.toCharArray() else null
                    text = ""
                    onAction(WifiSetupAction.Join(chars))
                },
            ) { Text(stringResource(R.string.action_connect)) }
        }
    }
}

@Composable
private fun JoiningDialog(network: WifiNetwork) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(network.ssid) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.wifi_joining))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun OutcomeCard(outcome: JoinOutcome, onAction: (WifiSetupAction) -> Unit) {
    val success = outcome is JoinOutcome.Finished && outcome.result is WifiJoinResult.Connected
    val text = when (outcome) {
        is JoinOutcome.Finished -> when (val result = outcome.result) {
            is WifiJoinResult.Connected -> result.ip
                ?.let { ip -> stringResource(R.string.wifi_result_connected_ip, result.ssid, ip) }
                ?: stringResource(R.string.wifi_result_connected, result.ssid)
            WifiJoinResult.WrongPassword -> stringResource(R.string.wifi_result_wrong_password)
            WifiJoinResult.NotFound -> stringResource(R.string.wifi_result_not_found)
            WifiJoinResult.Timeout -> stringResource(R.string.wifi_result_timeout)
            is WifiJoinResult.Failed -> result.message
            WifiJoinResult.Unknown -> stringResource(R.string.wifi_result_unknown)
        }
        is JoinOutcome.Rejected -> when (val error = outcome.error) {
            DomainError.Busy -> stringResource(R.string.wifi_rejected_busy)
            is DomainError.Remote -> error.message
            else -> stringResource(R.string.wifi_rejected_other)
        }
    }
    val retry = !success
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (retry) {
                    Button(onClick = { onAction(WifiSetupAction.Select(outcome.network)) }) { Text(stringResource(R.string.action_retry)) }
                }
                TextButton(onClick = { onAction(WifiSetupAction.DismissOutcome) }) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

@Composable
private fun ProblemCard(problem: WifiScan.Problem, onFixProblem: (WifiScan.Problem) -> Unit) {
    val (message, fix) = when (problem) {
        WifiScan.Problem.PermissionMissing -> R.string.wifi_problem_permission to R.string.fix_permission
        WifiScan.Problem.LocationOff -> R.string.wifi_problem_location to R.string.fix_location
        WifiScan.Problem.WifiOff -> R.string.wifi_problem_wifi_off to R.string.wifi_fix_wifi
        WifiScan.Problem.ScanFailed -> R.string.problem_scan_failed to R.string.fix_retry
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(message))
            Button(onClick = { onFixProblem(problem) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(fix)) }
        }
    }
}
