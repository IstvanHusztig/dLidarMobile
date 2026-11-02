package com.dlidar.mobile.debug.traffic

import com.dlidar.mobile.data.protocol.session.Traffic
import com.dlidar.mobile.data.protocol.session.TrafficListener
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class TrafficEntry(val atMillis: Long, val kind: Kind, val text: String) {
    enum class Kind(val mark: String) { Sent("->"), Received("<-"), Dropped("!!"), Link("--") }

    fun format(zone: ZoneId = ZoneId.systemDefault()): String =
        "${TIME.format(Instant.ofEpochMilli(atMillis).atZone(zone))} ${kind.mark} $text"

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}

/**
 * The last [capacity] protocol messages and link changes, in memory only. Sent lines arrive
 * already redacted from the session; nothing here is written to storage.
 */
class TrafficLog(
    private val capacity: Int = 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) : TrafficListener {

    private val mutableEntries = MutableStateFlow<List<TrafficEntry>>(emptyList())
    val entries: StateFlow<List<TrafficEntry>> = mutableEntries.asStateFlow()

    override fun onTraffic(traffic: Traffic) = when (traffic) {
        is Traffic.Sent -> add(TrafficEntry.Kind.Sent, traffic.line)
        is Traffic.Received -> add(TrafficEntry.Kind.Received, traffic.line)
        is Traffic.Dropped -> add(TrafficEntry.Kind.Dropped, "dropped ${traffic.reason}")
    }

    fun onLink(state: LinkState) {
        val text = when (state) {
            LinkState.Idle -> return
            LinkState.Connecting -> "connecting"
            is LinkState.Up -> "link up, write piece ${state.connection.maxWritePiece} B"
            is LinkState.Down -> "link down: ${describe(state.reason)}"
        }
        add(TrafficEntry.Kind.Link, text)
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }

    /** The newest entries as text, at most [maxChars] long; older entries are left out first. */
    fun export(maxChars: Int = Int.MAX_VALUE, zone: ZoneId = ZoneId.systemDefault()): String {
        val all = entries.value
        val kept = ArrayDeque<String>()
        var length = 0
        for (entry in all.asReversed()) {
            val line = entry.format(zone)
            if (length + line.length + 1 > maxChars) break
            kept.addFirst(line)
            length += line.length + 1
        }
        val omitted = all.size - kept.size
        val header = if (omitted > 0) listOf("($omitted older entries left out)") else emptyList()
        return (header + kept).joinToString("\n")
    }

    private fun add(kind: TrafficEntry.Kind, text: String) {
        val entry = TrafficEntry(clock(), kind, text)
        mutableEntries.update { (it + entry).takeLast(capacity) }
    }

    private fun describe(reason: DownReason): String = when (reason) {
        DownReason.Closed -> "closed by the app"
        is DownReason.ConnectFailed -> "connect failed (status ${reason.status})"
        is DownReason.ConnectionLost -> "connection lost (status ${reason.status})"
        DownReason.ServiceNotFound -> "scanner service not found"
        is DownReason.SetupFailed -> "setup failed at ${reason.step}"
        DownReason.BluetoothOff -> "Bluetooth off"
        DownReason.PermissionMissing -> "permission missing"
    }
}
