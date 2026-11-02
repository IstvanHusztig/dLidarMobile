package com.dlidar.mobile.debug.traffic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId

/** Debug screen; its texts are not translated. The newest entry is at the bottom. */
@Composable
fun TrafficLogScreen(
    entries: List<TrafficEntry>,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onShare, enabled = entries.isNotEmpty()) { Text("Share") }
            TextButton(onClick = onClear, enabled = entries.isNotEmpty()) { Text("Clear") }
            Text("${entries.size} entries")
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), reverseLayout = true) {
            items(entries.asReversed(), key = { it.id }) { entry ->
                Text(
                    entry.format(zone),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when (entry.kind) {
                        TrafficEntry.Kind.Sent -> MaterialTheme.colorScheme.primary
                        TrafficEntry.Kind.Received -> MaterialTheme.colorScheme.onSurface
                        TrafficEntry.Kind.Dropped -> MaterialTheme.colorScheme.error
                        TrafficEntry.Kind.Link -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }
    }
}
