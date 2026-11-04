package com.dlidar.mobile

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dlidar.mobile.data.protocol.session.TrafficListener
import com.dlidar.mobile.debug.linktest.LinkTestScreen
import com.dlidar.mobile.debug.linktest.LinkTestViewModel
import com.dlidar.mobile.debug.traffic.TrafficLog
import com.dlidar.mobile.debug.traffic.TrafficLogScreen
import com.dlidar.mobile.domain.model.ScannerCandidate
import kotlinx.coroutines.launch

/** Debug builds: the link test for the scanner's echo service and the protocol traffic log. */
object DebugTools {
    const val linkTestAvailable = true
    const val trafficLogAvailable = true

    /** Share texts are capped; an Intent extra must stay well below the 1 MB Binder limit. */
    private const val SHARE_MAX_CHARS = 100_000

    private val trafficLog = TrafficLog()

    val traffic: TrafficListener get() = trafficLog

    /**
     * Called once the container is built: logs link changes next to the messages. They come from
     * a StateFlow on their own coroutine, so a short-lived state can be skipped and a change can
     * land just after the first message of a new link.
     */
    fun start(container: AppContainer) {
        container.appScope.launch { container.transport.link.collect(trafficLog::onLink) }
    }

    @Composable
    fun LinkTest(container: AppContainer, candidate: ScannerCandidate, onBack: () -> Unit) {
        // The test owns its link only while it is on screen; leaving it closes the link.
        val owner = remember(candidate.id) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        val viewModel = viewModel(viewModelStoreOwner = owner) {
            LinkTestViewModel(container.transport, candidate.id, container.appScope)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()
        LinkTestScreen(state, viewModel::send, viewModel::flood, viewModel::reconnect, onBack)
    }

    @Composable
    fun TrafficLogTab() {
        val entries by trafficLog.entries.collectAsStateWithLifecycle()
        val context = LocalContext.current
        TrafficLogScreen(
            entries = entries,
            onShare = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "dLidar traffic")
                    .putExtra(Intent.EXTRA_TEXT, trafficLog.export(SHARE_MAX_CHARS))
                context.startActivity(Intent.createChooser(send, null))
            },
            onClear = trafficLog::clear,
        )
    }
}
