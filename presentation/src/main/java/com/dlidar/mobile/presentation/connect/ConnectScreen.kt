package com.dlidar.mobile.presentation.connect

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.presentation.R

/**
 * Lists scanners nearby. Fixing a problem (permissions, Bluetooth, location) needs the Activity,
 * so [onFixProblem] is handled by the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onAction: (ConnectAction) -> Unit,
    onFixProblem: (Discovery.Problem) -> Unit,
    onLinkTest: ((ScannerCandidate) -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connect_title)) },
                actions = {
                    if (!state.scanning) {
                        TextButton(onClick = { onAction(ConnectAction.StartScan) }) { Text(stringResource(R.string.connect_scan)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { problem -> ProblemCard(problem, onFixProblem) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.lastScanner?.let { last ->
                    item(key = "last") { LastScannerCard(last, onConnect = { onAction(ConnectAction.Select(last)) }) }
                }
                if (state.lastScanner == null && state.candidates.isEmpty() && state.problem == null) {
                    item {
                        Text(
                            stringResource(if (state.scanning) R.string.connect_scanning else R.string.connect_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.candidates, key = { it.id }) { candidate ->
                    CandidateRow(candidate, onSelect = { onAction(ConnectAction.Select(candidate)) }, onLinkTest = onLinkTest)
                }
            }
        }
    }
}

@Composable
private fun LastScannerCard(scanner: ScannerCandidate, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.connect_last), style = MaterialTheme.typography.labelMedium)
                Text(scanner.name ?: stringResource(R.string.connect_unnamed), style = MaterialTheme.typography.titleMedium)
                Text(scanner.id, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onConnect) { Text(stringResource(R.string.action_connect)) }
        }
    }
}

@Composable
private fun CandidateRow(candidate: ScannerCandidate, onSelect: () -> Unit, onLinkTest: ((ScannerCandidate) -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(candidate.name ?: stringResource(R.string.connect_unnamed), style = MaterialTheme.typography.titleMedium)
                Text(candidate.id, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.connect_signal, candidate.rssi), style = MaterialTheme.typography.bodySmall)
            }
            if (onLinkTest != null) {
                TextButton(onClick = { onLinkTest(candidate) }) { Text(stringResource(R.string.connect_link_test)) }
            }
        }
    }
}

@Composable
private fun ProblemCard(problem: Discovery.Problem, onFixProblem: (Discovery.Problem) -> Unit) {
    val (message, fix) = when (problem) {
        Discovery.Problem.BluetoothOff -> R.string.problem_bluetooth_off to R.string.fix_bluetooth
        Discovery.Problem.PermissionMissing -> R.string.problem_permission to R.string.fix_permission
        Discovery.Problem.LocationOff -> R.string.problem_location_off to R.string.fix_location
        Discovery.Problem.ScanFailed -> R.string.problem_scan_failed to R.string.fix_retry
    }
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(message))
            Button(onClick = { onFixProblem(problem) }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(fix)) }
        }
    }
}
