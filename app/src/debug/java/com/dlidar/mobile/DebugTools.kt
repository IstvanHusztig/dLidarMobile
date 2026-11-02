package com.dlidar.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dlidar.mobile.debug.linktest.LinkTestScreen
import com.dlidar.mobile.debug.linktest.LinkTestViewModel
import com.dlidar.mobile.domain.model.ScannerCandidate

/** Debug builds: the link test for the scanner's echo service. */
object DebugTools {
    const val linkTestAvailable = true

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
}
