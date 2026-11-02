package com.dlidar.mobile.debug.linktest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.data.protocol.framing.DecodedFrame
import com.dlidar.mobile.data.protocol.framing.FrameDecoder
import com.dlidar.mobile.data.protocol.framing.FrameSplitter
import com.dlidar.mobile.data.protocol.framing.SplitResult
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LinkTestUiState(
    val link: String = "Connecting…",
    val maxWritePiece: Int? = null,
    val log: List<String> = emptyList(),
    val flood: FloodStats? = null,
)

/**
 * Talks raw lines to the scanner's echo test service: `anything` → `echo anything`, `info`, and
 * `flood N SIZE`. It uses the transport directly, so the scanner screen must be disconnected.
 */
class LinkTestViewModel(
    private val transport: ByteTransport,
    private val target: String,
    /** Outlives the ViewModel, so the link is closed after the screen is gone. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val mutableState = MutableStateFlow(LinkTestUiState())
    val state: StateFlow<LinkTestUiState> = mutableState.asStateFlow()

    private val splitter = FrameSplitter()
    /** Keeps the pieces of two lines from interleaving. */
    private val writeMutex = Mutex()
    @Volatile private var connection: ByteConnection? = null
    @Volatile private var flood: FloodMeter? = null
    private var reader: Job? = null

    init {
        reconnect()
    }

    /** (Re)opens the link, for example after the scanner screen took it over. */
    fun reconnect() {
        val previous = reader
        reader = viewModelScope.launch {
            previous?.cancelAndJoin()
            mutableState.update { it.copy(link = "Connecting…") }
            connectAndRead()
        }
    }

    fun send(line: String) {
        val text = line.replace("\n", " ").trim()
        if (text.isEmpty()) return
        viewModelScope.launch { writeMutex.withLock { write(text) } }
    }

    fun flood(count: Int, size: Int) {
        flood = FloodMeter(count, clock())
        mutableState.update { it.copy(flood = flood?.stats()) }
        send("flood $count $size")
    }

    override fun onCleared() {
        appScope.launch { transport.disconnect() }
    }

    private suspend fun connectAndRead() {
        val opened = when (val result = transport.connect(target)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.Failed -> {
                mutableState.update { it.copy(link = "Failed: ${result.reason}") }
                return
            }
        }
        connection = opened
        mutableState.update { it.copy(link = "Connected", maxWritePiece = opened.maxWritePiece) }
        val decoder = FrameDecoder()
        opened.incoming.collect { piece ->
            for (frame in decoder.feed(piece)) {
                when (frame) {
                    is DecodedFrame.Line -> onLine(frame.text)
                    is DecodedFrame.Oversized -> append("! dropped ${frame.bytes} bytes (too long)")
                    is DecodedFrame.Malformed -> append("! dropped ${frame.bytes} bytes (not UTF-8)")
                }
            }
        }
        mutableState.update { it.copy(link = "Disconnected") }
    }

    private fun onLine(line: String) {
        val meter = flood
        if (meter != null && meter.onLine(line, clock())) {
            mutableState.update { it.copy(flood = meter.stats()) }
        } else {
            append("< $line")
        }
    }

    private suspend fun write(text: String) {
        val current = connection ?: return append("! not connected")
        when (val split = splitter.split(text, current.maxWritePiece)) {
            is SplitResult.TooLarge -> append("! ${split.bytes} bytes is over the request limit")
            is SplitResult.Pieces -> {
                append("> $text (${split.pieces.size} piece(s))")
                for (piece in split.pieces) {
                    val result = current.write(piece)
                    if (result is WriteResult.Failed) return append("! write failed: ${result.reason}")
                }
            }
        }
    }

    private fun append(line: String) {
        mutableState.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private companion object {
        const val MAX_LOG_LINES = 200
    }
}
