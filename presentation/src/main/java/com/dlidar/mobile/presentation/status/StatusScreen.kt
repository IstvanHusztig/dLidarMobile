package com.dlidar.mobile.presentation.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.DeviceInfo
import com.dlidar.mobile.domain.model.RecordingState
import com.dlidar.mobile.domain.model.RecordingSummary
import com.dlidar.mobile.domain.model.ScannerStatus
import com.dlidar.mobile.domain.model.SensorType
import com.dlidar.mobile.domain.model.Warning
import com.dlidar.mobile.domain.model.WarningKind
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.presentation.R
import com.dlidar.mobile.presentation.format.Formatters
import com.dlidar.mobile.presentation.theme.DLidarTheme
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    state: StatusUiState,
    /** Debug builds only: opens the protocol traffic log. */
    onTrafficLog: (() -> Unit)? = null,
    onAction: (StatusAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.scannerName ?: stringResource(R.string.status_title_fallback)) },
                actions = {
                    if (onTrafficLog != null) {
                        TextButton(onClick = onTrafficLog) { Text(stringResource(R.string.action_traffic_log)) }
                    }
                    if (state.isLive && !state.updateAppRequired && !state.updateScannerRequired && !state.notAnswering) {
                        TextButton(onClick = { onAction(StatusAction.Refresh) }) { Text(stringResource(R.string.action_refresh)) }
                    }
                    TextButton(onClick = { onAction(StatusAction.Disconnect) }) { Text(stringResource(R.string.action_disconnect)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionBanner(state, onAction) }
            if (state.updateAppRequired) item { Notice(stringResource(R.string.update_app), MaterialTheme.colorScheme.errorContainer) }
            if (state.updateScannerRequired) item { Notice(stringResource(R.string.update_scanner), MaterialTheme.colorScheme.errorContainer) }
            if (state.notAnswering) item { Notice(stringResource(R.string.not_answering), MaterialTheme.colorScheme.errorContainer) }
            if (state.refreshError != null) item { Notice(stringResource(R.string.refresh_failed), MaterialTheme.colorScheme.errorContainer) }
            if (state.showRecordingControls) item { RecordingControls(state, onAction) }
            item { RecordingCard(state.status, state.freeBytes, state.isLive) }
            if (state.warnings.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.section_warnings)) }
                items(state.warnings, key = { it.id }) { warning -> WarningCard(warning, onAction) }
            }
            state.summary?.let { summary -> item { SummaryCard(summary) } }
            state.info?.let { info -> item { DeviceCard(info) } }
        }
    }
}

@Composable
private fun ConnectionBanner(state: StatusUiState, onAction: (StatusAction) -> Unit) {
    val text = when (val connection = state.connection) {
        ConnectionState.Disconnected -> stringResource(R.string.conn_disconnected)
        is ConnectionState.Connecting ->
            if (connection.attempt <= 1) stringResource(R.string.conn_connecting)
            else stringResource(R.string.conn_connecting_attempt, connection.attempt)
        ConnectionState.Connected -> stringResource(R.string.conn_connected)
        is ConnectionState.WaitingToRetry -> stringResource(R.string.conn_waiting, ((connection.retryInMillis + 999) / 1000).toInt())
        ConnectionState.WaitingForBluetooth -> stringResource(R.string.conn_waiting_bluetooth)
        is ConnectionState.Refused -> stringResource(R.string.conn_refused)
        is ConnectionState.Failed -> when (connection.reason) {
            ConnectionState.Failed.Reason.PermissionMissing -> stringResource(R.string.conn_failed_permission)
            ConnectionState.Failed.Reason.NotADLidar -> stringResource(R.string.conn_failed_not_dlidar)
            ConnectionState.Failed.Reason.NotPaired -> stringResource(R.string.conn_failed_not_paired)
            ConnectionState.Failed.Reason.Other -> stringResource(R.string.conn_failed_other)
        }
    }
    val color = if (state.isLive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium)
            if (!state.isLive && state.status != null) {
                Text(stringResource(R.string.values_stale), style = MaterialTheme.typography.bodySmall)
            }
            if (state.canRetry) {
                OutlinedButton(onClick = { onAction(StatusAction.Retry) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun RecordingControls(state: StatusUiState, onAction: (StatusAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onAction(StatusAction.StartRecording) },
                enabled = state.canStart,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_start)) }
            Button(
                onClick = { onAction(StatusAction.StopRecording) },
                enabled = state.canStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_stop)) }
        }
        if (state.saving) Text(stringResource(R.string.recording_saving), style = MaterialTheme.typography.bodySmall)
        state.commandFailure?.let { failure ->
            Notice(commandFailureText(failure), MaterialTheme.colorScheme.errorContainer)
        }
    }
}

