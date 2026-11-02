package com.dlidar.mobile

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dlidar.mobile.domain.model.ConnectionState
import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.infra.ble.BlePermissions
import com.dlidar.mobile.presentation.connect.ConnectAction
import com.dlidar.mobile.presentation.connect.ConnectScreen
import com.dlidar.mobile.presentation.connect.ConnectViewModel
import com.dlidar.mobile.presentation.status.StatusAction
import com.dlidar.mobile.presentation.status.StatusScreen
import com.dlidar.mobile.presentation.status.StatusViewModel

private sealed interface Screen {
    data object Connect : Screen
    data object Status : Screen
    data object TrafficLog : Screen
    data class LinkTest(val candidate: ScannerCandidate) : Screen
}

/** The link test is not restored after recreation; its connection is gone by then. */
private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Status -> "status"
            Screen.TrafficLog -> "traffic"
            else -> "connect"
        }
    },
    restore = {
        when (it) {
            "status" -> Screen.Status
            "traffic" -> Screen.TrafficLog
            else -> Screen.Connect
        }
    },
)

/** Navigation and the Android-only work the screens cannot do: permissions and system settings. */
@Composable
fun AppRoot(container: AppContainer) {
    val snapshot by container.observeScanner().collectAsStateWithLifecycle()
    // The connection outlives the Activity, so after recreation the app returns to the status
    // screen instead of starting a new search (which could tear the live link down).
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf(if (snapshot.connection != ConnectionState.Disconnected) Screen.Status else Screen.Connect)
    }
    // After process death the saved screen may be Status while no scanner is selected.
    val shown = if (screen == Screen.Status && snapshot.target == null) Screen.Connect else screen

    when (val current = shown) {
        Screen.Connect -> ConnectRoute(
            container = container,
            onConnected = { screen = Screen.Status },
            onLinkTest = if (DebugTools.linkTestAvailable) ({ candidate -> screen = Screen.LinkTest(candidate) }) else null,
        )
        Screen.Status -> {
            BackHandler {
                container.disconnectScanner()
                screen = Screen.Connect
            }
            StatusRoute(
                container,
                onDisconnected = { screen = Screen.Connect },
                onTrafficLog = if (DebugTools.trafficLogAvailable) ({ screen = Screen.TrafficLog }) else null,
            )
        }
        Screen.TrafficLog -> {
            BackHandler { screen = Screen.Status }
            DebugTools.TrafficLog(onBack = { screen = Screen.Status })
        }
        is Screen.LinkTest -> {
            BackHandler { screen = Screen.Connect }
            DebugTools.LinkTest(container, current.candidate, onBack = { screen = Screen.Connect })
        }
    }
}

@Composable
private fun ConnectRoute(container: AppContainer, onConnected: () -> Unit, onLinkTest: ((ScannerCandidate) -> Unit)?) {
    val viewModel = viewModel { ConnectViewModel(container.findScanners, container.connectScanner) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rescan = { viewModel.onAction(ConnectAction.StartScan) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { rescan() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { rescan() }

    LaunchedEffect(Unit) { rescan() }

    ConnectScreen(
        state = state,
        onAction = { action ->
            viewModel.onAction(action)
            if (action is ConnectAction.Select) onConnected()
        },
        onFixProblem = { problem ->
            when (problem) {
                Discovery.Problem.PermissionMissing -> permissions.launch(BlePermissions.required.toTypedArray())
                Discovery.Problem.BluetoothOff ->
                    if (BlePermissions.canConnect(context)) settings.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    else permissions.launch(BlePermissions.required.toTypedArray())
                Discovery.Problem.LocationOff -> settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                Discovery.Problem.ScanFailed -> rescan()
            }
        },
        onLinkTest = onLinkTest?.let { open ->
            { candidate: ScannerCandidate ->
                viewModel.onAction(ConnectAction.StopScan)
                open(candidate)
            }
        },
    )
}

@Composable
private fun StatusRoute(container: AppContainer, onDisconnected: () -> Unit, onTrafficLog: (() -> Unit)?) {
    val viewModel = viewModel {
        StatusViewModel(
            container.observeScanner,
            container.refreshStatus,
            container.connectScanner,
            container.disconnectScanner,
            container.dismissWarning,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusScreen(state, onTrafficLog = onTrafficLog) { action ->
        viewModel.onAction(action)
        if (action is StatusAction.Disconnect) onDisconnected()
    }
}
