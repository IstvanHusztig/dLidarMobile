package com.dlidar.mobile.presentation.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dlidar.mobile.domain.model.LogEntry
import com.dlidar.mobile.domain.model.LogLevel
import com.dlidar.mobile.domain.model.LogSource
import com.dlidar.mobile.domain.model.LogThreshold
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.presentation.R
import com.dlidar.mobile.presentation.log.LogUiState.Access
import com.dlidar.mobile.presentation.log.LogUiState.Loading
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Diagnostics tab: the scanner's log, newest first, older pages read as the list nears its end.
 * [traffic] (debug builds) is the protocol traffic log, offered beside it.
 */
@Composable
fun LogScreen(
    state: LogUiState,
    onAction: (LogAction) -> Unit,
    onShare: () -> Unit,
    traffic: (@Composable () -> Unit)? = null,
) {
    var showTraffic by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (traffic != null) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showTraffic, onClick = { showTraffic = false }, label = { Text(stringResource(R.string.log_view_scanner)) })
                FilterChip(selected = showTraffic, onClick = { showTraffic = true }, label = { Text(stringResource(R.string.log_view_traffic)) })
            }
        }
        if (traffic != null && showTraffic) {
            Box(Modifier.fillMaxSize()) { traffic() }
        } else {
            ScannerLog(state, onAction, onShare)
        }
    }
}

@Composable
private fun ScannerLog(state: LogUiState, onAction: (LogAction) -> Unit, onShare: () -> Unit) {
    val action by rememberUpdatedState(onAction)
    val latest by rememberUpdatedState(state)
    LaunchedEffect(Unit) { action(LogAction.Opened) }
    val zone = remember { ZoneId.systemDefault() }
    val list = rememberLazyListState()
    // Another level is a new list; it starts at its newest line.
    LaunchedEffect(state.threshold) { if (list.firstVisibleItemIndex > 0) list.scrollToItem(0) }
    // Near the end of the list, the next older page is asked for; again after each page that
    // added lines, so a short page on a tall screen is followed by the next.
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            // The loading state is part of the key, so an ask refused while a read ran is repeated.
            Triple(last >= layout.totalItemsCount - OLDER_PAGE_MARGIN, layout.totalItemsCount, latest.loading)
        }
            .distinctUntilChanged()
            .filter { (nearEnd, _, loading) -> nearEnd && loading == Loading.None }
            .collect { action(LogAction.LoadOlder) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogThreshold.entries.forEach { threshold ->
                FilterChip(
                    selected = state.threshold == threshold,
                    onClick = { onAction(LogAction.SetThreshold(threshold)) },
                    label = { Text(thresholdLabel(threshold)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val refresh = stringResource(R.string.action_refresh)
            TextButton(
                onClick = { onAction(LogAction.Refresh) },
                enabled = state.access == Access.Live && state.loading != Loading.Newest,
                modifier = Modifier.semantics { contentDescription = refresh },
            ) { Text("⟳", style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = onShare, enabled = state.hasLines) { Text(stringResource(R.string.action_share)) }
        }
        if (state.loading == Loading.Newest) LinearProgressIndicator(Modifier.fillMaxWidth())
        TopNote(state, onAction)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Rows are only ever added at the end or replaced as a whole, so positions are stable keys.
            itemsIndexed(state.rows) { _, row ->
                when (row) {
                    is LogRow.BootStart -> BootHeader(row.boot)
                    is LogRow.Line -> LogLine(row.entry, zone)
                }
            }
            item { Footer(state, onAction) }
        }
    }
}

/** Why there are no live lines, or why the newest read failed. */
@Composable
private fun TopNote(state: LogUiState, onAction: (LogAction) -> Unit) {
    val failure = state.failure?.takeIf { it.read == Loading.Newest }
    val text = when {
        failure != null -> failureText(failure.error)
        state.access == Access.NotSupported -> stringResource(R.string.log_not_supported)
        state.access == Access.Blocked -> stringResource(R.string.log_blocked)
        state.access == Access.NotConnected && state.hasLines -> stringResource(R.string.log_stale)
        state.access == Access.NotConnected -> stringResource(R.string.log_not_connected)
        state.startReached && !state.hasLines -> stringResource(R.string.log_empty)
        else -> return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (failure != null && state.access == Access.Live) {
            TextButton(onClick = { onAction(LogAction.Refresh) }) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun Footer(state: LogUiState, onAction: (LogAction) -> Unit) {
    val failure = state.failure?.takeIf { it.read == Loading.Older }
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading == Loading.Older -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            failure != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(failureText(failure.error), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                if (state.access == Access.Live) {
                    TextButton(onClick = { onAction(LogAction.LoadOlder) }) { Text(stringResource(R.string.action_retry)) }
                }
            }
            state.canLoadOlder -> TextButton(onClick = { onAction(LogAction.LoadOlder) }) { Text(stringResource(R.string.log_load_older)) }
            state.startReached && state.hasLines ->
                Text(stringResource(R.string.log_start), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BootHeader(boot: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        HorizontalDivider()
        Text(
            stringResource(R.string.log_boot, boot),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LogLine(entry: LogEntry, zone: ZoneId) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text(LIST_TIME.format(entry.time.atZone(zone)), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(levelLabel(entry.level), style = MaterialTheme.typography.labelSmall, color = levelColor(entry.level), fontWeight = FontWeight.SemiBold)
            sourceLabel(entry.source)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
        }
        // The scanner's text is shown as it is.
        Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun failureText(error: DomainError): String = when (error) {
    is DomainError.Remote -> stringResource(R.string.log_failed_scanner, error.message)
    DomainError.NotSupported -> stringResource(R.string.log_not_supported)
    else -> stringResource(R.string.log_failed)
}

@Composable
private fun thresholdLabel(threshold: LogThreshold): String = when (threshold) {
    LogThreshold.Info -> stringResource(R.string.log_threshold_info)
    LogThreshold.Warning -> stringResource(R.string.log_threshold_warning)
    LogThreshold.Error -> stringResource(R.string.log_threshold_error)
}

@Composable
private fun levelLabel(level: LogLevel): String = when (level) {
    LogLevel.Info -> stringResource(R.string.log_level_info)
    LogLevel.Warning -> stringResource(R.string.log_level_warning)
    LogLevel.Error -> stringResource(R.string.log_level_error)
    is LogLevel.Unknown -> level.raw
}

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.Warning -> WARNING_AMBER
    LogLevel.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** dLidar's own lines need no label; systemd's do. */
@Composable
private fun sourceLabel(source: LogSource): String? = when (source) {
    LogSource.Dlidar -> null
    LogSource.System -> stringResource(R.string.log_source_system)
    is LogSource.Unknown -> source.raw
}

private val WARNING_AMBER = Color(0xFFFFB300)
private val LIST_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** How many rows before the end the next older page is asked for. */
private const val OLDER_PAGE_MARGIN = 5
