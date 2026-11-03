package com.dlidar.mobile

import android.content.Context
import com.dlidar.mobile.data.protocol.session.DefaultProtocolSessionFactory
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.scanner.ReconnectPolicy
import com.dlidar.mobile.data.scanner.ScannerRepositoryImpl
import com.dlidar.mobile.domain.port.Clock
import com.dlidar.mobile.domain.port.ScannerRepository
import com.dlidar.mobile.domain.usecase.ConnectScanner
import com.dlidar.mobile.domain.usecase.DisconnectScanner
import com.dlidar.mobile.domain.usecase.DismissWarning
import com.dlidar.mobile.domain.usecase.FindScanners
import com.dlidar.mobile.domain.usecase.ObserveScanner
import com.dlidar.mobile.domain.usecase.RefreshStatus
import com.dlidar.mobile.domain.usecase.StartRecording
import com.dlidar.mobile.domain.usecase.StopRecording
import com.dlidar.mobile.infra.ble.AndroidBleTransport
import com.dlidar.mobile.infra.ble.BleScannerFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant

/** Composition root: the only place that creates concrete classes and joins them to their ports. */
class AppContainer(context: Context) {

    /** Lives as long as the process; the scanner connection belongs to it, not to a screen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val logger = AndroidLogger(debugEnabled = BuildConfig.DEBUG)

    val transport: ByteTransport = AndroidBleTransport.create(context, appScope, logger)

    private val repository: ScannerRepository = ScannerRepositoryImpl(
        transport = transport,
        sessionFactory = DefaultProtocolSessionFactory(logger = logger, traffic = DebugTools.traffic),
        scope = appScope,
        clock = Clock { Instant.now() },
        reconnectPolicy = ReconnectPolicy(),
        logger = logger,
    )

    val findScanners = FindScanners(BleScannerFinder(context))
    val connectScanner = ConnectScanner(repository)
    val disconnectScanner = DisconnectScanner(repository)
    val observeScanner = ObserveScanner(repository)
    val refreshStatus = RefreshStatus(repository)
    val dismissWarning = DismissWarning(repository)
    val startRecording = StartRecording(repository)
    val stopRecording = StopRecording(repository)

    init {
        DebugTools.start(this)
    }
}
