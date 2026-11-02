package com.dlidar.mobile.debug.linktest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Debug screen; its texts are not translated. */
@Composable
fun LinkTestScreen(
    state: LinkTestUiState,
    onSend: (String) -> Unit,
    onFlood: (Int, Int) -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
) {
    var line by remember { mutableStateOf("hello scanner") }
    var count by remember { mutableStateOf("200") }
    var size by remember { mutableStateOf("600") }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onReconnect) { Text("Reconnect") }
            Text("Link: ${state.link}  write piece: ${state.maxWritePiece ?: "-"} B", Modifier.padding(top = 12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(line, { line = it }, Modifier.weight(1f), singleLine = true, label = { Text("Line") })
            Button(onClick = { onSend(line) }) { Text("Send") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSend("info") }) { Text("info") }
            OutlinedTextField(count, { count = it }, Modifier.weight(1f), singleLine = true, label = { Text("N") })
            OutlinedTextField(size, { size = it }, Modifier.weight(1f), singleLine = true, label = { Text("SIZE") })
            Button(onClick = {
                val n = count.toIntOrNull()
                val s = size.toIntOrNull()
                if (n != null && s != null) onFlood(n, s)
            }) { Text("flood") }
        }
        state.flood?.let { flood ->
            Text(
                "flood: ${flood.received}/${flood.expected} received, ${flood.missing} missing, " +
                    "${flood.outOfOrder} out of order, ${flood.bytesPerSecond} B/s, sizes ${flood.lineSizes}",
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.log) { entry -> Text(entry, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}