@Composable
private fun commandFailureText(failure: StatusUiState.CommandFailure): String = when {
    failure.error == DomainError.Busy -> stringResource(R.string.recording_busy)
    failure.command == StatusUiState.RecordingCommand.Start -> stringResource(R.string.recording_start_failed)
    else -> stringResource(R.string.recording_stop_failed)
}

@Composable
private fun RecordingCard(status: ScannerStatus?, freeBytes: Long?, isLive: Boolean) {
    val waiting = stringResource(R.string.value_waiting)
    InfoCard(stringResource(R.string.section_recording)) {
        Field(stringResource(R.string.label_state), status?.state?.let { recordingStateLabel(it) } ?: waiting, emphasize = isLive)
        Field(stringResource(R.string.label_chunks), status?.chunks?.let(Formatters::count) ?: waiting)
        Field(stringResource(R.string.label_points), status?.points?.let(Formatters::count) ?: waiting)
        Field(
            stringResource(R.string.label_last_ok),
            status?.lastOk?.let { stringResource(if (it) R.string.last_ok_yes else R.string.last_ok_no) } ?: waiting,
        )
        Field(stringResource(R.string.label_location), status?.location?.takeIf { it.isNotEmpty() } ?: waiting)
        Field(
            stringResource(R.string.label_free_space),
            when {
                status == null -> waiting
                freeBytes == null -> stringResource(R.string.value_unknown)
                else -> Formatters.bytes(freeBytes)
            },
        )
    }
}

@Composable
private fun WarningCard(warning: Warning, onAction: (StatusAction) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The scanner's text is complete and readable; it is shown as it is.
            Text(warning.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAction(StatusAction.DismissWarning(warning.id)) }) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: RecordingSummary) {
    InfoCard(stringResource(R.string.section_summary)) {
        Field(stringResource(R.string.label_imu_mode), imuModeLabel(summary.imuMode))
        Field(stringResource(R.string.label_decision), summary.decision)
        Field(stringResource(R.string.label_imu_in), Formatters.count(summary.imuIn))
        Field(stringResource(R.string.label_imu_out), Formatters.count(summary.imuOut))
        Field(stringResource(R.string.label_lost_samples), Formatters.count(summary.lostSamples))
    }
}

@Composable
private fun DeviceCard(info: DeviceInfo) {
    InfoCard(stringResource(R.string.section_device)) {
        Field(stringResource(R.string.label_version), info.version)
        Field(stringResource(R.string.label_sensor), sensorLabel(info.sensor))
        Field(stringResource(R.string.label_sensor_model), info.sensorModel)
        Field(stringResource(R.string.label_recording_format), info.recordingFormat.toString())
        Field(stringResource(R.string.label_protocol), info.protocol.toString())
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Field(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasize) FontWeight.SemiBold else null)
    }
}

@Composable
private fun Notice(text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun imuModeLabel(raw: String): String = when (raw) {
    "sensor" -> stringResource(R.string.imu_mode_sensor)
    "host_aligned" -> stringResource(R.string.imu_mode_host_aligned)
    "auto" -> stringResource(R.string.imu_mode_auto)
    else -> raw
}

@Composable
private fun recordingStateLabel(state: RecordingState): String = when (state) {
    RecordingState.Idle -> stringResource(R.string.state_idle)
    RecordingState.Preparing -> stringResource(R.string.state_preparing)
    RecordingState.Recording -> stringResource(R.string.state_recording)
    RecordingState.Finalizing -> stringResource(R.string.state_finalizing)
    RecordingState.Error -> stringResource(R.string.state_error)
    is RecordingState.Unknown -> stringResource(R.string.state_unknown, state.raw)
}

@Composable
private fun sensorLabel(sensor: SensorType): String = when (sensor) {
    SensorType.UnitreeL2 -> stringResource(R.string.sensor_unitree_l2)
    SensorType.LivoxMid360s -> stringResource(R.string.sensor_livox_mid360s)
    is SensorType.Unknown -> sensor.raw
}

@Preview
@Composable
private fun StatusScreenPreview() {
    DLidarTheme {
        StatusScreen(
            state = StatusUiState(
                scannerName = "dLidar",
                connection = ConnectionState.Connected,
                isLive = true,
                showRecordingControls = true,
                canStop = true,
                status = ScannerStatus(RecordingState.Recording, 12, 2_400_000, true, "/media/usb/scans"),
                freeBytes = 63_200_000_000,
                info = DeviceInfo("1.0.0", SensorType.UnitreeL2, "L2", 3, 1),
                warnings = listOf(Warning(1, WarningKind.LensDirty, "The lens looks dirty. Clean it before the next scan.", Instant.EPOCH)),
            ),
            onAction = {},
        )
    }
}
