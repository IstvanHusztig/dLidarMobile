package com.dlidar.mobile.presentation.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dlidar.mobile.domain.result.Outcome
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.ReadScannerLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The scanner's log, a page at a time. The newest page is read when the Diagnostics tab is first shown
 * (or once the scanner can answer); older pages when the list nears its end. Refresh and a new
 * level read the newest page again and replace what is held, so no lines need merging.
 */
class LogViewModel(
    private val observeScanner: ObserveScanner,
    private val readScannerLog: ReadScannerLog,
) : ViewModel() {

    private val held = MutableStateFlow(HeldLog())
    private val access = observeScanner().map(LogAccessInput::from).distinctUntilChanged()
    private var opened = false
    private var read: Job? = null
    /** Counts reads, so the answer to one that was replaced is dropped. */
    private var generation = 0

    val state: StateFlow<LogUiState> = combine(access, held) { input, log ->
        LogUiState(
            threshold = log.threshold,
            rows = log.rows,
            access = input.access,
            loading = log.loading,
            startReached = log.loaded && log.older == null,
            canLoadOlder = input.access == LogUiState.Access.Live && log.older != null && log.loading == LogUiState.Loading.None,
            failure = log.failure,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LogUiState())

    init {
        var scannerId: String? = null
        var wasLive = false
        access.onEach { input ->
            // Another scanner's lines are not this one's.
            if (input.scannerId != scannerId) {
                scannerId = input.scannerId
                cancelRead()
                held.update { HeldLog(threshold = it.threshold) }
            }
            // Each time the scanner can answer again (it may have rebooted or logged meanwhile),
            // the newest page is read; the lines held stay shown until it arrives.
            val live = input.access == LogUiState.Access.Live
            if (live && !wasLive && opened) readNewest()
            wasLive = live
        }.launchIn(viewModelScope)
    }

    fun onAction(action: LogAction) {
        when (action) {
            LogAction.Opened -> {
                opened = true
                if (canRead() && !held.value.loaded && held.value.loading == LogUiState.Loading.None) readNewest()
            }
            LogAction.Refresh -> if (canRead()) readNewest()
            LogAction.LoadOlder -> if (canRead() && held.value.older != null && held.value.loading == LogUiState.Loading.None) readOlder()
            is LogAction.SetThreshold -> if (action.threshold != held.value.threshold) {
                cancelRead()
                held.value = HeldLog(threshold = action.threshold)
                if (canRead()) readNewest()
            }
        }
    }

    /** The held lines as text to share, oldest first. */
    fun exportText(zone: ZoneId = ZoneId.systemDefault()): String = LogExport.text(held.value.entries, zone)

    /** Read from the snapshot itself, so an action right after a change sees it. */
    private fun canRead() = LogAccessInput.from(observeScanner().value).access == LogUiState.Access.Live

    /** Replaces what is held once it succeeds; until then the old lines stay shown. */
    private fun readNewest() {
        val threshold = held.value.threshold
        start(LogUiState.Loading.Newest) {
            when (val outcome = readScannerLog(null, threshold)) {
                is Outcome.Ok -> HeldLog(
                    threshold = threshold,
                    rows = LogRow.of(outcome.value.entries),
                    entries = outcome.value.entries,
                    older = outcome.value.older,
                    loaded = true,
                )
                is Outcome.Failed -> failed(LogUiState.Loading.Newest, outcome)
            }
        }
    }

    private fun readOlder() {
        val before = held.value.older ?: return
        val threshold = held.value.threshold
        start(LogUiState.Loading.Older) {
            when (val outcome = readScannerLog(before, threshold)) {
                is Outcome.Ok -> held.value.let { log ->
                    val entries = log.entries + outcome.value.entries
                    log.copy(rows = LogRow.of(entries), entries = entries, older = outcome.value.older, loading = LogUiState.Loading.None)
                }
                is Outcome.Failed -> failed(LogUiState.Loading.Older, outcome)
            }
        }
    }

    private fun failed(read: LogUiState.Loading, outcome: Outcome.Failed) =
        held.value.copy(loading = LogUiState.Loading.None, failure = LogUiState.ReadFailure(read, outcome.error))

    private fun start(loading: LogUiState.Loading, read: suspend () -> HeldLog) {
        cancelRead()
        val mine = generation
        held.update { it.copy(loading = loading, failure = null) }
        this.read = viewModelScope.launch {
            val next = read()
            if (mine == generation) held.value = next
        }
    }

    private fun cancelRead() {
        generation++
        read?.cancel()
        held.update { it.copy(loading = LogUiState.Loading.None) }
    }
}
